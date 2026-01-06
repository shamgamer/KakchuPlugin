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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
    private static final long SENDER_INTERVAL_MS = 1000L;          // how often the sender runs
    private static final int SENDER_BATCH_SIZE = 3;                // how many messages to attempt per interval
    private static final int MAX_QUEUE_SIZE = 5000;                // hard cap for queued messages
    private static final long CHANNEL_CACHE_TTL_MS = 30_000L;      // how long to cache resolved channel
    private static final long CHANNEL_MISSING_WARN_COOLDOWN_MS = 60_000L; // cooldown for local warning when channel missing
    private static final int MAX_SEND_ATTEMPTS = 5;                // how many times to retry a failed send (non-network failures)

    // Backoff during network outages (e.g., Ethernet down)
    private static final long NETWORK_BACKOFF_BASE_MS = 5_000L;
    private static final long NETWORK_BACKOFF_MAX_MS = 60_000L;

    // Rate-limit local send-failure warnings (to avoid console spam)
    private static final long SEND_FAIL_WARN_COOLDOWN_MS = 15_000L;

    private final String token;
    private final String channelId;
    private final String pingType; // e.g. "@everyone", "@here", "<@123...>", "<@&roleId>"

    /**
     * Patterns of substrings which, if present in a formatted log message,
     * will cause the alert to be silently ignored. This allows noise from
     * known benign messages to be filtered out on a per-installation basis.
     *
     * Matching is case-sensitive (String.contains()).
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

    // message wrapper
    private static final class AlertMessage {
        final String content;
        int attempts;

        AlertMessage(String content) {
            this.content = content;
            this.attempts = 0;
        }
    }

    /**
     * Backwards-compatible constructor (no ignore list).
     *
     * @param token     Discord bot token
     * @param channelId Channel ID to send alerts to (as a string, quote in YAML to be safe)
     * @param pingType  Optional ping type; if null/empty defaults to "@everyone"
     */
    public Alerts(String token, String channelId, String pingType) {
        this(token, channelId, pingType, null);
    }

    /**
     * New constructor with ignore list support.
     * Any formatted alert message containing any ignore substring will NOT be sent.
     *
     * @param token      Discord bot token
     * @param channelId  Channel ID to send alerts to (as a string, quote in YAML to be safe)
     * @param pingType   Optional ping type; if null/empty defaults to "@everyone"
     * @param ignoreList List of substrings to filter out (case-sensitive); null/empty = no filtering
     */
    public Alerts(String token, String channelId, String pingType, List<String> ignoreList) {
        this.token = Objects.requireNonNull(token, "token");
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.pingType = (pingType == null || pingType.trim().isEmpty()) ? "@everyone" : pingType.trim();

        // Copy ignore patterns defensively (and normalise null to empty list).
        // IMPORTANT: To keep this class usable even if the caller forgets to pass the ignore list,
        // we attempt a best-effort auto-load from plugin config.yml files when the provided list is empty.
        List<String> effectiveIgnore = (ignoreList == null) ? Collections.emptyList() : new ArrayList<>(ignoreList);
        if (effectiveIgnore.isEmpty()) {
            List<String> disk = tryLoadIgnoreListFromDisk();
            if (!disk.isEmpty()) {
                effectiveIgnore = disk;
                LOGGER.info("[Alerts] Loaded " + effectiveIgnore.size() + " ignore pattern(s) from config.yml on disk.");
            }
        }
        // Trim and drop empties
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

        // start sender executor
        senderExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Alerts-Sender-Thread");
            t.setDaemon(true);
            return t;
        });

        // schedule the processing loop
        senderExecutor.scheduleAtFixedRate(this::processQueueSafely, 0L, SENDER_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // initialize JDA in background
        initJdaAsync();
    }

    // Initialize JDA on its own thread and allow JDA to set the jda field once ready.
    private void initJdaAsync() {
        Thread t = new Thread(() -> {
            try {
                JDA local = JDABuilder.createDefault(token).build();
                local.awaitReady();
                jda = local;
                LOGGER.info("[Alerts] Discord initialized successfully.");

                // when JDA becomes ready, clear the cached channel so resolve attempt happens immediately
                cachedChannel = null;
                cachedChannelExpiry = 0L;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.SEVERE, "[Alerts] Discord init interrupted.", ie);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[Alerts] Failed to initialize Discord: " + e.getMessage(), e);
            } finally {
                // clear initThread reference when done
                initThread = null;
            }
        }, "Discord-Init-Thread");
        t.setDaemon(true);
        initThread = t;
        t.start();
    }

    @Override
    public void publish(LogRecord record) {
        if (closed) return; // do not accept new records after close
        if (record == null) return;

        // Prevent self-recursion (Alerts' own warnings/errors should not generate Discord alerts)
        if (isSelfRecord(record)) return;

        // Prevent re-entrant loops even if logger names are unusual
        if (Boolean.TRUE.equals(IN_PUBLISH.get())) return;

        IN_PUBLISH.set(Boolean.TRUE);
        try {
            if (record.getLevel().intValue() < Level.WARNING.intValue()) {
                return; // only handle WARNING and above
            }

            // Build the formatted message, but also evaluate ignores against the *raw* message/throwable.
            String formatted = formatRecord(record);
            if (shouldIgnore(record, formatted)) return;

            enqueueMessage(formatted);
        } catch (Throwable t) {
            // publish must not throw
            try {
                LOGGER.log(Level.SEVERE, "[Alerts] publish() failed: " + t.getMessage(), t);
            } catch (Throwable ignored) {
            }
        } finally {
            IN_PUBLISH.set(Boolean.FALSE);
        }
    }

    private boolean isSelfRecord(LogRecord record) {
        if (record == null) return false;
        String ln = record.getLoggerName();
        if (SELF_LOGGER_NAME.equals(ln)) return true;
        String sc = record.getSourceClassName();
        return SELF_LOGGER_NAME.equals(sc);
    }

    // Enqueue a new message; if queue is too large we drop the oldest messages to keep bounded memory.
    private void enqueueMessage(String formatted) {
        if (formatted == null) return;
        if (closed) return;

        // enforce queue cap
        int currentSize = sendQueue.size();
        if (currentSize >= MAX_QUEUE_SIZE) {
            // drop ~1/10th oldest to relieve pressure and record metric
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

    // Safe wrapper for processQueue to ensure exceptions are caught.
    private void processQueueSafely() {
        try {
            processQueue();
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "[Alerts] Sender thread error: " + t.getMessage(), t);
        }
    }

    // Main sending loop executed on senderExecutor at the configured interval.
    private void processQueue() {
        if (sendQueue.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now < networkBackoffUntilMs) {
            // Network seems down; keep queue intact and retry later.
            return;
        }

        // Ensure JDA is ready
        JDA localJda = jda;
        if (localJda == null) {
            // JDA not ready; nothing to send to Discord yet. We keep messages queued for later.
            return;
        }

        // Resolve channel (cached)
        TextChannel channel = resolveChannel(localJda);
        if (channel == null) {
            // Channel still not resolvable; rate-limit a local warning and return (leave queue intact).
            if (now - lastChannelMissingWarn > CHANNEL_MISSING_WARN_COOLDOWN_MS) {
                lastChannelMissingWarn = now;
                LOGGER.warning("[Alerts] Could not find Discord channel with ID: " + channelId + " - will retry later.");
            }
            return;
        }

        // Send up to SENDER_BATCH_SIZE messages from the queue
        for (int i = 0; i < SENDER_BATCH_SIZE; i++) {
            AlertMessage entry = sendQueue.poll();
            if (entry == null) break;

            // split large messages and send each part asynchronously
            sendMessageParts(channel, entry);
        }
    }

    // Resolve channel via JDA with caching
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
            // don't spam logs; we'll warn in a rate-limited fashion
            long last = lastChannelMissingWarn;
            if (System.currentTimeMillis() - last > CHANNEL_MISSING_WARN_COOLDOWN_MS) {
                lastChannelMissingWarn = System.currentTimeMillis();
                LOGGER.log(Level.WARNING, "[Alerts] Exception while resolving channel: " + t.getMessage(), t);
            }
            // keep cachedChannel as null and try again later
            cachedChannel = null;
            cachedChannelExpiry = 0L;
            return null;
        }
    }

    // Split a long message into DISCORD_MAX_LEN parts and send them asynchronously.
    // On failure, the entire original message will be requeued up to MAX_SEND_ATTEMPTS (network failures do not count).
    private void sendMessageParts(TextChannel channel, AlertMessage entry) {
        if (entry == null || entry.content == null) return;

        final String original = entry.content;
        final int failuresSoFar = entry.attempts;

        // If we've already tried too many times, drop it with a local log to avoid infinite retries.
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

            // send asynchronously; attach failure handler that requeues the original message
            channel.sendMessage(part).queue(
                    success -> {
                        // Any success means Discord/network is reachable again; clear backoff.
                        clearNetworkBackoff();
                    },
                    throwable -> {
                        boolean networkIssue = isNetworkException(throwable);
                        if (networkIssue) {
                            applyNetworkBackoff();
                        }

                        maybeLogSendFailure(logged, throwable);

                        // requeue only once per original message (even if multiple parts fail)
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
        // only log once per original message
        if (logged != null && !logged.compareAndSet(false, true)) return;

        long now = System.currentTimeMillis();
        if (now - lastSendFailWarnMs > SEND_FAIL_WARN_COOLDOWN_MS) {
            lastSendFailWarnMs = now;
            try {
                LOGGER.log(Level.WARNING, "[Alerts] Failed to send message to Discord: " + (throwable == null ? "unknown" : throwable.getMessage()), throwable);
            } catch (Throwable ignored) {
            }
        } else {
            // keep it quiet between cooldown windows
            try {
                LOGGER.log(Level.FINE, "[Alerts] Failed to send message to Discord: " + (throwable == null ? "unknown" : throwable.getMessage()));
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyNetworkBackoff() {
        long now = System.currentTimeMillis();

        // If we're already backing off, extend at most to the new calculated value.
        long proposedUntil = now + networkBackoffMs;
        if (proposedUntil > networkBackoffUntilMs) {
            networkBackoffUntilMs = proposedUntil;
        }

        long next = networkBackoffMs * 2L;
        networkBackoffMs = Math.min(next, NETWORK_BACKOFF_MAX_MS);

        // Force channel re-resolve after reconnect (harmless if it stays valid)
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
                    cur instanceof java.net.ConnectException ||
                    cur instanceof java.net.NoRouteToHostException ||
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

    // Format a LogRecord to a user-friendly Discord-friendly string, including thrown stack traces.
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
                // wrap in codeblock; note: splitting may break triple-backticks if too long, but safe enough
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
            // we must not throw from here
            try {
                LOGGER.log(Level.WARNING, "[Alerts] Failed to extract stack trace: " + e.getMessage(), e);
            } catch (Throwable ignored) {
            }
            return "";
        }
    }

    @Override
    public void flush() {
        // nothing to flush: messages are in sendQueue and will be handled by senderExecutor.
    }

    // Close handler: stop executor and shut down JDA safely (bounded wait)
    @Override
    public void close() throws SecurityException {
        if (closed) return; // idempotent
        closed = true;

        LOGGER.info("[Alerts] Closing Alerts handler: stopping sender executor, interrupting init, shutting down JDA...");

        // 1) stop the sender executor (no more queue processing)
        if (senderExecutor != null) {
            try {
                senderExecutor.shutdown(); // polite shutdown
                if (!senderExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    LOGGER.warning("[Alerts] Sender executor did not terminate within timeout; forcing shutdown.");
                    senderExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                try { senderExecutor.shutdownNow(); } catch (Throwable ignored) {}
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "[Alerts] Error shutting down sender executor: " + t.getMessage(), t);
                try { senderExecutor.shutdownNow(); } catch (Throwable ignored) {}
            }
        }

        // 2) interrupt init thread if JDA is still initializing
        try {
            Thread t = initThread;
            if (t != null && t.isAlive()) {
                LOGGER.fine("[Alerts] Interrupting JDA init thread...");
                try {
                    t.interrupt();
                } catch (Throwable ignored) {}
                // allow a short moment for init thread to respond
                try {
                    t.join(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            initThread = null;
        } catch (Throwable ignored) {}

        // 3) attempt to shut down JDA and wait for JDA-related threads to finish (bounded wait)
        try {
            if (jda != null) {
                try {
                    LOGGER.fine("[Alerts] Calling jda.shutdownNow()...");
                    try {
                        jda.shutdownNow();
                    } catch (Throwable t) {
                        // fallback to shut down if shutdownNow not present / failed
                        try { jda.shutdown(); } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING, "[Alerts] Exception while initiating JDA shutdown: " + t.getMessage(), t);
                }

                // Wait for JDA related threads to exit. Bounded wait (5s).
                final long deadline = System.currentTimeMillis() + 5_000L;

                // Collect candidate threads once and then join them individually (no busy-wait loop)
                List<Thread> toJoin = new ArrayList<>();
                for (Thread thread : Thread.getAllStackTraces().keySet()) {
                    if (thread == null) continue;
                    String name = thread.getName();
                    if (name == null) continue;
                    String nl = name.toLowerCase();
                    if ((nl.contains("jda") || nl.contains("discord") || nl.contains("websocket") ||
                            nl.contains("gateway") || nl.contains("audio") || nl.contains("okhttp")) && thread.isAlive()) {
                        toJoin.add(thread);
                    }
                }

                for (Thread th : toJoin) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    try {
                        // join will block efficiently until th dies or timeout elapses
                        th.join(remaining);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable joinEx) {
                        LOGGER.log(Level.FINE, "[Alerts] Exception while joining thread " + th.getName() + ": " + joinEx.getMessage(), joinEx);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[Alerts] Error while waiting for JDA threads to terminate: " + t.getMessage(), t);
        } finally {
            // clear JDA reference so GC can collect classloader resources
            jda = null;
        }

        // 4) Final cleanup: clear queue and caches
        try {
            sendQueue.clear();
        } catch (Throwable ignored) {}
        try {
            cachedChannel = null;
            cachedChannelExpiry = 0L;
        } catch (Throwable ignored) {}

        LOGGER.info("[Alerts] Alerts handler closed.");
    }

    /**
     * Determine whether an alert should be ignored based on the configured ignore list.
     *
     * Matching rules (for each pattern):
     * - Exact substring match against the formatted message.
     * - Exact substring match against the formatted *body* (record message + params).
     * - Exact substring match against throwable message / stack trace (if present).
     * - Case-insensitive, whitespace-normalized match against the above (fallback).
     */
    private boolean shouldIgnore(LogRecord record, String formatted) {
        if (ignoreList == null || ignoreList.isEmpty()) return false;

        String body = null;
        try {
            body = formatMessageBody(record);
        } catch (Throwable ignored) {
        }

        Throwable thrown = (record == null) ? null : record.getThrown();
        String thrownMsg = (thrown == null) ? null : thrown.getMessage();
        String stack = null;
        if (thrown != null) {
            try {
                stack = getStackTraceString(thrown);
            } catch (Throwable ignored) {
            }
        }

        for (String rawPattern : ignoreList) {
            if (rawPattern == null) continue;
            String pattern = rawPattern.trim();
            if (pattern.isEmpty()) continue;

            // Fast exact contains (case-sensitive)
            if (formatted != null && formatted.contains(pattern)) return true;
            if (body != null && body.contains(pattern)) return true;
            if (thrownMsg != null && thrownMsg.contains(pattern)) return true;
            if (stack != null && stack.contains(pattern)) return true;

            // Robust contains (case-insensitive + whitespace-normalized)
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
        // Lowercase + collapse whitespace so "a  b" matches "a b"
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

    /**
     * Best-effort: load ignore list from any plugin config.yml on disk (typically plugins/<PluginName>/config.yml)
     * that contains either "alerts.ignore" or "discord.ignore".
     *
     * This exists to prevent silent misconfiguration when the caller forgets to pass the ignore list into
     * the Alerts constructor.
     */
    private List<String> tryLoadIgnoreListFromDisk() {
        try {
            Path pluginsDir = Paths.get("plugins");
            if (!Files.isDirectory(pluginsDir)) return Collections.emptyList();

            // Depth 2 is enough for plugins/<PluginName>/config.yml
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

                    // Quick prefilter to avoid parsing every config.yml
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

    /**
     * Minimal YAML list extractor for the common pattern:
     *
     * parentKey:
     *   childKey:
     *     - item
     *     - "item with spaces"
     *
     * Also supports inline lists: childKey: ["a", "b"]
     */
    private List<String> extractYamlStringList(String yaml, String parentKey, String childKey) {
        if (yaml == null) return Collections.emptyList();
        if (parentKey == null || childKey == null) return Collections.emptyList();

        String[] lines = yaml.split("\r?\n");
        int parentIndent = -1;
        int childIndent = -1;
        boolean inParent = false;
        boolean inChild = false;

        List<String> out = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) continue;

            // strip comments (best effort)
            int hash = line.indexOf('#');
            String effective = (hash >= 0) ? line.substring(0, hash) : line;
            if (effective.trim().isEmpty()) continue;

            int indent = countIndent(effective);
            String trimmed = effective.trim();

            // Enter/exit parent
            if (!inParent) {
                if (isYamlKey(trimmed, parentKey)) {
                    inParent = true;
                    parentIndent = indent;
                    childIndent = -1;
                    inChild = false;
                }
                continue;
            } else {
                // If indentation returns to parent level or above and we're not on the parent key itself, we left parent.
                if (indent <= parentIndent && !isYamlKey(trimmed, parentKey)) {
                    break;
                }
            }

            // Enter/exit child
            if (!inChild) {
                if (indent > parentIndent && isYamlKey(trimmed, childKey)) {
                    inChild = true;
                    childIndent = indent;

                    // Inline list support: ignore: ["a", "b"]
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
                // If indentation returns to child indent or above and it's not a list item, we left child list.
                if (indent <= childIndent && !trimmed.startsWith("-")) {
                    break;
                }
            }

            if (inChild) {
                if (indent > childIndent && trimmed.startsWith("-")) {
                    String item = trimmed.substring(1).trim();
                    item = stripQuotes(item);
                    if (!item.isEmpty()) out.add(item);
                }
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
        // key:
        if (trimmed.equals(k + ":")) return true;
        // key: value
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
        // bracketed: ["a", "b"] or [a, b]
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
}