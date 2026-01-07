package win.kakchuserver;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
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

// Log4j2 (server-provided on Paper; add as PROVIDED in pom if your IDE can’t resolve)
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

public class Alerts extends Handler {
    private static final Logger LOGGER = Logger.getLogger(Alerts.class.getName());

    /**
     * Prevent recursive alert loops:
     * This handler logs WARNINGs when Discord sending fails. Without guarding,
     * those WARNING logs are handled by this same handler, which attempts to send them to Discord,
     * fails again, and loops.
     */
    private static final String SELF_LOGGER_NAME = Alerts.class.getName();
    private static final ThreadLocal<Boolean> IN_PUBLISH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // Configurable behavior
    private static final int DISCORD_MAX_LEN = 1999;                // Discord message size limit
    private static final long SENDER_INTERVAL_MS = 1000L;           // how often the sender runs
    private static final int SENDER_BATCH_SIZE = 3;                 // how many messages to attempt per interval
    private static final int MAX_QUEUE_SIZE = 5000;                 // hard cap for queued messages
    private static final long CHANNEL_CACHE_TTL_MS = 30_000L;       // how long to cache resolved channel
    private static final long CHANNEL_MISSING_WARN_COOLDOWN_MS = 300_000L; // cooldown for local warning when channel missing
    private static final int MAX_SEND_ATTEMPTS = 5;                 // how many times to retry a failed send (non-network failures)

    // Backoff during network outages (e.g., Ethernet down)
    private static final long NETWORK_BACKOFF_BASE_MS = 15_000L;
    private static final long NETWORK_BACKOFF_MAX_MS = 120_000L;

    // Rate-limit local send-failure warnings (to avoid console spam)
    private static final long SEND_FAIL_WARN_COOLDOWN_MS = 15_000L;

    private final String token;
    private final String channelId;
    private final String pingType; // e.g. "@everyone", "@here", "<@123...>", "<@&roleId>"

    /**
     * Patterns of substrings which, if present in a formatted log message,
     * will cause the alert to be silently ignored.
     */
    private final List<String> ignoreList;

    // Internal JDA reference (set once ready)
    private volatile JDA jda;

    // Queue of messages to send. We wrap messages in AlertMessage to track retries.
    private final Queue<AlertMessage> sendQueue = new ConcurrentLinkedQueue<>();

    // Executor that will process the queue on a dedicated thread (never main thread).
    private final ScheduledExecutorService senderExecutor;

    // Channel caching
    private volatile TextChannel cachedChannel = null;
    private volatile long cachedChannelExpiry = 0L;

    // Rate-limited local logging when channel missing (to avoid spamming console)
    private final AtomicInteger droppedDueToFullQueue = new AtomicInteger(0);
    private volatile long lastChannelMissingWarn = 0L;

    // Network backoff state
    private volatile long networkBackoffUntilMs = 0L;
    private volatile long networkBackoffMs = NETWORK_BACKOFF_BASE_MS;
    private volatile long lastSendFailWarnMs = 0L;

    // track init thread so we can interrupt it during shutdown
    private volatile Thread initThread = null;

    // closed flag to stop accepting new messages after close() invoked
    private volatile boolean closed = false;

    // ---- Log4j2 capture (to catch console WARN/ERROR not emitted via JUL) ----
    private volatile boolean log4jInstalled = false;
    private volatile LoggerContext log4jCtx = null;
    private volatile String log4jAppenderName = null;

    // message wrapper
    private static final class AlertMessage {
        final String content;
        int attempts;

        AlertMessage(String content) {
            this.content = content;
            this.attempts = 0;
        }
    }

    public Alerts(String token, String channelId, String pingType, List<String> ignoreList) {
        this.token = Objects.requireNonNull(token, "token");
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.pingType = (pingType == null || pingType.trim().isEmpty()) ? "@everyone" : pingType.trim();

        // Ensure handler itself is willing to receive everything; we filter manually.
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
            for (String s : effectiveIgnore) {
                if (s == null) continue;
                String t = s.trim();
                if (!t.isEmpty()) cleaned.add(t);
            }
            this.ignoreList = cleaned.isEmpty() ? Collections.emptyList() : cleaned;
        }

        senderExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Alerts-Sender-Thread");
            t.setDaemon(true);
            return t;
        });

        senderExecutor.scheduleAtFixedRate(this::processQueueSafely, 0L, SENDER_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Capture Log4j2 WARN/ERROR as well
        installLog4jAppender();

        // initialize JDA in background
        initJdaAsync();
    }

    private void initJdaAsync() {
        Thread t = new Thread(() -> {
            try {
                JDA local = JDABuilder.createDefault(token).build();
                local.awaitReady();
                jda = local;
                LOGGER.info("[Alerts] Discord initialized successfully.");

                cachedChannel = null;
                cachedChannelExpiry = 0L;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.SEVERE, "[Alerts] Discord init interrupted.", ie);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[Alerts] Failed to initialize Discord: " + e.getMessage(), e);
            } finally {
                initThread = null;
            }
        }, "Discord-Init-Thread");
        t.setDaemon(true);
        initThread = t;
        t.start();
    }

    // ---------------- JUL capture ----------------

    @Override
    public void publish(LogRecord record) {
        if (closed) return;
        if (record == null) return;

        if (!isLoggable(record)) return;

        if (isSelfRecord(record)) return;
        if (Boolean.TRUE.equals(IN_PUBLISH.get())) return;

        IN_PUBLISH.set(Boolean.TRUE);
        try {
            if (record.getLevel().intValue() < Level.WARNING.intValue()) return;

            String formatted = formatRecord(record);
            String body = safeFormatBody(record);
            Throwable thrown = record.getThrown();

            if (shouldIgnoreStrings(formatted, body, thrown)) return;

            enqueueMessage(formatted);
        } catch (Throwable t) {
            try {
                LOGGER.log(Level.SEVERE, "[Alerts] publish() failed: " + t.getMessage(), t);
            } catch (Throwable ignored) {
            }
        } finally {
            IN_PUBLISH.set(Boolean.FALSE);
        }
    }

    private boolean isSelfRecord(LogRecord record) {
        String ln = record.getLoggerName();
        if (SELF_LOGGER_NAME.equals(ln)) return true;
        String sc = record.getSourceClassName();
        return SELF_LOGGER_NAME.equals(sc);
    }

    private String safeFormatBody(LogRecord record) {
        try {
            return formatMessageBody(record);
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ---------------- Log4j2 capture ----------------

    private static final class DiscordLog4jAppender extends AbstractAppender {
        private final Alerts alerts;

        private DiscordLog4jAppender(String name, Alerts alerts) {
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

    private void installLog4jAppender() {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();

            String name = "KakchuDiscordAlerts-" + System.identityHashCode(this);
            DiscordLog4jAppender app = new DiscordLog4jAppender(name, this);
            app.start();

            config.addAppender(app);

            // Always attach to root
            config.getRootLogger().addAppender(app, org.apache.logging.log4j.Level.WARN, null);

            // Attach to non-additive logger configs (they would not bubble to root)
            for (LoggerConfig lc : config.getLoggers().values()) {
                if (!lc.isAdditive() && !lc.getAppenders().containsKey(name)) {
                    lc.addAppender(app, org.apache.logging.log4j.Level.WARN, null);
                }
            }

            ctx.updateLoggers();

            log4jCtx = ctx;
            log4jAppenderName = name;
            log4jInstalled = true;

            LOGGER.info("[Alerts] Log4j2 capture enabled (WARN+).");
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

    private void onLog4jEvent(LogEvent event) {
        if (closed) return;
        if (event == null) return;

        if (Boolean.TRUE.equals(IN_PUBLISH.get())) return;

        org.apache.logging.log4j.Level lvl = event.getLevel();
        if (lvl == null || !lvl.isMoreSpecificThan(org.apache.logging.log4j.Level.WARN)) return;

        String loggerName = event.getLoggerName();
        if (SELF_LOGGER_NAME.equals(loggerName)) return;

        IN_PUBLISH.set(Boolean.TRUE);
        try {
            String body = "";
            try {
                if (event.getMessage() != null) body = event.getMessage().getFormattedMessage();
            } catch (Throwable ignored) {
            }

            Throwable thrown = null;
            try {
                thrown = event.getThrown();
            } catch (Throwable ignored) {
            }

            String formatted = formatLog4jEvent(event, body, thrown);
            if (shouldIgnoreStrings(formatted, body, thrown)) return;

            enqueueMessage(formatted);
        } catch (Throwable t) {
            try {
                LOGGER.log(Level.FINE, "[Alerts] onLog4jEvent() failed: " + t.getMessage(), t);
            } catch (Throwable ignored) {
            }
        } finally {
            IN_PUBLISH.set(Boolean.FALSE);
        }
    }

    private String formatLog4jEvent(LogEvent event, String body, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ ").append(pingType).append(" [").append(event.getLevel() != null ? event.getLevel().name() : "WARN").append("]");
        sb.append(" (").append(event.getLoggerName() != null ? event.getLoggerName() : "root").append(")");
        if (body != null && !body.isEmpty()) sb.append(" ").append(body);

        long ms = 0L;
        try { ms = event.getTimeMillis(); } catch (Throwable ignored) {}
        sb.append("\n").append("Time: ").append(Instant.ofEpochMilli(ms).toString());

        if (t != null) {
            sb.append("\nException: ").append(t.getClass().getName()).append(": ").append(t.getMessage());
            String trace = getStackTraceString(t);
            if (!trace.isEmpty()) {
                sb.append("\n```").append(trace).append("```");
            }
        }
        return sb.toString();
    }

    // ---------------- Queue + sending ----------------

    private void enqueueMessage(String formatted) {
        if (formatted == null) return;
        if (closed) return;

        int currentSize = sendQueue.size();
        if (currentSize >= MAX_QUEUE_SIZE) {
            int toDrop = Math.max(1, MAX_QUEUE_SIZE / 10);
            for (int i = 0; i < toDrop; i++) {
                AlertMessage polled = sendQueue.poll();
                if (polled == null) break;
                droppedDueToFullQueue.incrementAndGet();
            }
            LOGGER.warning("[Alerts] Queue reached max size. Dropped " + droppedDueToFullQueue.get() + " oldest messages so far.");
        }

        sendQueue.add(new AlertMessage(formatted));
    }

    private void processQueueSafely() {
        try {
            processQueue();
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "[Alerts] Sender thread error: " + t.getMessage(), t);
        }
    }

    private void processQueue() {
        if (sendQueue.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now < networkBackoffUntilMs) return;

        JDA localJda = jda;
        if (localJda == null) return;

        TextChannel channel = resolveChannel(localJda);
        if (channel == null) {
            if (now - lastChannelMissingWarn > CHANNEL_MISSING_WARN_COOLDOWN_MS) {
                lastChannelMissingWarn = now;
                LOGGER.warning("[Alerts] Could not find Discord channel with ID: " + channelId + " - will retry later.");
            }
            return;
        }

        for (int i = 0; i < SENDER_BATCH_SIZE; i++) {
            AlertMessage entry = sendQueue.poll();
            if (entry == null) break;
            sendMessageParts(channel, entry);
        }
    }

    private TextChannel resolveChannel(JDA localJda) {
        long now = System.currentTimeMillis();
        TextChannel ch = cachedChannel;

        if (ch != null && now < cachedChannelExpiry) {
            return ch;
        }

        try {
            TextChannel resolved = localJda.getTextChannelById(channelId);
            cachedChannel = resolved;
            cachedChannelExpiry = now + CHANNEL_CACHE_TTL_MS;
            return resolved;
        } catch (Throwable t) {
            long last = lastChannelMissingWarn;
            if (System.currentTimeMillis() - last > CHANNEL_MISSING_WARN_COOLDOWN_MS) {
                lastChannelMissingWarn = System.currentTimeMillis();
                LOGGER.log(Level.WARNING, "[Alerts] Exception while resolving channel: " + t.getMessage(), t);
            }
            cachedChannel = null;
            cachedChannelExpiry = 0L;
            return null;
        }
    }

    private void sendMessageParts(TextChannel channel, AlertMessage entry) {
        if (entry == null || entry.content == null) return;

        final String original = entry.content;
        final int failuresSoFar = entry.attempts;

        if (failuresSoFar >= MAX_SEND_ATTEMPTS) {
            LOGGER.warning("[Alerts] Dropping message after " + failuresSoFar + " failed attempts: " + summarizeForLog(original));
            return;
        }

        final AtomicBoolean requeued = new AtomicBoolean(false);
        final AtomicBoolean logged = new AtomicBoolean(false);

        int start = 0;
        while (start < original.length()) {
            int end = Math.min(original.length(), start + DISCORD_MAX_LEN);
            String part = original.substring(start, end);

            channel.sendMessage(part).queue(
                    success -> clearNetworkBackoff(),
                    throwable -> {
                        boolean networkIssue = isNetworkException(throwable);
                        if (networkIssue) applyNetworkBackoff();

                        maybeLogSendFailure(logged, throwable);

                        if (!closed && requeued.compareAndSet(false, true)) {
                            AlertMessage retry = new AlertMessage(original);
                            retry.attempts = failuresSoFar + (networkIssue ? 0 : 1);

                            if (retry.attempts < MAX_SEND_ATTEMPTS) {
                                sendQueue.add(retry);
                            } else {
                                LOGGER.warning("[Alerts] Message reached max retries and will be dropped: " + summarizeForLog(original));
                            }
                        }
                    }
            );

            start = end;
        }
    }

    private void maybeLogSendFailure(AtomicBoolean logged, Throwable throwable) {
        if (logged != null && !logged.compareAndSet(false, true)) return;

        long now = System.currentTimeMillis();
        if (now - lastSendFailWarnMs > SEND_FAIL_WARN_COOLDOWN_MS) {
            lastSendFailWarnMs = now;
            try {
                LOGGER.log(Level.WARNING, "[Alerts] Failed to send message to Discord: " + (throwable == null ? "unknown" : throwable.getMessage()), throwable);
            } catch (Throwable ignored) {
            }
        } else {
            try {
                LOGGER.log(Level.FINE, "[Alerts] Failed to send message to Discord: " + (throwable == null ? "unknown" : throwable.getMessage()));
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyNetworkBackoff() {
        long now = System.currentTimeMillis();

        long proposedUntil = now + networkBackoffMs;
        if (proposedUntil > networkBackoffUntilMs) {
            networkBackoffUntilMs = proposedUntil;
        }

        long next = networkBackoffMs * 2L;
        networkBackoffMs = Math.min(next, NETWORK_BACKOFF_MAX_MS);

        cachedChannel = null;
        cachedChannelExpiry = 0L;
    }

    private void clearNetworkBackoff() {
        networkBackoffUntilMs = 0L;
        networkBackoffMs = NETWORK_BACKOFF_BASE_MS;
    }

    private boolean isNetworkException(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            if (cur instanceof java.net.UnknownHostException ||
                    cur instanceof java.net.SocketTimeoutException ||
                    cur instanceof java.net.SocketException ||
                    cur instanceof java.nio.channels.UnresolvedAddressException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String summarizeForLog(String s) {
        if (s == null) return "";
        int max = 200;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(truncated)";
    }

    // ---------------- Formatting ----------------

    private String formatRecord(LogRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ ").append(pingType).append(" [").append(record.getLevel()).append("]");
        sb.append(" (").append(record.getLoggerName() != null ? record.getLoggerName() : "root").append(")");
        sb.append(" ").append(formatMessageBody(record));
        sb.append("\n").append("Time: ").append(Instant.ofEpochMilli(record.getMillis()).toString());

        Throwable t = record.getThrown();
        if (t != null) {
            sb.append("\nException: ").append(t.getClass().getName()).append(": ").append(t.getMessage());
            String trace = getStackTraceString(t);
            if (!trace.isEmpty()) {
                sb.append("\n```").append(trace).append("```");
            }
        }
        return sb.toString();
    }

    private String formatMessageBody(LogRecord record) {
        String msg = record.getMessage();
        Object[] params = record.getParameters();

        if (msg != null && params != null && params.length > 0) {
            try {
                msg = MessageFormat.format(msg, params);
            } catch (IllegalArgumentException ignored) {
                try {
                    msg = String.format(msg, params);
                } catch (Exception ignored2) {
                }
            }
        }

        return (msg == null) ? "" : msg;
    }

    private String getStackTraceString(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            return sw.toString();
        } catch (Exception e) {
            try {
                LOGGER.log(Level.WARNING, "[Alerts] Failed to extract stack trace: " + e.getMessage(), e);
            } catch (Throwable ignored) {
            }
            return "";
        }
    }

    // ---------------- Ignore list ----------------

    private boolean shouldIgnoreStrings(String formatted, String body, Throwable thrown) {
        if (ignoreList == null || ignoreList.isEmpty()) return false;

        String thrownMsg = (thrown == null) ? null : thrown.getMessage();
        String stack = null;
        if (thrown != null) {
            try { stack = getStackTraceString(thrown); } catch (Throwable ignored) {}
        }

        for (String rawPattern : ignoreList) {
            if (rawPattern == null) continue;
            String pattern = rawPattern.trim();
            if (pattern.isEmpty()) continue;

            if (formatted != null && formatted.contains(pattern)) return true;
            if (body != null && body.contains(pattern)) return true;
            if (thrownMsg != null && thrownMsg.contains(pattern)) return true;
            if (stack != null && stack.contains(pattern)) return true;

            String pN = normalizeForContains(pattern);
            if (pN.isEmpty()) continue;

            if (formatted != null && normalizeForContains(formatted).contains(pN)) return true;
            if (body != null && normalizeForContains(body).contains(pN)) return true;
            if (thrownMsg != null && normalizeForContains(thrownMsg).contains(pN)) return true;
            if (stack != null && normalizeForContains(stack).contains(pN)) return true;
        }

        return false;
    }

    private String normalizeForContains(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase();
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

    // ---------------- YAML best-effort ignore autoload ----------------

    private List<String> tryLoadIgnoreListFromDisk() {
        try {
            Path pluginsDir = Paths.get("plugins");
            if (!Files.isDirectory(pluginsDir)) return Collections.emptyList();

            try (var stream = Files.walk(pluginsDir, 2)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(p)) continue;
                    if (!p.getFileName().toString().equalsIgnoreCase("config.yml")) continue;

                    String text;
                    try {
                        text = Files.readString(p, StandardCharsets.UTF_8);
                    } catch (Throwable ignored) {
                        continue;
                    }

                    String lower = text.toLowerCase();
                    if (!(lower.contains("alerts:") || lower.contains("discord:"))) continue;

                    List<String> a = extractYamlStringList(text, "alerts", "ignore");
                    if (!a.isEmpty()) return a;

                    List<String> d = extractYamlStringList(text, "discord", "ignore");
                    if (!d.isEmpty()) return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptyList();
    }

    private List<String> extractYamlStringList(String yaml, String parentKey, @SuppressWarnings("SameParameterValue") String childKey) {
        if (yaml == null) return Collections.emptyList();
        if (parentKey == null || childKey == null) return Collections.emptyList();

        String[] lines = yaml.split("\r?\n");
        int parentIndent = -1;
        int childIndent = -1;
        boolean inParent = false;
        boolean inChild = false;

        List<String> out = new ArrayList<>();

        for (String line : lines) {
            if (line == null) continue;

            int hash = line.indexOf('#');
            String effective = (hash >= 0) ? line.substring(0, hash) : line;
            if (effective.trim().isEmpty()) continue;

            int indent = countIndent(effective);
            String trimmed = effective.trim();

            if (!inParent) {
                if (isYamlKey(trimmed, parentKey)) {
                    inParent = true;
                    parentIndent = indent;
                }
                continue;
            } else {
                if (indent <= parentIndent && !isYamlKey(trimmed, parentKey)) {
                    break;
                }
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
            } else {
                if (indent <= childIndent && !trimmed.startsWith("-")) {
                    break;
                }
            }

            if (indent > childIndent && trimmed.startsWith("-")) {
                String item = trimmed.substring(1).trim();
                item = stripQuotes(item);
                if (!item.isEmpty()) out.add(item);
            }
        }

        return out;
    }

    private int countIndent(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ') i++;
            else if (c == '\t') i += 2;
            else break;
        }
        return i;
    }

    private boolean isYamlKey(String trimmed, String key) {
        if (trimmed == null || key == null) return false;
        String k = key.trim();
        if (k.isEmpty()) return false;
        if (trimmed.equals(k + ":")) return true;
        return trimmed.startsWith(k + ":");
    }

    private String stripQuotes(String s) {
        if (s == null) return "";
        String t = s.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            if (t.length() >= 2) return t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private List<String> parseInlineYamlList(String bracketed) {
        if (bracketed == null) return Collections.emptyList();
        String t = bracketed.trim();
        if (!t.startsWith("[") || !t.endsWith("]")) return Collections.emptyList();
        String inner = t.substring(1, t.length() - 1).trim();
        if (inner.isEmpty()) return Collections.emptyList();

        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        char quote = 0;

        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (!inQuotes && (c == '\"' || c == '\'')) {
                inQuotes = true;
                quote = c;
                cur.append(c);
                continue;
            }
            if (inQuotes && c == quote) {
                inQuotes = false;
                cur.append(c);
                continue;
            }
            if (!inQuotes && c == ',') {
                String item = stripQuotes(cur.toString());
                if (!item.isEmpty()) out.add(item);
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        String last = stripQuotes(cur.toString());
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    // ---------------- Handler lifecycle ----------------

    @Override
    public void flush() {
        // nothing to flush
    }

    @Override
    public void close() throws SecurityException {
        if (closed) return;
        closed = true;

        LOGGER.info("[Alerts] Closing Alerts handler...");

        // Uninstall Log4j2 appender
        uninstallLog4jAppender();

        // Stop sender
        if (senderExecutor != null) {
            try {
                senderExecutor.shutdown();
                if (!senderExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    senderExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                try { senderExecutor.shutdownNow(); } catch (Throwable ignored) {}
            } catch (Throwable t) {
                try { senderExecutor.shutdownNow(); } catch (Throwable ignored) {}
            }
        }

        // Interrupt init thread if still initializing
        try {
            Thread t = initThread;
            if (t != null && t.isAlive()) {
                try { t.interrupt(); } catch (Throwable ignored) {}
                try { t.join(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            initThread = null;
        } catch (Throwable ignored) {}

        // Shutdown JDA
        try {
            if (jda != null) {
                try {
                    jda.shutdownNow();
                } catch (Throwable t) {
                    try { jda.shutdown(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
        } finally {
            jda = null;
        }

        try { sendQueue.clear(); } catch (Throwable ignored) {}
        cachedChannel = null;
        cachedChannelExpiry = 0L;

        LOGGER.info("[Alerts] Alerts handler closed.");
    }

    private void uninstallLog4jAppender() {
        if (!log4jInstalled) return;

        try {
            LoggerContext ctx = log4jCtx;
            String name = log4jAppenderName;
            if (ctx == null || name == null) return;

            Configuration cfg = ctx.getConfiguration();

            // Detach from root
            try { cfg.getRootLogger().removeAppender(name); } catch (Throwable ignored) {}

            // Detach from all logger configs (including non-additive ones)
            try {
                for (LoggerConfig lc : cfg.getLoggers().values()) {
                    try { lc.removeAppender(name); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            // Remove from config appender map and stop it
            try {
                Appender app = cfg.getAppender(name);
                try { cfg.getAppenders().remove(name); } catch (Throwable ignored) {}
                if (app != null) {
                    try { app.stop(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            try { ctx.updateLoggers(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        } finally {
            log4jInstalled = false;
            log4jCtx = null;
            log4jAppenderName = null;
        }
    }
}