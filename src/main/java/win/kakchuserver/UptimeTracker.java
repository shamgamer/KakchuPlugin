package win.kakchuserver;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * UptimeTracker - Robust implementation (single interval per run, periodic end updates, async atomic saves,
 * merge/trim, exact rolling window computation).

 * Config keys (put in your plugin config.yml):
 * uptime:
 *   flush-interval-seconds: 1      # how often to update last interval end (min 1)
 *   retention-days: 370            # how many days of intervals to keep; set -1 to keep forever
 *   merge-gap-ms: 1000             # merge intervals separated by <= this ms

 * Integration:
 *  - Create an instance in your plugin's onEnable():
 *      tracker = new UptimeTracker(this);
 *      tracker.start();
 *      this.getCommand("uptime").setExecutor(new UptimeTracker.UptimeCommand(tracker));
 *  - Call tracker.stop() from plugin's onDisable().
 */
public class UptimeTracker {
    private final JavaPlugin plugin;
    private final File dataFile;
    private YamlConfiguration cfg;

    // keys in the YAML
    private static final String KEY_RUNNING_SINCE = "runningSince"; // epoch millis (for compatibility)
    private static final String KEY_ALLTIME_SINCE = "alltime.trackedSince"; // epoch millis
    private static final String KEY_INTERVALS = "intervals"; // list of maps {start: <ms>, end: <ms>}

    // runtime state
    private volatile long runningSince = -1L; // epoch millis
    private int flushTaskId = -1;

