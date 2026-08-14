package win.shamserver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Alerts extends Handler {
    private static final Logger LOGGER = Logger.getLogger(Alerts.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SELF_LOGGER_NAME = Alerts.class.getName();
    private static final ThreadLocal<Boolean> IN_PUBLISH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern USER_MENTION = Pattern.compile("<@!?\\d+>");
    private static final Pattern ROLE_MENTION = Pattern.compile("<@&\\d+>");
    private static final Pattern WAIT_QUERY = Pattern.compile("(^|[?&])wait=");

    private static final DateTimeFormatter HUMAN_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd | HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private static final int MESSAGE_CONTENT_MAX = 2000;
    private static final int MESSAGE_TEXT_CHUNK_MAX = 1900;
    private static final int EMBED_TITLE_MAX = 256;
    private static final int EMBED_DESCRIPTION_MAX = 4096;
    private static final int EMBED_FIELD_NAME_MAX = 256;
    private static final int EMBED_FIELD_VALUE_MAX = 1024;
    private static final int EMBED_FOOTER_MAX = 2048;
    private static final int EMBED_TOTAL_TEXT_MAX = 6000;
    private static final int MAX_EMBED_FIELDS = 25;
    private static final int MAX_QUEUE_SIZE = 5000;
    private static final int MAX_SEND_ATTEMPTS = 5;
    private static final int MAX_INVALID_REBUILDS = 2;
    private static final int MAX_ATTACHMENT_BYTES = 512 * 1024;
    private static final int ATTACHMENT_PREFERRED_OVERFLOW_THRESHOLD = 3000;
    private static final int FP_MAX_FIELD_LEN = 512;

    private static final long SENDER_INTERVAL_MS = 1000L;
    private static final int SENDER_BATCH_SIZE = 3;
    private static final long SEND_FAIL_WARN_COOLDOWN_MS = 15_000L;
    private static final long NETWORK_BACKOFF_BASE_MS = 15_000L;
    private static final long NETWORK_BACKOFF_MAX_MS = 120_000L;
    private static final long DEDUP_WINDOW_MS = 25L;
    private static final long DEDUP_RETENTION_MS = 30_000L;
    private static final int DEDUP_MAX_SIZE_HARD = 10_000;

    private final ConcurrentHashMap<String, Long> recentFingerprints = new ConcurrentHashMap<>();
    private final AtomicInteger fingerprintOps = new AtomicInteger(0);
    private final Queue<AlertMessage> sendQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger droppedDueToFullQueue = new AtomicInteger(0);

    private final URI webhookUri;
    private final String pingText;
    private final List<String> ignoreList;
    private final Severity alertLevel;
    private final Severity pingLevel;
    private final ScheduledExecutorService senderExecutor;
    private final HttpClient http;

    private volatile long networkBackoffUntilMs = 0L;
    private volatile long networkBackoffMs = NETWORK_BACKOFF_BASE_MS;
    private volatile long lastSendFailWarnMs = 0L;
    private volatile boolean closed = false;

    private volatile boolean log4jInstalled = false;
    private volatile LoggerContext log4jCtx = null;
    private volatile String log4jAppenderName = null;

    public enum Severity {
        INFO("info", "INFO", 0x3498DB),
        WARN("warn", "WARN", 0xF1C40F),
        ERROR("error", "ERROR", 0xE74C3C);

        private final String configValue;
        private final String label;
        private final int embedColor;

        Severity(String configValue, String label, int embedColor) {
            this.configValue = configValue;
            this.label = label;
            this.embedColor = embedColor;
        }

        public String getConfigValue() {
            return configValue;
        }

        public String getLabel() {
            return label;
        }

        public int getEmbedColor() {
            return embedColor;
        }

        public boolean includes(Severity other) {
            return other != null && other.ordinal() >= ordinal();
        }

        public static Severity fromConfig(String value) {
            if (value == null) return null;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Severity severity : values()) {
                if (severity.configValue.equals(normalized)) {
                    return severity;
                }
            }
            return null;
        }

        public static Severity fromJul(Level level) {
            if (level == null) return WARN;
            if (level.intValue() >= Level.SEVERE.intValue()) return ERROR;
            if (level.intValue() >= Level.WARNING.intValue()) return WARN;
            if (level.intValue() >= Level.INFO.intValue()) return INFO;
            return null;
        }

        public static Severity fromLog4j(org.apache.logging.log4j.Level level) {
            if (level == null) return WARN;
            if (level.isMoreSpecificThan(org.apache.logging.log4j.Level.ERROR)) return ERROR;
            if (level.isMoreSpecificThan(org.apache.logging.log4j.Level.WARN)) return WARN;
            if (level.isMoreSpecificThan(org.apache.logging.log4j.Level.INFO)) return INFO;
            return null;
        }
    }

    private enum RenderMode {
        EMBED,
        PLAIN_WITH_FILE,
        PLAIN_CHUNKS
    }

    private static final class AlertMessage {
        final Severity severity;
        final String loggerName;
        final String body;
        final String exceptionSummary;
        final String stackTrace;
        final long timeMs;
        int attempts;

        AlertMessage(Severity severity, String loggerName, String body, String exceptionSummary, String stackTrace, long timeMs) {
            this.severity = severity;
            this.loggerName = (loggerName == null || loggerName.isBlank()) ? "root" : loggerName;
            this.body = body == null ? "" : body;
            this.exceptionSummary = exceptionSummary == null ? "" : exceptionSummary;
            this.stackTrace = stackTrace == null ? "" : stackTrace;
            this.timeMs = timeMs > 0L ? timeMs : System.currentTimeMillis();
            this.attempts = 0;
        }
    }

    private static final class WebhookRequestPlan {
        final PreparedWebhookRequest primaryRequest;
        final List<PreparedWebhookRequest> followUpRequests;

        WebhookRequestPlan(PreparedWebhookRequest primaryRequest, List<PreparedWebhookRequest> followUpRequests) {
            this.primaryRequest = primaryRequest;
            this.followUpRequests = followUpRequests;
        }
    }

    private static final class PreparedWebhookRequest {
        final HttpRequest request;
        final String logSummary;

        PreparedWebhookRequest(HttpRequest request, String logSummary) {
            this.request = request;
            this.logSummary = logSummary;
        }
    }

    private static final class SendResult {
        final boolean success;
        final boolean retryable;
        final boolean countsAttempt;
        final boolean payloadRejected;
        final long retryAfterMs;
        final String message;

        private SendResult(boolean success, boolean retryable, boolean countsAttempt, boolean payloadRejected, long retryAfterMs, String message) {
            this.success = success;
            this.retryable = retryable;
            this.countsAttempt = countsAttempt;
            this.payloadRejected = payloadRejected;
            this.retryAfterMs = retryAfterMs;
            this.message = message == null ? "" : message;
        }

        static SendResult success() {
            return new SendResult(true, false, false, false, 0L, "");
        }

        static SendResult failure(boolean retryable, boolean countsAttempt, boolean payloadRejected, long retryAfterMs, String message) {
            return new SendResult(false, retryable, countsAttempt, payloadRejected, retryAfterMs, message);
        }
    }

    private static final class OverflowSections {
        final String messageOverflow;
        final String stackTrace;

        OverflowSections(String messageOverflow, String stackTrace) {
            this.messageOverflow = messageOverflow == null ? "" : messageOverflow;
            this.stackTrace = stackTrace == null ? "" : stackTrace;
        }

        boolean isEmpty() {
            return messageOverflow.isBlank() && stackTrace.isBlank();
        }

        String asAttachmentText(AlertMessage entry) {
            StringBuilder out = new StringBuilder();
            out.append("Severity: ").append(entry.severity.getLabel()).append('\n');
            out.append("Logger: ").append(entry.loggerName).append('\n');
            out.append("Time: ").append(formatTime(entry.timeMs)).append(" UTC").append('\n');
            if (!messageOverflow.isBlank()) {
                out.append('\n').append("Message Overflow").append('\n');
                out.append(messageOverflow.trim()).append('\n');
            }
            if (!stackTrace.isBlank()) {
                out.append('\n').append("Stack Trace").append('\n');
                out.append(stackTrace.trim()).append('\n');
            }
            return out.toString().trim();
        }
    }

    private static final class LimitedText {
        final String shown;
        final String remainder;

        LimitedText(String shown, String remainder) {
            this.shown = shown;
            this.remainder = remainder;
        }
    }

    private static final class EmbedBuildResult {
        final Map<String, Object> embed;
        final OverflowSections overflow;

        EmbedBuildResult(Map<String, Object> embed, OverflowSections overflow) {
            this.embed = embed;
            this.overflow = overflow;
        }
    }

    private static final class MultipartAttachment {
        final String filename;
        final byte[] bytes;
        final String description;

        MultipartAttachment(String filename, byte[] bytes, String description) {
            this.filename = filename;
            this.bytes = bytes;
            this.description = description;
        }
    }

    private static final class RateLimitInfo {
        final long retryAfterMs;

        RateLimitInfo(long retryAfterMs) {
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static final class Log4jForwardingAppender extends AbstractAppender {
        private final Alerts alerts;

        private Log4jForwardingAppender(String name, Alerts alerts) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
            this.alerts = alerts;
        }

        @Override
        public void append(LogEvent event) {
            if (alerts != null) {
                alerts.onLog4jEvent(event);
            }
        }
    }

    public Alerts(String webhookUrl, String pingText, List<String> ignoreList, Severity alertLevel, Severity pingLevel) {
        this.webhookUri = buildWebhookUri(Objects.requireNonNull(webhookUrl, "webhookUrl"));
        this.pingText = (pingText == null || pingText.trim().isEmpty()) ? "@everyone" : pingText.trim();
        this.alertLevel = Objects.requireNonNull(alertLevel, "alertLevel");
        this.pingLevel = pingLevel;

        setLevel(Level.ALL);

        List<String> effectiveIgnore = (ignoreList == null) ? Collections.emptyList() : new ArrayList<>(ignoreList);
        if (effectiveIgnore.isEmpty()) {
            List<String> disk = tryLoadIgnoreListFromDisk();
            if (!disk.isEmpty()) {
                effectiveIgnore = disk;
                LOGGER.info("[Alerts] Loaded " + effectiveIgnore.size() + " ignore pattern(s) from config.yml on disk.");
            }
        }
        if (effectiveIgnore.isEmpty()) {
            this.ignoreList = Collections.emptyList();
        } else {
            List<String> cleaned = new ArrayList<>(effectiveIgnore.size());
            for (String pattern : effectiveIgnore) {
                if (pattern == null) continue;
                String trimmed = pattern.trim();
                if (!trimmed.isEmpty()) cleaned.add(trimmed);
            }
            this.ignoreList = cleaned.isEmpty() ? Collections.emptyList() : cleaned;
        }

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        this.senderExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Alerts-Sender-Thread");
            thread.setDaemon(true);
            return thread;
        });
        this.senderExecutor.scheduleAtFixedRate(this::processQueueSafely, 0L, SENDER_INTERVAL_MS, TimeUnit.MILLISECONDS);

        installLog4jAppender();
    }

    @Override
    public void publish(LogRecord record) {
        if (closed || record == null) return;
        if (!isLoggable(record)) return;
        if (isSelfRecord(record)) return;
        if (Boolean.TRUE.equals(IN_PUBLISH.get())) return;

        IN_PUBLISH.set(Boolean.TRUE);
        try {
            Severity severity = Severity.fromJul(record.getLevel());
            if (severity == null || !alertLevel.includes(severity)) return;

            String bodyRaw = safeFormatBodyRaw(record);
            String bodySan = sanitizeForDiscord(bodyRaw);
            Throwable thrown = record.getThrown();
            long timeMs = record.getMillis() > 0L ? record.getMillis() : System.currentTimeMillis();

            String formattedRaw = formatAlertText(severity, record.getLoggerName(), bodyRaw, timeMs, thrown, false);
            String formattedSan = formatAlertText(severity, record.getLoggerName(), bodySan, timeMs, thrown, true);
            if (shouldIgnoreStrings(formattedRaw, formattedSan, bodyRaw, bodySan, thrown)) return;

            String fingerprint = buildFingerprint(severity, record.getLoggerName(), bodySan, thrown);
            if (shouldDropDuplicate(fingerprint, timeMs)) return;

            enqueueMessage(new AlertMessage(
                    severity,
                    record.getLoggerName(),
                    bodySan,
                    sanitizeForDiscord(buildExceptionSummary(thrown)),
                    sanitizeForDiscord(getStackTraceString(thrown)),
                    timeMs
            ));
        } catch (Throwable t) {
            try {
                LOGGER.log(Level.SEVERE, "[Alerts] publish() failed: " + t.getMessage(), t);
            } catch (Throwable ignored) {
            }
        } finally {
            IN_PUBLISH.set(Boolean.FALSE);
        }
    }

    @Override
    public void flush() {
        // nothing buffered
    }

    @Override
    public void close() throws SecurityException {
        if (closed) return;
        closed = true;

        LOGGER.info("[Alerts] Closing Alerts handler...");

        try {
            senderExecutor.shutdown();
            if (!senderExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                senderExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            try {
                senderExecutor.shutdownNow();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            try {
                senderExecutor.shutdownNow();
            } catch (Throwable ignored) {
            }
        }

        uninstallLog4jAppender();

        try {
            sendQueue.clear();
        } catch (Throwable ignored) {
        }
        try {
            recentFingerprints.clear();
        } catch (Throwable ignored) {
        }

        LOGGER.info("[Alerts] Alerts handler closed.");
    }

    private void onLog4jEvent(LogEvent event) {
        if (closed || event == null) return;
        if (Boolean.TRUE.equals(IN_PUBLISH.get())) return;

        Severity severity = Severity.fromLog4j(event.getLevel());
        if (severity == null || !alertLevel.includes(severity)) return;

        String loggerName = event.getLoggerName();
        if (SELF_LOGGER_NAME.equals(loggerName)) return;

        IN_PUBLISH.set(Boolean.TRUE);
        try {
            String bodyRaw = "";
            try {
                if (event.getMessage() != null) {
                    bodyRaw = event.getMessage().getFormattedMessage();
                }
            } catch (Throwable ignored) {
            }
            String bodySan = sanitizeForDiscord(bodyRaw);
            Throwable thrown = null;
            try {
                thrown = event.getThrown();
            } catch (Throwable ignored) {
            }
            long timeMs = event.getTimeMillis() > 0L ? event.getTimeMillis() : System.currentTimeMillis();

            String formattedRaw = formatAlertText(severity, loggerName, bodyRaw, timeMs, thrown, false);
            String formattedSan = formatAlertText(severity, loggerName, bodySan, timeMs, thrown, true);
            if (shouldIgnoreStrings(formattedRaw, formattedSan, bodyRaw, bodySan, thrown)) return;

            String fingerprint = buildFingerprint(severity, loggerName, bodySan, thrown);
            if (shouldDropDuplicate(fingerprint, timeMs)) return;

            enqueueMessage(new AlertMessage(
                    severity,
                    loggerName,
                    bodySan,
                    sanitizeForDiscord(buildExceptionSummary(thrown)),
                    sanitizeForDiscord(getStackTraceString(thrown)),
                    timeMs
            ));
        } catch (Throwable t) {
            try {
                LOGGER.log(Level.FINE, "[Alerts] onLog4jEvent() failed: " + t.getMessage(), t);
            } catch (Throwable ignored) {
            }
        } finally {
            IN_PUBLISH.set(Boolean.FALSE);
        }
    }

    private void installLog4jAppender() {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();

            String name = "ShamWebhookAlerts-" + System.identityHashCode(this);
            Log4jForwardingAppender appender = new Log4jForwardingAppender(name, this);
            appender.start();

            config.addAppender(appender);
            org.apache.logging.log4j.Level minimumLevel = alertLevel == Severity.INFO
                    ? org.apache.logging.log4j.Level.INFO
                    : org.apache.logging.log4j.Level.WARN;

            config.getRootLogger().addAppender(appender, minimumLevel, null);
            for (LoggerConfig loggerConfig : config.getLoggers().values()) {
                if (!loggerConfig.isAdditive() && !loggerConfig.getAppenders().containsKey(name)) {
                    loggerConfig.addAppender(appender, minimumLevel, null);
                }
            }

            ctx.updateLoggers();

            log4jCtx = ctx;
            log4jAppenderName = name;
            log4jInstalled = true;

            LOGGER.info("[Alerts] Log4j2 capture enabled (" + minimumLevel.name() + "+).");
        } catch (Throwable t) {
            log4jInstalled = false;
            log4jCtx = null;
            log4jAppenderName = null;
            try {
                LOGGER.log(Level.FINE, "[Alerts] Log4j2 capture not available: " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
    }

    private void uninstallLog4jAppender() {
        if (!log4jInstalled) return;
        try {
            LoggerContext ctx = log4jCtx;
            String name = log4jAppenderName;
            if (ctx == null || name == null) return;

            Configuration cfg = ctx.getConfiguration();
            try {
                cfg.getRootLogger().removeAppender(name);
            } catch (Throwable ignored) {
            }
            try {
                for (LoggerConfig loggerConfig : cfg.getLoggers().values()) {
                    try {
                        loggerConfig.removeAppender(name);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Appender appender = cfg.getAppender(name);
                try {
                    cfg.getAppenders().remove(name);
                } catch (Throwable ignored) {
                }
                if (appender != null) {
                    try {
                        appender.stop();
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                ctx.updateLoggers();
            } catch (Throwable ignored) {
            }
        } finally {
            log4jInstalled = false;
            log4jCtx = null;
            log4jAppenderName = null;
        }
    }

    private void processQueueSafely() {
        try {
            processQueue();
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "[Alerts] Sender thread error: " + t.getMessage(), t);
        }
    }

    private void processQueue() {
        if (closed || sendQueue.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now < networkBackoffUntilMs) return;

        for (int i = 0; i < SENDER_BATCH_SIZE; i++) {
            AlertMessage entry = sendQueue.poll();
            if (entry == null) break;
            sendAlert(entry);
        }
    }

    private void sendAlert(AlertMessage entry) {
        if (entry == null) return;
        if (entry.attempts >= MAX_SEND_ATTEMPTS) {
            LOGGER.warning("[Alerts] Dropping message after " + entry.attempts + " failed attempts: " + summarizeForLog(buildPlainSummary(entry, true)));
            return;
        }

        SendResult result = dispatchWithFallback(entry);
        if (result.success) {
            clearNetworkBackoff();
            return;
        }

        if (result.retryable) {
            applyBackoff(result.retryAfterMs);
        }

        maybeLogSendFailure(result.message);

        if (!closed) {
            AlertMessage retry = new AlertMessage(entry.severity, entry.loggerName, entry.body, entry.exceptionSummary, entry.stackTrace, entry.timeMs);
            retry.attempts = entry.attempts + (result.countsAttempt ? 1 : 0);
            if (retry.attempts < MAX_SEND_ATTEMPTS) {
                enqueueMessage(retry);
            } else {
                LOGGER.warning("[Alerts] Message reached max retries and will be dropped: " + summarizeForLog(buildPlainSummary(entry, true)));
            }
        }
    }

    private SendResult dispatchWithFallback(AlertMessage entry) {
        RenderMode[] modes = {
                RenderMode.EMBED,
                RenderMode.PLAIN_WITH_FILE,
                RenderMode.PLAIN_CHUNKS
        };

        int invalidRebuilds = 0;
        SendResult lastFailure = SendResult.failure(false, true, false, 0L, "unknown");

        for (RenderMode mode : modes) {
            if (mode != RenderMode.EMBED && invalidRebuilds > MAX_INVALID_REBUILDS) {
                break;
            }

            WebhookRequestPlan plan;
            try {
                plan = buildRequestPlan(entry, mode);
            } catch (JsonProcessingException e) {
                return SendResult.failure(false, true, false, 0L, "Failed to serialize Discord webhook payload: " + e.getMessage());
            } catch (Throwable t) {
                return SendResult.failure(false, true, false, 0L, "Failed to build Discord webhook payload: " + t.getMessage());
            }

            SendResult result = executePlan(plan);
            if (result.success) {
                return result;
            }

            lastFailure = result;
            if (!result.payloadRejected) {
                return result;
            }
            invalidRebuilds++;
        }

        return lastFailure;
    }

    private SendResult executePlan(WebhookRequestPlan plan) {
        boolean sentAny = false;
        SendResult primary = executeRequest(plan.primaryRequest);
        if (!primary.success) {
            return primary;
        }
        sentAny = true;

        for (PreparedWebhookRequest followUp : plan.followUpRequests) {
            SendResult followUpResult = executeRequest(followUp);
            if (!followUpResult.success) {
                if (sentAny) {
                    maybeLogSendFailure("Discord overflow delivery failed after primary alert succeeded: " + followUpResult.message);
                    return SendResult.success();
                }
                return followUpResult;
            }
        }
        return SendResult.success();
    }

    private SendResult executeRequest(PreparedWebhookRequest prepared) {
        try {
            HttpResponse<String> response = http.send(prepared.request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return SendResult.success();
            }
            if (status == 429) {
                RateLimitInfo rateLimit = readRateLimitInfo(response.headers(), response.body());
                return SendResult.failure(true, false, false, rateLimit.retryAfterMs, "Discord rate limited request (" + prepared.logSummary + ")");
            }
            if (status >= 500) {
                return SendResult.failure(true, false, false, 0L, "Discord webhook HTTP " + status + " for " + prepared.logSummary);
            }
            boolean payloadRejected = status == 400;
            String message = "Discord webhook HTTP " + status + " for " + prepared.logSummary;
            if (response.body() != null && !response.body().isBlank()) {
                message += " | " + summarizeForLog(response.body());
            }
            return SendResult.failure(false, true, payloadRejected, 0L, message);
        } catch (Throwable t) {
            boolean networkIssue = isNetworkException(t);
            return SendResult.failure(networkIssue, !networkIssue, false, 0L,
                    "Discord webhook request failed for " + prepared.logSummary + ": " + t.getMessage());
        }
    }

    private WebhookRequestPlan buildRequestPlan(AlertMessage entry, RenderMode mode) throws JsonProcessingException {
        return switch (mode) {
            case EMBED -> buildEmbedPlan(entry);
            case PLAIN_WITH_FILE -> buildPlainPlan(entry, true);
            case PLAIN_CHUNKS -> buildPlainPlan(entry, false);
        };
    }

    private WebhookRequestPlan buildEmbedPlan(AlertMessage entry) throws JsonProcessingException {
        EmbedBuildResult embedBuild = buildEmbed(entry);
        boolean shouldPing = shouldPing(entry.severity);

        Map<String, Object> payload = new LinkedHashMap<>();
        String content = shouldPing ? trimToLength(pingText, MESSAGE_CONTENT_MAX) : "";
        if (!content.isEmpty()) {
            payload.put("content", content);
        }
        payload.put("allowed_mentions", buildAllowedMentions(content));
        payload.put("embeds", List.of(embedBuild.embed));

        List<PreparedWebhookRequest> followUps = new ArrayList<>();
        MultipartAttachment attachment = chooseOverflowAttachment(entry, embedBuild.overflow);
        if (attachment != null) {
            PreparedWebhookRequest primary = buildMultipartRequest(payload, attachment, "embed alert with attachment");
            return new WebhookRequestPlan(primary, followUps);
        }

        PreparedWebhookRequest primary = buildJsonRequest(payload, "embed alert");
        followUps.addAll(buildOverflowFollowUpRequests(entry, embedBuild.overflow, false));
        return new WebhookRequestPlan(primary, followUps);
    }

    private WebhookRequestPlan buildPlainPlan(AlertMessage entry, boolean allowAttachment) throws JsonProcessingException {
        String summary = buildPlainSummary(entry, false);
        String content = shouldPing(entry.severity) ? pingText : "";
        String combined = content.isBlank() ? summary : content + "\n\n" + summary;
        LimitedText splitSummary = splitForLimit(combined, MESSAGE_CONTENT_MAX);
        String mainBody = splitSummary.shown;
        String mainOverflow = splitSummary.remainder;

        OverflowSections overflow = new OverflowSections(mainOverflow, entry.stackTrace);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", mainBody);
        payload.put("allowed_mentions", buildAllowedMentions(content));

        List<PreparedWebhookRequest> followUps = new ArrayList<>();
        MultipartAttachment attachment = allowAttachment ? chooseOverflowAttachment(entry, overflow) : null;
        PreparedWebhookRequest primary;
        if (attachment != null) {
            primary = buildMultipartRequest(payload, attachment, "plain alert with attachment");
        } else {
            primary = buildJsonRequest(payload, "plain alert");
            followUps.addAll(buildOverflowFollowUpRequests(entry, overflow, true));
        }
        return new WebhookRequestPlan(primary, followUps);
    }

    private EmbedBuildResult buildEmbed(AlertMessage entry) {
        String title = trimToLength("[" + entry.severity.getLabel() + "] " + entry.loggerName, EMBED_TITLE_MAX);
        boolean titleShowsLevel = title.contains("[" + entry.severity.getLabel() + "]");
        boolean titleShowsLogger = title.contains(entry.loggerName);

        List<Map<String, String>> fields = new ArrayList<>();
        if (!titleShowsLogger) {
            fields.add(embedField("Logger", trimToLength(entry.loggerName, 400)));
        }
        if (!titleShowsLevel) {
            fields.add(embedField("Level", entry.severity.getLabel()));
        }
        fields.add(embedField("Time", formatTime(entry.timeMs)));
        if (!entry.exceptionSummary.isBlank()) {
            fields.add(embedField("Exception", trimToLength(entry.exceptionSummary, 800)));
        }
        if (fields.size() > MAX_EMBED_FIELDS) {
            fields = fields.subList(0, MAX_EMBED_FIELDS);
        }

        int fixedChars = title.length();
        for (Map<String, String> field : fields) {
            fixedChars += field.get("name").length();
            fixedChars += field.get("value").length();
        }

        String body = entry.body.isBlank() ? "(no message body)" : entry.body;
        int availableForDescription = Math.max(0, EMBED_TOTAL_TEXT_MAX - fixedChars);
        int descriptionLimit = Math.min(EMBED_DESCRIPTION_MAX, availableForDescription);
        LimitedText splitDescription = splitForLimit(body, descriptionLimit);
        String description = splitDescription.shown;
        String messageOverflow = splitDescription.remainder;

        String stackTrace = entry.stackTrace;
        boolean overflowPresent = !messageOverflow.isBlank() || !stackTrace.isBlank();
        String footerText = "";
        if (overflowPresent) {
            footerText = trimToLength("Additional alert details will be sent as follow-up text or a .txt attachment.", EMBED_FOOTER_MAX);
            int textUsed = fixedChars + description.length() + footerText.length();
            if (textUsed > EMBED_TOTAL_TEXT_MAX) {
                int reduceBy = textUsed - EMBED_TOTAL_TEXT_MAX;
                LimitedText tightenedDescription = splitForLimit(description, Math.max(0, description.length() - reduceBy));
                description = tightenedDescription.shown;
                String tightenedOverflow = tightenedDescription.remainder;
                messageOverflow = tightenedOverflow.isBlank() ? messageOverflow : tightenedOverflow + "\n" + messageOverflow;
            }
        }

        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", title);
        embed.put("description", description);
        embed.put("color", entry.severity.getEmbedColor());
        embed.put("timestamp", Instant.ofEpochMilli(entry.timeMs).toString());
        if (!fields.isEmpty()) {
            embed.put("fields", fields);
        }
        if (!footerText.isBlank()) {
            embed.put("footer", Map.of("text", footerText));
        }

        return new EmbedBuildResult(embed, new OverflowSections(messageOverflow, stackTrace));
    }

    private MultipartAttachment chooseOverflowAttachment(AlertMessage entry, OverflowSections overflow) {
        if (overflow.isEmpty()) return null;

        String attachmentText = overflow.asAttachmentText(entry);
        byte[] bytes = attachmentText.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ATTACHMENT_BYTES) return null;

        boolean preferAttachment = bytes.length > ATTACHMENT_PREFERRED_OVERFLOW_THRESHOLD
                || countChunkRequests(overflow, false) > 2
                || !overflow.messageOverflow.isBlank();
        if (!preferAttachment) return null;

        return new MultipartAttachment(
                buildAttachmentFilename(entry),
                bytes,
                "Full alert details"
        );
    }

    private List<PreparedWebhookRequest> buildOverflowFollowUpRequests(AlertMessage entry, OverflowSections overflow, boolean plainOnly) throws JsonProcessingException {
        if (overflow.isEmpty()) return Collections.emptyList();

        List<PreparedWebhookRequest> requests = new ArrayList<>();
        if (!overflow.messageOverflow.isBlank()) {
            List<String> chunks = chunkPlainText("Message overflow:\n" + overflow.messageOverflow, MESSAGE_TEXT_CHUNK_MAX);
            for (String chunk : chunks) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("content", chunk);
                payload.put("allowed_mentions", Map.of("parse", List.of()));
                requests.add(buildJsonRequest(payload, "overflow message chunk"));
            }
        }

        if (!overflow.stackTrace.isBlank()) {
            List<String> chunks = plainOnly
                    ? chunkPlainText("Stack trace:\n" + overflow.stackTrace, MESSAGE_TEXT_CHUNK_MAX)
                    : chunkCodeBlockText(overflow.stackTrace, MESSAGE_CONTENT_MAX);
            for (String chunk : chunks) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("content", chunk);
                payload.put("allowed_mentions", Map.of("parse", List.of()));
                requests.add(buildJsonRequest(payload, "overflow stack trace chunk"));
            }
        }
        return requests;
    }

    private PreparedWebhookRequest buildJsonRequest(Map<String, Object> payload, String logSummary) throws JsonProcessingException {
        String json = JSON.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(webhookUri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return new PreparedWebhookRequest(request, logSummary);
    }

    private PreparedWebhookRequest buildMultipartRequest(Map<String, Object> payload, MultipartAttachment attachment, String logSummary) throws JsonProcessingException {
        String boundary = "ShamPluginBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writeMultipartPart(output, boundary, "payload_json", null, "application/json; charset=UTF-8",
                JSON.writeValueAsBytes(payload));
        writeMultipartPart(output, boundary, "files[0]", attachment.filename, "text/plain; charset=UTF-8",
                attachment.bytes);
        writeString(output, "--" + boundary + "--\r\n");

        HttpRequest request = HttpRequest.newBuilder(webhookUri)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray()))
                .build();
        return new PreparedWebhookRequest(request, logSummary);
    }

    private static void writeMultipartPart(ByteArrayOutputStream output, String boundary, String fieldName, String filename, String contentType, byte[] bytes) {
        writeString(output, "--" + boundary + "\r\n");
        String disposition = "Content-Disposition: form-data; name=\"" + fieldName + "\"";
        if (filename != null) {
            disposition += "; filename=\"" + filename + "\"";
        }
        writeString(output, disposition + "\r\n");
        if (contentType != null && !contentType.isBlank()) {
            writeString(output, "Content-Type: " + contentType + "\r\n");
        }
        writeString(output, "\r\n");
        output.writeBytes(bytes);
        writeString(output, "\r\n");
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private void enqueueMessage(AlertMessage message) {
        if (message == null || closed) return;

        int currentSize = sendQueue.size();
        if (currentSize >= MAX_QUEUE_SIZE) {
            int toDrop = Math.max(1, MAX_QUEUE_SIZE / 10);
            for (int i = 0; i < toDrop; i++) {
                AlertMessage dropped = sendQueue.poll();
                if (dropped == null) break;
                droppedDueToFullQueue.incrementAndGet();
            }
            LOGGER.warning("[Alerts] Queue reached max size. Dropped " + droppedDueToFullQueue.get() + " oldest messages so far.");
        }

        sendQueue.add(message);
    }

    private boolean shouldPing(Severity severity) {
        return pingLevel != null && pingLevel.includes(severity) && !pingText.isBlank();
    }

    private boolean isSelfRecord(LogRecord record) {
        String loggerName = record.getLoggerName();
        if (SELF_LOGGER_NAME.equals(loggerName)) return true;
        String sourceClass = record.getSourceClassName();
        return SELF_LOGGER_NAME.equals(sourceClass);
    }

    private String safeFormatBodyRaw(LogRecord record) {
        try {
            return formatMessageBodyRaw(record);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String formatAlertText(Severity severity, String loggerName, String body, long timeMs, Throwable thrown, boolean sanitizeException) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(severity.getLabel()).append(']').append(' ');
        sb.append('(').append(loggerName != null ? loggerName : "root").append(')');
        if (body != null && !body.isEmpty()) {
            sb.append(' ').append(body);
        }
        sb.append('\n').append("Time: ").append(formatTime(timeMs)).append(" UTC");

        if (thrown != null) {
            String summary = buildExceptionSummary(thrown);
            String trace = getStackTraceString(thrown);
            if (sanitizeException) {
                summary = sanitizeForDiscord(summary);
                trace = sanitizeForDiscord(trace);
            }
            if (!summary.isBlank()) {
                sb.append('\n').append("Exception: ").append(summary);
            }
            if (!trace.isBlank()) {
                sb.append('\n').append("Stack Trace:").append('\n').append(trace);
            }
        }
        return sb.toString();
    }

    private String formatMessageBodyRaw(LogRecord record) {
        String message = record.getMessage();
        Object[] params = record.getParameters();

        if (message != null && params != null && params.length > 0) {
            try {
                message = MessageFormat.format(message, params);
            } catch (IllegalArgumentException ignored) {
                try {
                    message = String.format(message, params);
                } catch (Exception ignoredAgain) {
                }
            }
        }
        return message == null ? "" : message;
    }

    private String buildExceptionSummary(Throwable throwable) {
        if (throwable == null) return "";
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getName();
        }
        return throwable.getClass().getName() + ": " + message;
    }

    private String getStackTraceString(Throwable throwable) {
        if (throwable == null) return "";
        try {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            return sw.toString();
        } catch (Exception e) {
            try {
                LOGGER.log(Level.WARNING, "[Alerts] Failed to extract stack trace: " + e.getMessage(), e);
            } catch (Throwable ignored) {
            }
            return "";
        }
    }

    private boolean shouldIgnoreStrings(String formattedRaw, String formattedSan, String bodyRaw, String bodySan, Throwable thrown) {
        if (ignoreList == null || ignoreList.isEmpty()) return false;

        String fRaw = formattedRaw == null ? "" : formattedRaw;
        String bRaw = bodyRaw == null ? "" : bodyRaw;
        String fSan = sanitizeForDiscord(formattedSan == null ? fRaw : formattedSan);
        String bSan = sanitizeForDiscord(bodySan == null ? bRaw : bodySan);

        String thrownMsgRaw = (thrown == null || thrown.getMessage() == null) ? "" : thrown.getMessage();
        String stackRaw = thrown == null ? "" : getStackTraceString(thrown);
        String thrownMsgSan = sanitizeForDiscord(thrownMsgRaw);
        String stackSan = sanitizeForDiscord(stackRaw);

        String fRawN = normalizeForContains(fRaw);
        String bRawN = normalizeForContains(bRaw);
        String fSanN = normalizeForContains(fSan);
        String bSanN = normalizeForContains(bSan);
        String thrownRawN = normalizeForContains(thrownMsgRaw);
        String stackRawN = normalizeForContains(stackRaw);
        String thrownSanN = normalizeForContains(thrownMsgSan);
        String stackSanN = normalizeForContains(stackSan);

        for (String rawPattern : ignoreList) {
            if (rawPattern == null) continue;
            String pattern = rawPattern.trim();
            if (pattern.isEmpty()) continue;

            if (fRaw.contains(pattern) || bRaw.contains(pattern) || thrownMsgRaw.contains(pattern) || stackRaw.contains(pattern)) return true;
            if (fSan.contains(pattern) || bSan.contains(pattern) || thrownMsgSan.contains(pattern) || stackSan.contains(pattern)) return true;

            String normalizedPattern = normalizeForContains(pattern);
            if (normalizedPattern.isEmpty()) continue;

            if (fRawN.contains(normalizedPattern) || bRawN.contains(normalizedPattern)
                    || thrownRawN.contains(normalizedPattern) || stackRawN.contains(normalizedPattern)) return true;
            if (fSanN.contains(normalizedPattern) || bSanN.contains(normalizedPattern)
                    || thrownSanN.contains(normalizedPattern) || stackSanN.contains(normalizedPattern)) return true;
        }
        return false;
    }

    private String normalizeForContains(String input) {
        if (input == null) return "";
        String cleaned = sanitizeForDiscord(input);
        String lower = cleaned.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean prevWs = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean ws = Character.isWhitespace(c);
            if (ws) {
                if (!prevWs) out.append(' ');
            } else {
                out.append(c);
            }
            prevWs = ws;
        }
        return out.toString().trim();
    }

    private boolean shouldDropDuplicate(String fingerprint, long timeMs) {
        if (fingerprint == null || fingerprint.isBlank()) return false;

        AtomicBoolean duplicate = new AtomicBoolean(false);
        recentFingerprints.compute(fingerprint, (key, previous) -> {
            if (previous != null) {
                long delta = Math.abs(timeMs - previous);
                if (delta <= DEDUP_WINDOW_MS) {
                    duplicate.set(true);
                    return Math.min(previous, timeMs);
                }
            }
            return timeMs;
        });

        int ops = fingerprintOps.incrementAndGet();
        if ((ops & 0x7F) == 0) {
            cleanupDedupCache(timeMs);
        }
        if (recentFingerprints.size() > DEDUP_MAX_SIZE_HARD) {
            recentFingerprints.clear();
        }
        return duplicate.get();
    }

    private void cleanupDedupCache(long nowMs) {
        try {
            if (recentFingerprints.isEmpty()) return;
            for (var entry : recentFingerprints.entrySet()) {
                Long ts = entry.getValue();
                if (ts == null) continue;
                if (nowMs - ts > DEDUP_RETENTION_MS) {
                    recentFingerprints.remove(entry.getKey(), ts);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private String buildFingerprint(Severity severity, String loggerName, String body, Throwable thrown) {
        String sSeverity = normalizeForContainsCapped(severity == null ? "warn" : severity.getLabel(), 16);
        String sLogger = normalizeForContainsCapped(loggerName == null ? "root" : loggerName, 128);
        String sBody = normalizeForContainsCapped(body, FP_MAX_FIELD_LEN);

        String throwableClass = "";
        String throwableMsg = "";
        if (thrown != null) {
            try {
                throwableClass = thrown.getClass().getName();
            } catch (Throwable ignored) {
            }
            try {
                throwableMsg = thrown.getMessage();
            } catch (Throwable ignored) {
            }
        }

        String sThrowableClass = normalizeForContainsCapped(throwableClass, 256);
        String sThrowableMsg = normalizeForContainsCapped(throwableMsg, FP_MAX_FIELD_LEN);
        return sSeverity + "|" + sLogger + "|" + sBody + "|" + sThrowableClass + "|" + sThrowableMsg;
    }

    private String normalizeForContainsCapped(String input, int capLen) {
        if (input == null || capLen <= 0) return "";
        String cleaned = sanitizeForDiscord(input).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(Math.min(cleaned.length(), capLen));
        boolean prevWs = false;
        for (int i = 0; i < cleaned.length() && out.length() < capLen; i++) {
            char c = cleaned.charAt(i);
            boolean ws = Character.isWhitespace(c);
            if (ws) {
                if (!prevWs) out.append(' ');
            } else {
                out.append(c);
            }
            prevWs = ws;
        }
        return out.toString().trim();
    }

    private static String sanitizeForDiscord(String input) {
        if (input == null || input.isEmpty()) return "";
        String noAnsi = ANSI_ESCAPE.matcher(input).replaceAll("");
        StringBuilder out = new StringBuilder(noAnsi.length());
        for (int i = 0; i < noAnsi.length(); i++) {
            char c = noAnsi.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || (c >= 0x20 && c != 0x7F)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private Map<String, Object> buildAllowedMentions(String content) {
        List<String> parse = new ArrayList<>();
        if (content != null && !content.isBlank()) {
            if (content.contains("@everyone") || content.contains("@here")) {
                parse.add("everyone");
            }
            Matcher userMatcher = USER_MENTION.matcher(content);
            if (userMatcher.find()) {
                parse.add("users");
            }
            Matcher roleMatcher = ROLE_MENTION.matcher(content);
            if (roleMatcher.find()) {
                parse.add("roles");
            }
        }
        Map<String, Object> allowed = new LinkedHashMap<>();
        allowed.put("parse", parse);
        return allowed;
    }

    private static Map<String, String> embedField(String name, String value) {
        Map<String, String> field = new LinkedHashMap<>();
        field.put("name", trimToLength(name, EMBED_FIELD_NAME_MAX));
        field.put("value", trimToLength(value == null || value.isBlank() ? "-" : value, EMBED_FIELD_VALUE_MAX));
        return field;
    }

    private String buildPlainSummary(AlertMessage entry, boolean includeStackTrace) {
        StringBuilder out = new StringBuilder();
        out.append('[').append(entry.severity.getLabel()).append(']').append(' ');
        out.append('(').append(entry.loggerName).append(')');
        if (!entry.body.isBlank()) {
            out.append(' ').append(entry.body);
        }
        out.append('\n').append("Time: ").append(formatTime(entry.timeMs)).append(" UTC");
        if (!entry.exceptionSummary.isBlank()) {
            out.append('\n').append("Exception: ").append(entry.exceptionSummary);
        }
        if (includeStackTrace && !entry.stackTrace.isBlank()) {
            out.append('\n').append("Stack Trace:").append('\n').append(entry.stackTrace);
        }
        return out.toString();
    }

    private static String formatTime(long timeMs) {
        return HUMAN_TIME_FORMAT.format(Instant.ofEpochMilli(timeMs > 0L ? timeMs : System.currentTimeMillis()));
    }

    private void maybeLogSendFailure(String message) {
        long now = System.currentTimeMillis();
        if (now - lastSendFailWarnMs > SEND_FAIL_WARN_COOLDOWN_MS) {
            lastSendFailWarnMs = now;
            try {
                LOGGER.warning("[Alerts] " + message);
            } catch (Throwable ignored) {
            }
        } else {
            try {
                LOGGER.fine("[Alerts] " + message);
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyBackoff(long retryAfterMs) {
        if (retryAfterMs > 0L) {
            long until = System.currentTimeMillis() + retryAfterMs;
            if (until > networkBackoffUntilMs) {
                networkBackoffUntilMs = until;
            }
            return;
        }

        long now = System.currentTimeMillis();
        long proposedUntil = now + networkBackoffMs;
        if (proposedUntil > networkBackoffUntilMs) {
            networkBackoffUntilMs = proposedUntil;
        }
        networkBackoffMs = Math.min(networkBackoffMs * 2L, NETWORK_BACKOFF_MAX_MS);
    }

    private void clearNetworkBackoff() {
        networkBackoffUntilMs = 0L;
        networkBackoffMs = NETWORK_BACKOFF_BASE_MS;
    }

    private boolean isNetworkException(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current instanceof UnknownHostException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof SocketException
                    || current instanceof UnresolvedAddressException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private RateLimitInfo readRateLimitInfo(HttpHeaders headers, String body) {
        double seconds = 0.0;
        String resetAfter = headers.firstValue("X-RateLimit-Reset-After").orElse("");
        if (!resetAfter.isBlank()) {
            try {
                seconds = Double.parseDouble(resetAfter.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (seconds <= 0.0) {
            String retryAfterHeader = headers.firstValue("Retry-After").orElse("");
            if (!retryAfterHeader.isBlank()) {
                try {
                    seconds = Double.parseDouble(retryAfterHeader.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (seconds <= 0.0 && body != null && !body.isBlank()) {
            try {
                Object parsed = JSON.readValue(body, Object.class);
                if (parsed instanceof Map<?, ?> map) {
                    Object retryAfter = map.get("retry_after");
                    if (retryAfter instanceof Number number) {
                        seconds = number.doubleValue();
                    } else if (retryAfter instanceof String text) {
                        seconds = Double.parseDouble(text);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        long ms = Math.max(1000L, (long) Math.ceil(seconds * 1000.0));
        return new RateLimitInfo(ms);
    }

    private int countChunkRequests(OverflowSections overflow, boolean plainOnly) {
        int count = 0;
        if (!overflow.messageOverflow.isBlank()) {
            count += chunkPlainText("Message overflow:\n" + overflow.messageOverflow, MESSAGE_TEXT_CHUNK_MAX).size();
        }
        if (!overflow.stackTrace.isBlank()) {
            count += plainOnly
                    ? chunkPlainText("Stack trace:\n" + overflow.stackTrace, MESSAGE_TEXT_CHUNK_MAX).size()
                    : chunkCodeBlockText(overflow.stackTrace, MESSAGE_CONTENT_MAX).size();
        }
        return count;
    }

    private static List<String> chunkPlainText(String text, int maxLen) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        List<String> chunks = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(text.length(), index + maxLen);
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > index + 200) {
                    end = newline;
                }
            }
            String chunk = text.substring(index, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            index = end;
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }
        return chunks;
    }

    private static List<String> chunkCodeBlockText(String text, int maxLen) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        int payloadMax = Math.max(1, maxLen - 8);
        List<String> rawChunks = chunkPlainText(text, payloadMax);
        List<String> codeChunks = new ArrayList<>(rawChunks.size());
        boolean first = true;
        for (String raw : rawChunks) {
            String chunk = "```" + raw + "```";
            if (first) {
                String labeled = "Stack trace:\n" + chunk;
                if (labeled.length() <= maxLen) {
                    chunk = labeled;
                }
                first = false;
            }
            codeChunks.add(chunk);
        }
        return codeChunks;
    }

    private static String trimToLength(String input, int maxLen) {
        if (input == null) return "";
        if (maxLen <= 0) return "";
        String trimmed = input.trim();
        if (trimmed.length() <= maxLen) return trimmed;
        if (maxLen <= 3) return trimmed.substring(0, maxLen);
        return trimmed.substring(0, maxLen - 3).trim() + "...";
    }

    private static LimitedText splitForLimit(String input, int maxLen) {
        if (input == null) {
            return new LimitedText("", "");
        }
        String normalized = input.trim();
        if (maxLen <= 0) {
            return new LimitedText("", normalized);
        }
        if (normalized.length() <= maxLen) {
            return new LimitedText(normalized, "");
        }

        int rawLimit = maxLen <= 3 ? maxLen : maxLen - 3;
        rawLimit = Math.max(0, rawLimit);
        int splitIndex = Math.min(normalized.length(), rawLimit);

        String head = normalized.substring(0, splitIndex).trim();
        String tail = normalized.substring(splitIndex).trim();

        String shown;
        if (maxLen <= 3) {
            shown = normalized.substring(0, Math.min(normalized.length(), maxLen));
        } else {
            shown = head + "...";
        }
        return new LimitedText(shown, tail);
    }

    private String summarizeForLog(String text) {
        if (text == null) return "";
        int max = 200;
        return text.length() <= max ? text : text.substring(0, max) + "...(truncated)";
    }

    private String buildAttachmentFilename(AlertMessage entry) {
        String stamp = formatTime(entry.timeMs).replace(" | ", "_").replace(':', '-').replace('.', '-').replace(' ', '_');
        return "alert-" + entry.severity.getConfigValue() + "-" + stamp + ".txt";
    }

    private static URI buildWebhookUri(String webhookUrl) {
        String trimmed = webhookUrl.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Webhook URL cannot be empty.");
        }
        String withWait;
        if (WAIT_QUERY.matcher(trimmed).find()) {
            withWait = trimmed;
        } else {
            withWait = trimmed + (trimmed.contains("?") ? "&" : "?") + "wait=true";
        }
        return URI.create(withWait);
    }

    private List<String> tryLoadIgnoreListFromDisk() {
        try {
            Path pluginsDir = Paths.get("plugins");
            if (!Files.isDirectory(pluginsDir)) return Collections.emptyList();

            try (var stream = Files.walk(pluginsDir, 2)) {
                for (Path path : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(path)) continue;
                    if (!path.getFileName().toString().equalsIgnoreCase("config.yml")) continue;

                    String text;
                    try {
                        text = Files.readString(path, StandardCharsets.UTF_8);
                    } catch (Throwable ignored) {
                        continue;
                    }

                    String lower = text.toLowerCase(Locale.ROOT);
                    if (!(lower.contains("discord alerts:") || lower.contains("discord:") || lower.contains("alerts:"))) continue;

                    List<String> direct = extractYamlStringList(text, "discord alerts", "ignore");
                    if (!direct.isEmpty()) return direct;

                    List<String> legacyDiscord = extractYamlStringList(text, "discord", "ignore");
                    if (!legacyDiscord.isEmpty()) return legacyDiscord;

                    List<String> legacyAlerts = extractYamlStringList(text, "alerts", "ignore");
                    if (!legacyAlerts.isEmpty()) return legacyAlerts;
                }
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptyList();
    }

    private List<String> extractYamlStringList(String yaml, String parentKey, String childKey) {
        if (yaml == null || parentKey == null || childKey == null) return Collections.emptyList();

        String[] lines = yaml.split("\r?\n");
        int parentIndent = -1;
        int childIndent = -1;
        boolean inParent = false;
        boolean inChild = false;

        List<String> out = new ArrayList<>();

        for (String line : lines) {
            if (line == null) continue;

            int hash = line.indexOf('#');
            String effective = hash >= 0 ? line.substring(0, hash) : line;
            if (effective.trim().isEmpty()) continue;

            int indent = countIndent(effective);
            String trimmed = effective.trim();

            if (!inParent) {
                if (isYamlKey(trimmed, parentKey)) {
                    inParent = true;
                    parentIndent = indent;
                }
                continue;
            } else if (indent <= parentIndent && !isYamlKey(trimmed, parentKey)) {
                break;
            }

            if (!inChild) {
                if (indent > parentIndent && isYamlKey(trimmed, childKey)) {
                    inChild = true;
                    childIndent = indent;

                    int colon = trimmed.indexOf(':');
                    if (colon >= 0) {
                        String after = trimmed.substring(colon + 1).trim();
                        if (after.startsWith("[") && after.endsWith("]")) {
                            List<String> inline = parseInlineYamlList(after);
                            if (!inline.isEmpty()) out.addAll(inline);
                            return out;
                        }
                    }
                }
                continue;
            } else if (indent <= childIndent && !trimmed.startsWith("-")) {
                break;
            }

            if (indent > childIndent && trimmed.startsWith("-")) {
                String item = stripQuotes(trimmed.substring(1).trim());
                if (!item.isEmpty()) out.add(item);
            }
        }
        return out;
    }

    private int countIndent(String line) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == ' ') {
                i++;
            } else if (c == '\t') {
                i += 2;
            } else {
                break;
            }
        }
        return i;
    }

    private boolean isYamlKey(String trimmed, String key) {
        if (trimmed == null || key == null) return false;
        String normalized = key.trim();
        if (normalized.isEmpty()) return false;
        return trimmed.equals(normalized + ":") || trimmed.startsWith(normalized + ":");
    }

    private String stripQuotes(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            if (trimmed.length() >= 2) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private List<String> parseInlineYamlList(String bracketed) {
        if (bracketed == null) return Collections.emptyList();
        String trimmed = bracketed.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return Collections.emptyList();
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return Collections.emptyList();

        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quote = 0;

        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (!inQuotes && (c == '"' || c == '\'')) {
                inQuotes = true;
                quote = c;
                current.append(c);
                continue;
            }
            if (inQuotes && c == quote) {
                inQuotes = false;
                current.append(c);
                continue;
            }
            if (!inQuotes && c == ',') {
                String item = stripQuotes(current.toString());
                if (!item.isEmpty()) out.add(item);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }

        String last = stripQuotes(current.toString());
        if (!last.isEmpty()) out.add(last);
        return out;
    }
}