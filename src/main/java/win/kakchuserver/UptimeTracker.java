package win.kakchuserver;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * UptimeTracker - hardened implementation:
 * - Atomic writes only (tmp + move) + rolling backups
 * - Recovery from tmp / backups if main file is missing/empty/invalid
 * - Persist heartbeat (runningSince) together with interval end updates
 * - Coalesced async saves (latest snapshot wins) to prevent backlog

 * Config keys:
 * uptime:
 *   flush-interval-seconds: 1
 *   retention-days: 370   # -1 = keep forever
 *   merge-gap-ms: 1000
 */
public class UptimeTracker {
    private final JavaPlugin plugin;
    private final File dataFile;

    private final Path bak1;
    private final Path bak2;
    private final Path bak3;

    private YamlConfiguration cfg;

    // keys in the YAML
    private static final String KEY_RUNNING_SINCE = "runningSince";        // heartbeat / lastSeen epoch millis
    private static final String KEY_ALLTIME_SINCE = "alltime.trackedSince"; // epoch millis
    private static final String KEY_INTERVALS = "intervals";               // list of maps {start: <ms>, end: <ms>}

    // runtime state (last seen timestamp while running; -1 when stopped)
    private volatile long lastSeenMs = -1L;
    private int flushTaskId = -1;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closing = new AtomicBoolean(false);