    // Single-threaded executor to serialize all async saves (prevents temp-file race)
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Kakchu-UptimeSave");
        t.setDaemon(true);
        return t;
    });

    // defaults (overridable via config)
    private static final int DEFAULT_FLUSH_SECONDS = 1;
    private static final int DEFAULT_RETENTION_DAYS = 370;
    private static final long DEFAULT_MERGE_GAP_MS = 1000L;

    public UptimeTracker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "uptime.yml");

        File dataDir = plugin.getDataFolder();
        if (!dataDir.exists()) {
            boolean ok = dataDir.mkdirs();
            if (!ok) {
                plugin.getLogger().warning("Could not create plugin data folder: " + dataDir.getAbsolutePath());
            }
        }

        // Cleanup any leftover temp files from previous runs (optional hygiene)
        cleanupLeftoverTempFiles();

        loadConfig();
    }

    /**
     * Remove leftover temp files matching uptime.yml.*.tmp that are old enough to be considered stale.
     */
    private void cleanupLeftoverTempFiles() {
        try {
            Path parent = dataFile.toPath().getParent();
            if (parent == null) return;
            DirectoryStream.Filter<Path> filter = entry -> {
                String name = entry.getFileName().toString();
                return name.startsWith(dataFile.getName() + ".") && name.endsWith(".tmp");
            };
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(parent, filter)) {
                for (Path p : ds) {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                        long ageMs = System.currentTimeMillis() - attrs.lastModifiedTime().toMillis();
                        // delete files older than 1 minute (safe threshold)
                        if (ageMs > Duration.ofMinutes(1).toMillis()) {
                            Files.deleteIfExists(p);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Load (or initialize) uptime.yml
     */
    private void loadConfig() {
        if (dataFile.exists()) {
            cfg = YamlConfiguration.loadConfiguration(dataFile);
        } else {
            cfg = new YamlConfiguration();
            // initialize fields
            cfg.set(KEY_INTERVALS, new ArrayList<Map<String, Object>>());
            cfg.set(KEY_ALLTIME_SINCE, System.currentTimeMillis());
            saveConfig(); // initial synchronous write
        }

        // ensure intervals exists and is a list
        if (!cfg.contains(KEY_INTERVALS) || !(cfg.get(KEY_INTERVALS) instanceof List)) {
            cfg.set(KEY_INTERVALS, new ArrayList<Map<String, Object>>());
            saveConfig();
        }

        // ensure alltime.trackedSince exists
        if (!cfg.contains(KEY_ALLTIME_SINCE)) {
            cfg.set(KEY_ALLTIME_SINCE, System.currentTimeMillis());
            saveConfig();
        }
    }

    /**
     * Synchronous save (used on shutdown / fallback)
     */
    private synchronized void saveConfig() {
        try {
            cfg.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save uptime data", e);
        }
    }

    /**
     * Low-level helper: write the YAML snapshot to a tmp file and move into place.
     * This is used both from async task and as a synchronous fallback on shutdown.
     */
    private void writeAtomicYamlSnapshot(String yamlDump) throws IOException {
        // Ensure parent directory exists
        Path parent = dataFile.toPath().getParent();
        if (parent == null) {
            // fallback to plugin data folder path; this should not normally be null, but be defensive
            parent = plugin.getDataFolder().toPath();
        }
        Files.createDirectories(parent);

        // Create a unique temporary file in the same directory (avoids races between writers)
        // e.g. uptime.yml.123456.tmp
        Path tmp = Files.createTempFile(parent, dataFile.getName() + ".", ".tmp");

        // Write temp file
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(tmp,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                        StandardCharsets.UTF_8))) {
            out.write(yamlDump);
            out.flush();
        } catch (IOException writeEx) {
            // cleanup temp if write failed
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            throw writeEx;
        }

        // Defensive existence check
        if (!Files.exists(tmp)) {
            throw new IOException("Temporary file not present after write: " + tmp);
        }

        // Try atomic move; fall back to replace if not supported
        try {
            Files.move(tmp, dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
            Files.move(tmp, dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.NoSuchFileException nsfe) {
            // tmp missing at move time -> report
            throw new IOException("Temporary file missing during move: " + tmp, nsfe);
        } catch (Exception ex) {
            // ensure we don't leak temp files on unexpected failure
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            throw ex;
        }
    }

    /**
     * Async atomic save: snapshot YAML string under lock, then write the bytes asynchronously to a temp file
     * and move into place atomically where supported.

     * IMPORTANT: If the plugin is already disabled (shutdown path) we avoid scheduling any Bukkit tasks
     * and do a synchronous write instead.
     */
    private void saveConfigAsync() {
        final String yamlDump;
        synchronized (this) {
            try {
                yamlDump = cfg.saveToString();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to create YAML snapshot for async save", e);
                return;
            }
        }

        // If plugin is disabled, perform synchronous write to avoid scheduler calls during shutdown.
        if (!plugin.isEnabled()) {
            try {
                writeAtomicYamlSnapshot(yamlDump);
            } catch (IOException io) {
                plugin.getLogger().log(Level.WARNING, "Failed to write uptime.yml synchronously during shutdown", io);
            }
            return;
        }

        // Submit to our serializing executor. If it's rejected, fallback to sync write.
        try {
            saveExecutor.submit(() -> {
                try {
                    writeAtomicYamlSnapshot(yamlDump);
                } catch (IOException io) {
                    plugin.getLogger().log(Level.WARNING, "Failed to write uptime.yml asynchronously", io);
                }
            });
        } catch (RejectedExecutionException ex) {
            plugin.getLogger().log(Level.FINER, "Save executor rejected async save (shutting down?), falling back to sync save.", ex);
            try {
                writeAtomicYamlSnapshot(yamlDump);
            } catch (IOException io) {
                plugin.getLogger().log(Level.WARNING, "Failed to write uptime.yml synchronously after executor rejection", io);
            }
        }
    }

    // ------------------------- Interval helpers -------------------------

    /**
     * Get intervals as a mutable list of maps (each map contains "start" (Long) and "end" (Long)).
     * This method performs a single unchecked cast from the raw map-list returned by the config API.
     */
    @SuppressWarnings("unchecked")
    private synchronized List<Map<String, Object>> getIntervalsMutable() {
        // YamlConfiguration#getMapList returns List<Map<?,?>>; cast once in a controlled place
        List<Map<String, Object>> list = (List<Map<String, Object>>) (List<?>) cfg.getMapList(KEY_INTERVALS);
        // getMapList returns an empty list if not present, but defensively ensure a fresh mutable copy
        return new ArrayList<>(list);
    }

    /**
     * Write back intervals into cfg (and perform trimming + merging) then async-save.
     * This method must be called synchronized (it uses getIntervalsMutable which is synchronized).
     */
    private synchronized void setIntervalsAndSave(List<Map<String, Object>> intervals) {
        // normalize entries (ensure start/end are Long, remove malformed)
        List<Interval> parsed = new ArrayList<>(intervals.size());
        for (Map<String, Object> m : intervals) {
            if (m == null) continue;
            Object oStart = m.get("start");
            Object oEnd = m.get("end");
            try {
                long s = oStart instanceof Number ? ((Number) oStart).longValue() : Long.parseLong(String.valueOf(oStart));
                long e = oEnd instanceof Number ? ((Number) oEnd).longValue() : Long.parseLong(String.valueOf(oEnd));
                if (e < s) e = s; // defensive
                parsed.add(new Interval(s, e));
            } catch (Exception ignored) {
                // skip malformed entry
            }
        }

        // perform merge + trim according to config
        long now = System.currentTimeMillis();
        int retentionDays = getRetentionDays();

        // Support sentinel: retentionDays < 0 => keep forever (don't trim by age)
        final long minKeepEnd;
        if (retentionDays < 0) {
            // keep everything (no age-based trimming)
            minKeepEnd = Long.MIN_VALUE;
            plugin.getLogger().finer("[Uptime] retention-days < 0 detected: infinite retention (no age trimming).");
        } else {
            long retentionMs = Duration.ofDays(Math.max(1, retentionDays)).toMillis();
            minKeepEnd = now - retentionMs;
        }

        long mergeGapMs = getMergeGapMs();

        // sort by start
        parsed.sort(Comparator.comparingLong(i -> i.start));

        // merge contiguous/close intervals and drop too-old intervals (unless minKeepEnd == Long.MIN_VALUE)
        List<Interval> out = new ArrayList<>();
        for (Interval iv : parsed) {
            if (minKeepEnd != Long.MIN_VALUE && iv.end < minKeepEnd) continue; // too old entirely
            if (out.isEmpty()) {
                out.add(iv);
            } else {
                Interval last = out.get(out.size() - 1);
                if (iv.start <= last.end || iv.start <= last.end + mergeGapMs) {
                    // overlap or small gap -> merge
                    last.end = Math.max(last.end, iv.end);
                } else {
                    out.add(iv);
                }
            }
        }

        // convert back to list of maps
        List<Map<String, Object>> saveList = new ArrayList<>(out.size());
        for (Interval iv : out) {
            Map<String, Object> m = new HashMap<>();
            m.put("start", iv.start);
            m.put("end", iv.end);
            saveList.add(m);
        }

        cfg.set(KEY_INTERVALS, saveList);
        // ensure trackedSince set
        if (!cfg.contains(KEY_ALLTIME_SINCE)) {
            cfg.set(KEY_ALLTIME_SINCE, now);
        }
        // async-save snapshot (or sync if shutdown)
        saveConfigAsync();
    }

    /**
     * Append a new run interval (start,end). Will merge with previous if needed and trim old ones.
     * Use this for starting or when you want to record a completed interval.
     */
    private synchronized void appendInterval(long startMs, long endMs) {
        if (endMs < startMs) endMs = startMs;

        List<Map<String, Object>> intervals = getIntervalsMutable();

        // convert to Interval objects for easier logic
        List<Interval> parsed = new ArrayList<>();
        for (Map<String, Object> m : intervals) {
            if (m == null) continue;
            Object oStart = m.get("start");
            Object oEnd = m.get("end");
            try {
                long s = oStart instanceof Number ? ((Number) oStart).longValue() : Long.parseLong(String.valueOf(oStart));
                long e = oEnd instanceof Number ? ((Number) oEnd).longValue() : Long.parseLong(String.valueOf(oEnd));
                if (e < s) e = s;
                parsed.add(new Interval(s, e));
            } catch (Exception ignored) {
            }
        }

        // add the new interval (merge logic will run in setIntervalsAndSave)
        parsed.add(new Interval(startMs, endMs));

        // convert back to maps (we pass to setIntervalsAndSave which will sort/merge/trim)
        List<Map<String, Object>> toSave = new ArrayList<>();
        for (Interval iv : parsed) {
            Map<String, Object> m = new HashMap<>();
            m.put("start", iv.start);
            m.put("end", iv.end);
            toSave.add(m);
        }

        setIntervalsAndSave(toSave);
    }

    /**
     * Update last interval's end to given time (used for periodic flush). If no interval exists, create one.
     */
    private synchronized void updateLastIntervalEnd(long endMs) {
        List<Map<String, Object>> intervals = getIntervalsMutable();
        if (intervals.isEmpty()) {
            // no existing interval -> append new
            Map<String, Object> entry = new HashMap<>();
            entry.put("start", endMs);
            entry.put("end", endMs);
            intervals.add(entry);
            cfg.set(KEY_INTERVALS, intervals);
            saveConfigAsync();
            return;
        }

        // update last map's end
        Map<String, Object> last = intervals.get(intervals.size() - 1);
        last.put("end", endMs);
        cfg.set(KEY_INTERVALS, intervals);

        // we save async but don't run merge/trim on every second-level update to avoid churn;
        // merge/trim will be applied at appendInterval or periodically in setIntervalsAndSave when needed.
        saveConfigAsync();
    }

    // ------------------------- Lifecycle: start / stop / periodic flush -------------------------

    /**
     * Start tracking: recovers unclosed previous interval, appends new run record, and schedules periodic flush.
     */
    public synchronized void start() {
        long now = System.currentTimeMillis();

        // ensure config trackedSince
        if (!cfg.contains(KEY_ALLTIME_SINCE)) cfg.set(KEY_ALLTIME_SINCE, now);

        // Recover previous unclosed interval if present
        List<Map<String, Object>> intervals = getIntervalsMutable();
        if (!intervals.isEmpty()) {
            Map<String, Object> last = intervals.get(intervals.size() - 1);
            long lastStart = toLong(last.get("start"));
            long lastEnd = toLong(last.get("end"));

            // If lastEnd < lastStart (or equals -1), treat it as unclosed/crash and finalize at 'now'
            if (lastStart > 0 && (lastEnd < lastStart)) {
                last.put("end", now);
                cfg.set(KEY_INTERVALS, intervals);
                saveConfigAsync();
                plugin.getLogger().info("Recovered previous unclosed uptime interval; finalized at now.");
            }
        }

        // Append new interval for this run; initialize end = start (we'll update it periodically)
        appendInterval(now, now);
        runningSince = now;
        cfg.set(KEY_RUNNING_SINCE, runningSince);
        saveConfigAsync();

        // schedule periodic flush using configured interval (seconds)
        int flushIntervalSeconds = getFlushIntervalSeconds();
        if (flushIntervalSeconds < 1) flushIntervalSeconds = 1;
        long ticks = 20L * flushIntervalSeconds;

        flushTaskId = plugin.getServer().getScheduler()
                .scheduleSyncRepeatingTask(plugin, this::periodicFlush, ticks, ticks);
    }

    /**
     * Stop tracking: update last interval end, cancel task, sync-save (final).
     */
    public synchronized void stop() {
        // cancel scheduled task if present
        if (flushTaskId != -1) {
            try {
                plugin.getServer().getScheduler().cancelTask(flushTaskId);
            } catch (Exception ignored) {
            }
            flushTaskId = -1;
        }

        long now = System.currentTimeMillis();
        // ensure last interval's end is the final end
        // updateLastIntervalEnd calls saveConfigAsync; that method detects plugin.isEnabled() and will do sync save if needed
        updateLastIntervalEnd(now);
        runningSince = -1L;
        cfg.set(KEY_RUNNING_SINCE, null);

        // ensure merge/trim and schedule async saves for persistence
        List<Map<String, Object>> intervals = getIntervalsMutable();
        setIntervalsAndSave(intervals);

        // Shutdown the saveExecutor and wait for pending writes to finish so they don't overwrite our final state.
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                // if it didn't finish in time, force shutdown and perform a synchronous atomic write of the latest snapshot
                saveExecutor.shutdownNow();
                plugin.getLogger().finer("[Uptime] saveExecutor did not terminate in time; forcing shutdown and performing final synchronous save.");
                try {
                    final String yamlDump;
                    synchronized (this) {
                        yamlDump = cfg.saveToString();
                    }
                    writeAtomicYamlSnapshot(yamlDump);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to perform final synchronous write of uptime.yml during shutdown", e);
                }
            } else {
                // executor finished normally; for extra safety do one last synchronous save using cfg.save(file)
                saveConfig();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            saveExecutor.shutdownNow();
            try {
                final String yamlDump;
                synchronized (this) {
                    yamlDump = cfg.saveToString();
                }
                writeAtomicYamlSnapshot(yamlDump);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to perform final synchronous write of uptime.yml after interrupted shutdown", e);
            }
        }
    }

    /**
     * Periodic flush invoked on the main thread (scheduled). It simply updates last interval's end
     * and triggers async persistence. This is intentionally cheap (no merging here).
     */
    private synchronized void periodicFlush() {
        if (runningSince <= 0) return;
        long now = System.currentTimeMillis();
        if (now <= runningSince) return;
        updateLastIntervalEnd(now);
        runningSince = now;
        cfg.set(KEY_RUNNING_SINCE, runningSince);
        // updateLastIntervalEnd already called async-save (or sync fallback if shutdown)
    }

    // ------------------------- Querying / Formatting -------------------------

    private static String formatSeconds(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long mins = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (days > 0) return String.format("%dd %dh %dm %ds", days, hours, mins, secs);
        if (hours > 0) return String.format("%dh %dm %ds", hours, mins, secs);
        if (mins > 0) return String.format("%dm %ds", mins, secs);
        return String.format("%ds", secs);
    }

    /**
     * Returns a UptimeSummary for the requested period type.
     * period: "day", "week", "month", "year", "all" (alltime)
     * plus rolling windows: "24h", "7d", "30d", "365d"
     */
    public synchronized UptimeSummary getSummary(String period) {
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime nowZ = Instant.ofEpochMilli(now).atZone(zone);

        long uptimeSeconds;
        long periodPossibleSeconds;
        String label;

        switch (period.toLowerCase(Locale.ROOT)) {
            // calendar aligned buckets -> compute by overlap with bucket start.now
            case "day": {
                long bucketStart = nowZ.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
                uptimeSeconds = computeOverlap(bucketStart, now);
                periodPossibleSeconds = Math.max(0L, (now - bucketStart) / 1000L);
                label = "Today";
                break;
            }
            case "week": {
                WeekFields wf = WeekFields.ISO;
                LocalDate startOfWeek = nowZ.toLocalDate().with(wf.dayOfWeek(), 1);
                long bucketStart = startOfWeek.atStartOfDay(zone).toInstant().toEpochMilli();
                uptimeSeconds = computeOverlap(bucketStart, now);
                periodPossibleSeconds = Math.max(0L, (now - bucketStart) / 1000L);
                label = "This week";
                break;
            }
            case "month": {
                LocalDate startOfMonth = nowZ.toLocalDate().withDayOfMonth(1);
                long bucketStart = startOfMonth.atStartOfDay(zone).toInstant().toEpochMilli();
                uptimeSeconds = computeOverlap(bucketStart, now);
                periodPossibleSeconds = Math.max(0L, (now - bucketStart) / 1000L);
                label = "This month";
                break;
            }
            case "year": {
                LocalDate startOfYear = nowZ.toLocalDate().withDayOfYear(1);
                long bucketStart = startOfYear.atStartOfDay(zone).toInstant().toEpochMilli();
                uptimeSeconds = computeOverlap(bucketStart, now);
                periodPossibleSeconds = Math.max(0L, (now - bucketStart) / 1000L);
                label = "This year";
                break;
            }
            case "all":
            case "alltime":
            case "overall": {
                long trackedSince = cfg.getLong(KEY_ALLTIME_SINCE, now);
                uptimeSeconds = computeOverlap(trackedSince, now);
                periodPossibleSeconds = Math.max(0L, (now - trackedSince) / 1000L);
                label = "All time";
                break;
            }

            // rolling windows
            case "24h": {
                long cutoff = now - Duration.ofHours(24).toMillis();
                uptimeSeconds = computeOverlap(cutoff, now);
                long trackedSince = cfg.getLong(KEY_ALLTIME_SINCE, now);
                periodPossibleSeconds = Math.max(0L, (now - Math.max(cutoff, trackedSince)) / 1000L);
                label = "Past 24 hours";
                break;
            }
            case "7d":
            case "7days":
            case "1week": {
                long cutoff = now - Duration.ofDays(7).toMillis();
                uptimeSeconds = computeOverlap(cutoff, now);
                long trackedSince = cfg.getLong(KEY_ALLTIME_SINCE, now);
                periodPossibleSeconds = Math.max(0L, (now - Math.max(cutoff, trackedSince)) / 1000L);
                label = "Past 7 days";
                break;
            }
            case "30d":
            case "30days":
            case "1month": {
                long cutoff = now - Duration.ofDays(30).toMillis();
                uptimeSeconds = computeOverlap(cutoff, now);
                long trackedSince = cfg.getLong(KEY_ALLTIME_SINCE, now);
                periodPossibleSeconds = Math.max(0L, (now - Math.max(cutoff, trackedSince)) / 1000L);
                label = "Past 30 days";
                break;
            }
            case "365d":
            case "365days":
            case "1year": {
                long cutoff = now - Duration.ofDays(365).toMillis();
                uptimeSeconds = computeOverlap(cutoff, now);
                long trackedSince = cfg.getLong(KEY_ALLTIME_SINCE, now);
                periodPossibleSeconds = Math.max(0L, (now - Math.max(cutoff, trackedSince)) / 1000L);
                label = "Past 365 days";
                break;
            }

            default:
                return null;
        }

        double percent = (periodPossibleSeconds > 0) ? (100.0 * uptimeSeconds / periodPossibleSeconds) : 0.0;
        return new UptimeSummary(label, uptimeSeconds, periodPossibleSeconds, percent,
                Instant.ofEpochMilli(cfg.getLong(KEY_ALLTIME_SINCE, System.currentTimeMillis())));
    }

    /**
     * Compute overlap seconds between [windowStartMs, nowMs) and stored intervals including current running.
     * Assumes caller synchronizes.
     */
    private long computeOverlap(long windowStartMs, long nowMs) {
        long total = 0L;
        List<Map<String, Object>> intervals = getIntervalsMutable();
        for (Map<String, Object> m : intervals) {
            if (m == null) continue;
            long s = toLong(m.get("start"));
            long e = toLong(m.get("end"));
            if (s < 0) continue;
            if (e < s) e = s;
            long overlapStart = Math.max(s, windowStartMs);
            long overlapEnd = Math.min(e, nowMs);
            if (overlapStart < overlapEnd) {
                total += (overlapEnd - overlapStart) / 1000L;
            }
        }

        // also account for running interval if present and not fully captured above
        long rs = runningSince;
        if (rs > 0 && rs < nowMs) {
            long overlapStart = Math.max(rs, windowStartMs);
            if (overlapStart < nowMs) {
                total += (nowMs - overlapStart) / 1000L;
            }
        }

        return total;
    }

    // ------------------------- Utilities & small helpers -------------------------

    /**
     * Convert a config value to long; returns -1 if unparsable or null.
     * Single-arg helper so callers don't pass a literal fallback every time.
     */
    private static long toLong(Object o) {
        if (o == null) return -1L;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private int getConfigInt(String path, int fallback) {
        try {
            return plugin.getConfig().getInt(path, fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    // Small helpers to avoid repeating the same literal parameters across the codebase
    private int getFlushIntervalSeconds() {
        return getConfigInt("uptime.flush-interval-seconds", DEFAULT_FLUSH_SECONDS);
    }

    private int getRetentionDays() {
        return getConfigInt("uptime.retention-days", DEFAULT_RETENTION_DAYS);
    }

    /**
     * Read merge-gap-ms from the "uptime" section directly to avoid repeated literal-parameter warnings.
     * This avoids calling a helper with the identical literal path/fallback and silences IDE inspection warnings.
     */
    private long getMergeGapMs() {
        try {
            ConfigurationSection uptime = plugin.getConfig().getConfigurationSection("uptime");
            if (uptime == null) return DEFAULT_MERGE_GAP_MS;
            return uptime.getLong("merge-gap-ms", DEFAULT_MERGE_GAP_MS);
        } catch (Exception ignored) {
            return DEFAULT_MERGE_GAP_MS;
        }
    }

    // Simple interval holder for internal merging/trimming
    private static class Interval {
        long start;
        long end;

        Interval(long s, long e) {
            this.start = s;
            this.end = e;
        }
    }

    // ------------------------- Uptime summary + command -------------------------

    public static class UptimeSummary {
        public final String label;
        public final long uptimeSeconds;
        public final long possibleSeconds;
        public final double percent;
        public final Instant trackedSince;

        public UptimeSummary(String label, long uptimeSeconds, long possibleSeconds, double percent, Instant trackedSince) {
            this.label = label;
            this.uptimeSeconds = uptimeSeconds;
            this.possibleSeconds = possibleSeconds;
            this.percent = percent;
            this.trackedSince = trackedSince;
        }
    }

    // ------------------------- Command executor -------------------------
    public static class UptimeCommand implements CommandExecutor {
        private final UptimeTracker tracker;

        public UptimeCommand(UptimeTracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
            if (args.length == 0) {
                sendSummary(sender, "day");
                sendSummary(sender, "week");
                sendSummary(sender, "month");
                sendSummary(sender, "year");
                sendSummary(sender, "all");
                sendSummary(sender, "24h");
                sendSummary(sender, "7d");
                sendSummary(sender, "30d");
                sendSummary(sender, "365d");
                return true;
            }

            String period = args[0].toLowerCase(Locale.ROOT);
            if (period.equals("d") || period.equals("today")) period = "day";
            if (period.equals("w")) period = "week";
            if (period.equals("m")) period = "month";
            if (period.equals("y")) period = "year";
            if (period.equals("a")) period = "all";
            if (period.equals("24") || period.equals("1d") || period.equals("past24") || period.equals("pastday")) period = "24h";
            if (period.equals("7") || period.equals("1w") || period.equals("past7") || period.equals("pastweek") || period.equals("1week")) period = "7d";
            if (period.equals("30") || period.equals("1m") || period.equals("past30") || period.equals("pastmonth") || period.equals("1month")) period = "30d";
            if (period.equals("365") || period.equals("1y") || period.equals("past365") || period.equals("pastyear") || period.equals("1year")) period = "365d";

            if (period.equals("help")) {
                sender.sendMessage("/uptime [day|week|month|year|all|24h|7d|30d|365d] - show uptime; no arg = show lots of ranges");
                return true;
            }

            sendSummary(sender, period);
            return true;
        }

        private void sendSummary(CommandSender sender, String period) {
            UptimeSummary s = tracker.getSummary(period);
            if (s == null) {
                sender.sendMessage("§cUnknown period. Use day/week/month/year/all or 24h/7d/30d/365d");
                return;
            }
            String percentStr = String.format(Locale.ROOT, "%.2f", s.percent);
            sender.sendMessage("§6Uptime — " + s.label + ":");
            sender.sendMessage(" §7Up: §a" + formatSeconds(s.uptimeSeconds)
                    + " §7/ Since start: §a" + formatSeconds(s.possibleSeconds)
                    + " §7(" + percentStr + "%)");
        }
    }
}