    // Single-threaded executor for async writes (coalesced)
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Kakchu-UptimeSave");
        t.setDaemon(true);
        return t;
    });

    // Coalescing saver state
    private final AtomicBoolean saveScheduled = new AtomicBoolean(false);
    private final AtomicLong saveSeq = new AtomicLong(0);
    private final AtomicLong saveWrittenSeq = new AtomicLong(0);
    private final AtomicReference<String> latestYaml = new AtomicReference<>("");

    // defaults (overridable via config)
    private static final int DEFAULT_FLUSH_SECONDS = 1;
    private static final int DEFAULT_RETENTION_DAYS = 370;
    private static final long DEFAULT_MERGE_GAP_MS = 1000L;

    public UptimeTracker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "uptime.yml");

        Path parent = plugin.getDataFolder().toPath();
        this.bak1 = parent.resolve("uptime.yml.bak1");
        this.bak2 = parent.resolve("uptime.yml.bak2");
        this.bak3 = parent.resolve("uptime.yml.bak3");

        File dataDir = plugin.getDataFolder();
        if (!dataDir.exists()) {
            boolean ok = dataDir.mkdirs();
            if (!ok) {
                plugin.getLogger().warning("Could not create plugin data folder: " + dataDir.getAbsolutePath());
            }
        }

        // If the main file is missing/empty, try to recover from a leftover tmp file first.
        recoverFromTempIfNeeded();

        // Cleanup old leftover temp files (hygiene).
        cleanupLeftoverTempFiles();

        loadConfigRobust();
    }

    /**
     * If uptime.yml is missing or 0 bytes, try to recover from the newest uptime.yml.*.tmp in the same directory.
     */
    private void recoverFromTempIfNeeded() {
        try {
            Path parent = dataFile.toPath().getParent();
            if (parent == null) return;

            boolean mainMissingOrEmpty = !dataFile.exists() || Files.size(dataFile.toPath()) == 0;
            if (!mainMissingOrEmpty) return;

            List<Path> candidates = new ArrayList<>();
            DirectoryStream.Filter<Path> filter = entry -> {
                String name = entry.getFileName().toString();
                return name.startsWith(dataFile.getName() + ".") && name.endsWith(".tmp");
            };

            try (DirectoryStream<Path> ds = Files.newDirectoryStream(parent, filter)) {
                for (Path p : ds) candidates.add(p);
            }

            if (candidates.isEmpty()) return;

            candidates.sort((a, b) -> {
                try {
                    return Long.compare(Files.getLastModifiedTime(b).toMillis(), Files.getLastModifiedTime(a).toMillis());
                } catch (Exception e) {
                    return 0;
                }
            });

            for (Path tmp : candidates) {
                try {
                    YamlConfiguration test = new YamlConfiguration();
                    test.load(tmp.toFile());
                    if (isConfigSane(test)) {
                        // Move tmp into place as the main file
                        try {
                            Files.move(tmp, dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
                            Files.move(tmp, dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                        plugin.getLogger().warning("[Uptime] Recovered uptime.yml from temp file: " + tmp.getFileName());
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
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
                        // delete files older than 5 minutes (more conservative)
                        if (ageMs > Duration.ofMinutes(5).toMillis()) {
                            Files.deleteIfExists(p);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Robust load:
     * - Load main file; if invalid, try restore from backups; if still invalid, quarantine corrupt file and start fresh.
     * - Normalize/repair intervals and persist if changes are needed.
     */
    private void loadConfigRobust() {
        // Ensure parent directory exists
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
        } catch (Exception ignored) {}

        if (!dataFile.exists()) {
            cfg = new YamlConfiguration();
            cfg.set(KEY_INTERVALS, new ArrayList<Map<String, Object>>());
            cfg.set(KEY_ALLTIME_SINCE, System.currentTimeMillis());
            saveConfigAtomicSync();
            return;
        }

        YamlConfiguration loaded = tryLoadYaml(dataFile);
        if (!isConfigSane(loaded)) {
            plugin.getLogger().warning("[Uptime] uptime.yml is missing/invalid. Attempting restore from backups...");
            if (restoreFromBackups()) {
                loaded = tryLoadYaml(dataFile);
            }
        }

        if (!isConfigSane(loaded)) {
            // Quarantine corrupt file so we don't destroy evidence, then start fresh.
            quarantineCorruptMain();
            cfg = new YamlConfiguration();
            cfg.set(KEY_INTERVALS, new ArrayList<Map<String, Object>>());
            cfg.set(KEY_ALLTIME_SINCE, System.currentTimeMillis());
            saveConfigAtomicSync();
            return;
        }

        cfg = loaded;

        // Repair/normalize (merge, trim, fix bad entries)
        boolean changed = repairNormalizeInMemory();
        if (changed) {
            saveConfigAtomicSync();
        }
    }

    private YamlConfiguration tryLoadYaml(File file) {
        try {
            YamlConfiguration yc = new YamlConfiguration();
            yc.load(file);
            return yc;
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.WARNING, "[Uptime] Failed to load YAML: " + file.getName(), e);
            return null;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Uptime] Unexpected error loading YAML: " + file.getName(), e);
            return null;
        }
    }

    private boolean isConfigSane(YamlConfiguration yc) {
        if (yc == null) return false;

        Object raw = yc.get(KEY_INTERVALS);
        if (raw != null && !(raw instanceof List<?>)) return false;

        List<?> list = yc.getList(KEY_INTERVALS);
        if (list != null) {
            for (Object el : list) {
                if (!(el instanceof Map<?, ?>)) return false;
            }
        }

        // trackedSince should exist and be a plausible epoch ms (allow 0 if older files)
        long since = yc.getLong(KEY_ALLTIME_SINCE, 0L);
        return since >= 0;
    }

    private void quarantineCorruptMain() {
        try {
            Path parent = dataFile.toPath().getParent();
            if (parent == null) return;

            String ts = String.valueOf(System.currentTimeMillis());
            Path quarantined = parent.resolve("uptime.yml.corrupt-" + ts);
            Files.move(dataFile.toPath(), quarantined, StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("[Uptime] Quarantined corrupt uptime.yml to: " + quarantined.getFileName());
        } catch (Exception ignored) {
        }
    }

    private boolean restoreFromBackups() {
        List<Path> backups = Arrays.asList(bak1, bak2, bak3);
        for (Path b : backups) {
            try {
                if (!Files.exists(b) || Files.size(b) == 0) continue;
                YamlConfiguration test = tryLoadYaml(b.toFile());
                if (isConfigSane(test)) {
                    // Copy backup into main using temp+move (atomic-ish)
                    String content = Files.readString(b, StandardCharsets.UTF_8);
                    writeAtomicYamlSnapshot(content);
                    plugin.getLogger().warning("[Uptime] Restored uptime.yml from backup: " + b.getFileName());
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    // ------------------------- Saving (atomic only) -------------------------

    private synchronized void saveConfigAtomicSync() {
        try {
            String yaml = cfg.saveToString();
            writeAtomicYamlSnapshot(yaml);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Uptime] Failed to create/save YAML snapshot synchronously", e);
        }
    }

    /**
     * Coalesced async save:
     * - snapshot cfg under lock
     * - if closing/disabled -> sync atomic write
     * - else schedule one writer that always writes the latest snapshot
     */
    private void saveConfigAsync() {
        final String yamlDump;
        synchronized (this) {
            try {
                yamlDump = cfg.saveToString();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[Uptime] Failed to create YAML snapshot for async save", e);
                return;
            }
        }

        if (closing.get() || !plugin.isEnabled()) {
            try {
                writeAtomicYamlSnapshot(yamlDump);
            } catch (IOException io) {
                plugin.getLogger().log(Level.WARNING, "[Uptime] Failed to write uptime.yml synchronously (closing/disabled)", io);
            }
            return;
        }

        latestYaml.set(yamlDump);
        saveSeq.incrementAndGet();

        if (!saveScheduled.compareAndSet(false, true)) {
            return; // a writer is already scheduled/running; it will pick up the latestYaml
        }

        try {
            saveExecutor.submit(this::runCoalescedWriter);
        } catch (RejectedExecutionException ex) {
            saveScheduled.set(false);
            plugin.getLogger().log(Level.FINER, "[Uptime] Save executor rejected async save; falling back to sync write.", ex);
            try {
                writeAtomicYamlSnapshot(yamlDump);
            } catch (IOException io) {
                plugin.getLogger().log(Level.WARNING, "[Uptime] Failed to write uptime.yml synchronously after rejection", io);
            }
        }
    }

    private void runCoalescedWriter() {
        try {
            while (true) {
                long target = saveSeq.get();
                String yaml = latestYaml.get();

                try {
                    writeAtomicYamlSnapshot(yaml);
                } catch (IOException io) {
                    plugin.getLogger().log(Level.WARNING, "[Uptime] Failed to write uptime.yml asynchronously", io);
                }

                saveWrittenSeq.set(target);

                // If no newer snapshot requested during our write, we're done.
                if (saveSeq.get() == target) break;
            }
        } finally {
            saveScheduled.set(false);

            // Handle race: a new save could have been requested after we set saveScheduled=false.
            if (!closing.get() && plugin.isEnabled() && saveWrittenSeq.get() != saveSeq.get()) {
                if (saveScheduled.compareAndSet(false, true)) {
                    try {
                        saveExecutor.submit(this::runCoalescedWriter);
                    } catch (RejectedExecutionException ignored) {
                        saveScheduled.set(false);
                    }
                }
            }
        }
    }

    /**
     * Write the YAML snapshot to a tmp file and move into place (atomic where supported).
     * Also rotates backups (best-effort) before replacing the main file.
     */
    private void writeAtomicYamlSnapshot(String yamlDump) throws IOException {
        Path parent = dataFile.toPath().getParent();
        if (parent == null) parent = plugin.getDataFolder().toPath();
        Files.createDirectories(parent);

        // Create a unique temp file in same directory.
        Path tmp = Files.createTempFile(parent, dataFile.getName() + ".", ".tmp");

        // Write tmp (UTF-8) + force to disk.
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(
                        Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                        StandardCharsets.UTF_8))) {
            out.write(yamlDump);
            out.flush();
        } catch (IOException writeEx) {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            throw writeEx;
        }

        // fsync temp file
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        } catch (Exception ignored) {
        }

        if (!Files.exists(tmp) || Files.size(tmp) == 0) {
            throw new IOException("Temporary file missing/empty after write: " + tmp);
        }

        // Rotate backups best-effort (do not fail the save if backups fail)
        rotateBackupsBestEffort();

        // Move into place
        try {
            Files.move(tmp, dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
            Files.move(tmp, dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            throw ex;
        }
    }

    private void rotateBackupsBestEffort() {
        try {
            Path main = dataFile.toPath();
            if (!Files.exists(main) || Files.size(main) == 0) return;

            // bak3 <- bak2
            try {
                if (Files.exists(bak3)) Files.deleteIfExists(bak3);
                if (Files.exists(bak2)) Files.move(bak2, bak3, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {}

            // bak2 <- bak1
            try {
                if (Files.exists(bak2)) Files.deleteIfExists(bak2);
                if (Files.exists(bak1)) Files.move(bak1, bak2, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {}

            // bak1 <- main (copy)
            try {
                Files.copy(main, bak1, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {
        }
    }

    // ------------------------- Interval helpers -------------------------

    @SuppressWarnings("unchecked")
    private synchronized List<Map<String, Object>> getIntervalsMutable() {
        List<Map<String, Object>> list = (List<Map<String, Object>>) (List<?>) cfg.getMapList(KEY_INTERVALS);
        return new ArrayList<>(list);
    }

    /**
     * Normalize/repair intervals in cfg (in-memory) and return whether changes were applied.
     */
    private synchronized boolean repairNormalizeInMemory() {
        boolean changed = false;

        if (!cfg.contains(KEY_INTERVALS) || !(cfg.get(KEY_INTERVALS) instanceof List)) {
            cfg.set(KEY_INTERVALS, new ArrayList<Map<String, Object>>());
            changed = true;
        }

        long now = System.currentTimeMillis();

        // Parse intervals, drop malformed, normalize end>=start.
        List<Map<String, Object>> raw = getIntervalsMutable();
        List<Interval> parsed = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            if (m == null) { changed = true; continue; }
            long s = toLong(m.get("start"));
            long e = toLong(m.get("end"));
            if (s < 0) { changed = true; continue; }
            if (e < s) { e = s; changed = true; }
            parsed.add(new Interval(s, e));
        }

        // Crash recovery using persisted heartbeat (runningSince)
        // If server crashed, KEY_RUNNING_SINCE likely remains set. Ensure last interval end >= that heartbeat.
        long heartbeat = cfg.getLong(KEY_RUNNING_SINCE, -1L);
        if (heartbeat > 0 && heartbeat <= now && !parsed.isEmpty()) {
            parsed.sort(Comparator.comparingLong(i -> i.start));
            Interval last = parsed.getLast();
            if (heartbeat >= last.start && heartbeat > last.end) {
                last.end = heartbeat;
                changed = true;
            }
        }

        // Merge + trim
        int retentionDays = getRetentionDays();
        final long minKeepEnd;
        if (retentionDays < 0) {
            minKeepEnd = Long.MIN_VALUE;
        } else {
            long retentionMs = Duration.ofDays(Math.max(1, retentionDays)).toMillis();
            minKeepEnd = now - retentionMs;
        }
        long mergeGapMs = getMergeGapMs();

        parsed.sort(Comparator.comparingLong(i -> i.start));
        List<Interval> out = new ArrayList<>();
        for (Interval iv : parsed) {
            if (minKeepEnd != Long.MIN_VALUE && iv.end < minKeepEnd) {
                changed = true;
                continue;
            }
            if (out.isEmpty()) {
                out.add(iv);
            } else {
                Interval last = out.getLast();
                if (iv.start <= last.end || iv.start <= last.end + mergeGapMs) {
                    long newEnd = Math.max(last.end, iv.end);
                    if (newEnd != last.end) changed = true;
                    last.end = newEnd;
                } else {
                    out.add(iv);
                }
            }
        }

        // Ensure trackedSince exists
        if (!cfg.contains(KEY_ALLTIME_SINCE)) {
            // Prefer the earliest interval start if available
            long since = out.isEmpty() ? now : out.getFirst().start;
            cfg.set(KEY_ALLTIME_SINCE, since);
            changed = true;
        } else {
            long since = cfg.getLong(KEY_ALLTIME_SINCE, now);
            if (since < 0 || since > now) {
                long fixed = out.isEmpty() ? now : out.getFirst().start;
                cfg.set(KEY_ALLTIME_SINCE, fixed);
                changed = true;
            }
        }

        // Write back normalized list
        List<Map<String, Object>> saveList = new ArrayList<>(out.size());
        for (Interval iv : out) {
            Map<String, Object> m = new HashMap<>();
            m.put("start", iv.start);
            m.put("end", iv.end);
            saveList.add(m);
        }

        // Only set if different sizes or if we changed anything (simple + safe)
        if (changed || saveList.size() != raw.size()) {
            cfg.set(KEY_INTERVALS, saveList);
            changed = true;
        }

        return changed;
    }

    private synchronized void appendInterval(long startMs, long endMs) {
        if (endMs < startMs) endMs = startMs;

        List<Map<String, Object>> intervals = getIntervalsMutable();
        Map<String, Object> entry = new HashMap<>();
        entry.put("start", startMs);
        entry.put("end", endMs);
        intervals.add(entry);

        cfg.set(KEY_INTERVALS, intervals);

        // Normalize/merge/trim after append (may or may not change anything beyond the append)
        repairNormalizeInMemory();

        // Always save because the append itself changed the data
        saveConfigAsync();
    }

    /**
     * Update last interval end in-memory (no save).
     */
    private synchronized void updateLastIntervalEndInMemory(long endMs) {
        List<Map<String, Object>> intervals = getIntervalsMutable();
        if (intervals.isEmpty()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("start", endMs);
            entry.put("end", endMs);
            intervals.add(entry);
            cfg.set(KEY_INTERVALS, intervals);
            return;
        }

        Map<String, Object> last = intervals.getLast();
        if (last == null) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("start", endMs);
            entry.put("end", endMs);
            intervals.add(entry);
            cfg.set(KEY_INTERVALS, intervals);
            return;
        }

        last.put("end", endMs);
        cfg.set(KEY_INTERVALS, intervals);
    }

    // ------------------------- Lifecycle -------------------------

    public synchronized void start() {
        if (!started.compareAndSet(false, true)) return;
        closing.set(false);

        long now = System.currentTimeMillis();

        // Repair/normalize on start (also applies crash recovery using persisted heartbeat).
        boolean changed = repairNormalizeInMemory();
        if (changed) saveConfigAsync();

        // Append new interval for this run (end starts == start; will be extended by flushes).
        appendInterval(now, now);

        // Start heartbeat/lastSeen and persist it immediately.
        lastSeenMs = now;
        cfg.set(KEY_RUNNING_SINCE, lastSeenMs);
        saveConfigAsync();

        // Schedule periodic flush
        int flushIntervalSeconds = getFlushIntervalSeconds();
        if (flushIntervalSeconds < 1) flushIntervalSeconds = 1;
        long ticks = 20L * flushIntervalSeconds;

        flushTaskId = plugin.getServer().getScheduler()
                .scheduleSyncRepeatingTask(plugin, this::periodicFlush, ticks, ticks);
    }

    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) return;
        closing.set(true);

        // cancel scheduled task if present
        if (flushTaskId != -1) {
            try {
                plugin.getServer().getScheduler().cancelTask(flushTaskId);
            } catch (Exception ignored) {
            }
            flushTaskId = -1;
        }

        long now = System.currentTimeMillis();

        // Finalize last interval end + clear heartbeat
        updateLastIntervalEndInMemory(now);
        lastSeenMs = -1L;
        cfg.set(KEY_RUNNING_SINCE, null);

        // Normalize/merge/trim one last time (in-memory)
        repairNormalizeInMemory();

        // Final synchronous atomic save (no non-atomic saves)
        saveConfigAtomicSync();

        // Stop executor and prevent old snapshots from overwriting final state
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            saveExecutor.shutdownNow();
        }
    }

    private synchronized void periodicFlush() {
        if (lastSeenMs <= 0) return;

        long now = System.currentTimeMillis();
        if (now <= lastSeenMs) return;

        // Update interval end + heartbeat and save together.
        updateLastIntervalEndInMemory(now);
        lastSeenMs = now;
        cfg.set(KEY_RUNNING_SINCE, lastSeenMs);

        saveConfigAsync();
    }

    // ------------------------- Querying -------------------------

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
     * period: "day", "week", "month", "year", "all"
     * rolling: "24h", "7d", "30d", "365d"
     */
    public synchronized UptimeSummary getSummary(String period) {
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime nowZ = Instant.ofEpochMilli(now).atZone(zone);

        long uptimeSeconds;
        long periodPossibleSeconds;
        String label;

        switch (period.toLowerCase(Locale.ROOT)) {
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
     * Compute overlap seconds between [windowStartMs, nowMs) and stored intervals + live time since last flush.
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

        // Add live uptime since last persisted end (lastSeenMs == last flush time).
        long ls = lastSeenMs;
        if (ls > 0 && ls < nowMs) {
            long overlapStart = Math.max(ls, windowStartMs);
            if (overlapStart < nowMs) {
                total += (nowMs - overlapStart) / 1000L;
            }
        }

        return total;
    }

    // ------------------------- Utilities -------------------------

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

    private int getFlushIntervalSeconds() {
        return getConfigInt("uptime.flush-interval-seconds", DEFAULT_FLUSH_SECONDS);
    }

    private int getRetentionDays() {
        return getConfigInt("uptime.retention-days", DEFAULT_RETENTION_DAYS);
    }

    private long getMergeGapMs() {
        try {
            ConfigurationSection uptime = plugin.getConfig().getConfigurationSection("uptime");
            if (uptime == null) return DEFAULT_MERGE_GAP_MS;
            return uptime.getLong("merge-gap-ms", DEFAULT_MERGE_GAP_MS);
        } catch (Exception ignored) {
            return DEFAULT_MERGE_GAP_MS;
        }
    }

    private static class Interval {
        long start;
        long end;

        Interval(long s, long e) {
            this.start = s;
            this.end = e;
        }
    }

    // ------------------------- Summary + command -------------------------

    public record UptimeSummary(String label, long uptimeSeconds, long possibleSeconds, double percent,
                                Instant trackedSince) {
    }

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
                sender.sendMessage("/uptime [day|week|month|year|all|24h|7d|30d|365d]");
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