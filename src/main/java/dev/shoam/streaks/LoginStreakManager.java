package win.shamserver.streaks;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoginStreakManager {

    private static final ZoneOffset GRACE_ZONE = ZoneOffset.UTC;
    private static final long MILLIS_THRESHOLD = 1_000_000_000_000L;
    private static final DateTimeFormatter GRACE_TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");
    private static final Pattern DURATION_TOKEN = Pattern.compile("(\\d+)\\s*([smhdw])", Pattern.CASE_INSENSITIVE);
    private static final String GRACE_PERMISSION_PREFIX = "shamplugin.graces.";

    private final JavaPlugin plugin;
    private final ExecutorService dbExecutor;
    private final Map<UUID, PlayerStreak> cache = new HashMap<>();
    private final GraceOverrideResolver graceOverrideResolver;

    private Connection connection;
    private List<PlayerStreak> cachedTopCurrent = List.of();
    private List<PlayerStreak> cachedTopHighest = List.of();
    private long lastLeaderboardUpdate = 0L;
    private volatile boolean closed = false;

    public record StreakStatus(
            int current,
            int highest,
            int availableGraces,
            int maxGraces,
            Duration timeUntilReset
    ) {
    }

    public record ClaimResult(
            int rewardStreak,
            int currentStreak,
            int highestStreak,
            boolean streakIncremented
    ) {
    }

    private record Projection(
            int current,
            int highest,
            int graceUsed,
            int graceCycle,
            int availableGraces,
            boolean firstClaim,
            boolean expired,
            boolean pauseProtected,
            Duration timeUntilReset
    ) {
    }

    public LoginStreakManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Sham-StreakDb");
            thread.setDaemon(true);
            return thread;
        });
        this.graceOverrideResolver = createGraceOverrideResolver();
    }

    public boolean init() {
        try {
            return runOnDbThread(() -> {
                connection = DriverManager.getConnection("jdbc:sqlite:" + plugin.getDataFolder() + "/streaks.db");

                try (Statement stmt = connection.createStatement()) {
                    stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS streaks (
                        uuid TEXT PRIMARY KEY,
                        username TEXT,
                        current_streak INTEGER,
                        highest_streak INTEGER,
                        last_claim BIGINT,
                        grace_used INTEGER,
                        grace_week INTEGER
                        )
                    """);
                }

                return true;
            }).join();
        } catch (CompletionException ex) {
            plugin.getLogger().severe("Failed to initialize streak database: " + ex.getCause().getMessage());
            plugin.getLogger().throwing(getClass().getName(), "init", ex.getCause());
            return false;
        }
    }

    public void shutdown() {
        if (closed) {
            return;
        }

        try {
            CompletableFuture.runAsync(this::closeConnectionQuietly, dbExecutor).join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            plugin.getLogger().warning("Failed to close streak database cleanly: " + cause.getMessage());
        } finally {
            closed = true;
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    dbExecutor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                dbExecutor.shutdownNow();
            }
        }
    }

    public CompletableFuture<StreakStatus> getStatusAsync(UUID uuid, String username) {
        return getStatusAsync(uuid, username, null);
    }

    public CompletableFuture<StreakStatus> getStatusAsync(UUID uuid, String username, Player player) {
        return resolveMaxGracePerCycleAsync(uuid, username, player)
                .thenCompose(maxGracePerCycle -> runOnDbThread(() -> {
                    PlayerStreak streak = getOrLoad(uuid, username);
                    Projection projection = project(streak, currentEpochSecond(), maxGracePerCycle);

                    return new StreakStatus(
                            projection.current(),
                            projection.highest(),
                            projection.availableGraces(),
                            maxGracePerCycle,
                            projection.timeUntilReset()
                    );
                }));
    }

    public CompletableFuture<StreakStatus> getExistingStatusAsync(PlayerStreak streak, Player player) {
        PlayerStreak snapshot = streak.copy();
        return resolveMaxGracePerCycleAsync(snapshot.uuid, snapshot.username, player)
                .thenApply(maxGracePerCycle -> {
                    Projection projection = project(snapshot, currentEpochSecond(), maxGracePerCycle);

                    return new StreakStatus(
                            projection.current(),
                            projection.highest(),
                            projection.availableGraces(),
                            maxGracePerCycle,
                            projection.timeUntilReset()
                    );
                });
    }

    public CompletableFuture<Integer> getCurrentStreakAsync(UUID uuid, String username) {
        return getCurrentStreakAsync(uuid, username, null);
    }

    public CompletableFuture<Integer> getCurrentStreakAsync(UUID uuid, String username, Player player) {
        return getStatusAsync(uuid, username, player).thenApply(StreakStatus::current);
    }

    public CompletableFuture<ClaimResult> processClaimAsync(UUID uuid, String username, boolean countsForStreak) {
        return processClaimAsync(uuid, username, countsForStreak, null);
    }

    public CompletableFuture<ClaimResult> processClaimAsync(UUID uuid, String username, boolean countsForStreak, Player player) {
        return resolveMaxGracePerCycleAsync(uuid, username, player)
                .thenCompose(maxGracePerCycle -> runOnDbThread(() -> processClaim(uuid, username, countsForStreak, maxGracePerCycle)));
    }

    public CompletableFuture<List<PlayerStreak>> getTopCurrentAsync(int limit) {
        return runOnDbThread(() -> {
            updateLeaderboardsIfNeeded();
            return copyLeaderboard(cachedTopCurrent, limit);
        });
    }

    public CompletableFuture<List<PlayerStreak>> getTopHighestAsync(int limit) {
        return runOnDbThread(() -> {
            updateLeaderboardsIfNeeded();
            return copyLeaderboard(cachedTopHighest, limit);
        });
    }

    public CompletableFuture<PlayerStreak> findStreakByUuidAsync(UUID uuid) {
        return runOnDbThread(() -> {
            PlayerStreak streak = findByUuid(uuid);
            return streak == null ? null : streak.copy();
        });
    }

    public CompletableFuture<PlayerStreak> findStreakByUsernameAsync(String username) {
        return runOnDbThread(() -> {
            PlayerStreak streak = findByUsername(username);
            return streak == null ? null : streak.copy();
        });
    }

    public CompletableFuture<PlayerStreak> setStreakAsync(UUID uuid, String username, int value) {
        return runOnDbThread(() -> {
            PlayerStreak streak = getOrLoad(uuid, username);
            long now = currentEpochSecond();
            int currentGraceCycle = getCurrentGraceCycleKey(now);

            streak.username = username;
            streak.current = value;
            if (value > streak.highest) {
                streak.highest = value;
            }

            if (value <= 0) {
                streak.current = 0;
                streak.lastClaim = 0;
            } else {
                streak.lastClaim = now;
            }
            streak.graceUsed = 0;
            streak.graceWeek = currentGraceCycle;

            saveInternal(streak);
            return streak.copy();
        });
    }

    public int getMaxGracePerCycle() {
        String axPath = "axrewards.login-streaks.default_grace_per_cycle";
        String oldAxPath = "axrewards.login-streaks.grace_per_cycle";
        String oldAxWeekPath = "axrewards.login-streaks.grace_per_week";
        String legacyPath = "login_streaks.default_grace_per_cycle";
        String oldLegacyPath = "login_streaks.grace_per_cycle";
        String oldLegacyWeekPath = "login_streaks.grace_per_week";

        if (plugin.getConfig().contains(axPath)) {
            return Math.max(0, plugin.getConfig().getInt(axPath));
        }
        if (plugin.getConfig().contains(oldAxPath)) {
            return Math.max(0, plugin.getConfig().getInt(oldAxPath));
        }
        if (plugin.getConfig().contains(oldAxWeekPath)) {
            return Math.max(0, plugin.getConfig().getInt(oldAxWeekPath));
        }
        if (plugin.getConfig().contains(legacyPath)) {
            return Math.max(0, plugin.getConfig().getInt(legacyPath));
        }
        if (plugin.getConfig().contains(oldLegacyPath)) {
            return Math.max(0, plugin.getConfig().getInt(oldLegacyPath));
        }
        return Math.max(0, plugin.getConfig().getInt(oldLegacyWeekPath, 2));
    }

    public int getMaxGracePerCycle(Player player) {
        int defaultGrace = getMaxGracePerCycle();
        if (player == null) {
            return defaultGrace;
        }

        int permissionOverride = resolvePermissionGraceOverride(player);
        return permissionOverride >= 0 ? permissionOverride : defaultGrace;
    }

    private int resolvePermissionGraceOverride(Player player) {
        int override = Integer.MAX_VALUE;

        for (PermissionAttachmentInfo permissionInfo : player.getEffectivePermissions()) {
            if (!permissionInfo.getValue()) {
                continue;
            }

            String permission = permissionInfo.getPermission();
            if (permission == null) {
                continue;
            }

            String normalized = permission.toLowerCase(Locale.ROOT);
            if (!normalized.startsWith(GRACE_PERMISSION_PREFIX)) {
                continue;
            }

            String suffix = normalized.substring(GRACE_PERMISSION_PREFIX.length());
            try {
                int parsed = Integer.parseInt(suffix);
                if (parsed >= 0) {
                    override = Math.min(override, parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        return override == Integer.MAX_VALUE ? -1 : override;
    }

    private GraceOverrideResolver createGraceOverrideResolver() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            return null;
        }

        try {
            GraceOverrideResolver resolver = LuckPermsGraceOverrideResolver.tryCreate(plugin.getServer());
            if (resolver != null) {
                plugin.getLogger().info("LuckPerms grace override support enabled.");
            }
            return resolver;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to hook LuckPerms grace override support: " + throwable.getMessage());
            return null;
        }
    }

    private CompletableFuture<Integer> resolveMaxGracePerCycleAsync(UUID uuid, String username, Player player) {
        if (player != null) {
            return CompletableFuture.completedFuture(getMaxGracePerCycle(player));
        }

        int defaultGrace = getMaxGracePerCycle();
        if (graceOverrideResolver == null) {
            return CompletableFuture.completedFuture(defaultGrace);
        }

        return graceOverrideResolver.resolveGraceOverride(uuid, username)
                .handle((override, throwable) -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("Failed to resolve grace override for " + username + ": " + throwable.getMessage());
                        return defaultGrace;
                    }

                    return override != null && override >= 0 ? override : defaultGrace;
                });
    }

    private int resolveMaxGracePerCycleBlocking(UUID uuid, String username) {
        int defaultGrace = getMaxGracePerCycle();

        try {
            return resolveMaxGracePerCycleAsync(uuid, username, null).join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            plugin.getLogger().warning("Failed to resolve leaderboard grace override for " + username + ": " + cause.getMessage());
            return defaultGrace;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to resolve leaderboard grace override for " + username + ": " + ex.getMessage());
            return defaultGrace;
        }
    }

    public Duration getTimeUntilGraceReset() {
        long now = currentEpochSecond();
        long cycleSeconds = getGraceCycleSeconds();
        long anchorSeconds = getGraceResetAnchorEpochSecond();
        long cycleIndex = Math.floorDiv(now - anchorSeconds, cycleSeconds);
        long nextReset = anchorSeconds + ((cycleIndex + 1L) * cycleSeconds);
        return Duration.ofSeconds(Math.max(0L, nextReset - now));
    }

    private ClaimResult processClaim(UUID uuid, String username, boolean countsForStreak, int maxGracePerCycle) throws Exception {
        PlayerStreak streak = getOrLoad(uuid, username);
        long now = currentEpochSecond();
        Projection projection = project(streak, now, maxGracePerCycle);
        int rewardStreak = projection.current();

        if (!countsForStreak) {
            return new ClaimResult(rewardStreak, projection.current(), projection.highest(), false);
        }

        streak.username = username;
        streak.graceWeek = projection.graceCycle();

        if (projection.firstClaim()) {
            streak.current = 1;
            streak.highest = Math.max(streak.highest, 1);
            streak.lastClaim = now;
            streak.graceUsed = 0;
            saveInternal(streak);
            return new ClaimResult(0, streak.current, streak.highest, true);
        }

        if (projection.expired()) {
            streak.current = 1;
            streak.highest = Math.max(streak.highest, 1);
            streak.lastClaim = now;
            streak.graceUsed = 0;
            saveInternal(streak);
            return new ClaimResult(0, streak.current, streak.highest, true);
        }

        if (projection.pauseProtected()) {
            streak.current = projection.current();
            streak.highest = Math.max(streak.highest, projection.highest());
            streak.lastClaim = now;
            streak.graceUsed = projection.graceUsed();
            saveInternal(streak);
            return new ClaimResult(rewardStreak, streak.current, streak.highest, false);
        }

        streak.current = projection.current() + 1;
        streak.highest = Math.max(streak.highest, streak.current);
        streak.lastClaim = now;
        streak.graceUsed = projection.graceUsed();
        saveInternal(streak);

        return new ClaimResult(rewardStreak, streak.current, streak.highest, true);
    }

    private PlayerStreak getOrLoad(UUID uuid, String username) throws Exception {
        PlayerStreak cached = cache.get(uuid);
        if (cached != null) {
            if (!Objects.equals(cached.username, username)) {
                cached.username = username;
                saveInternal(cached);
            }
            return cached;
        }

        ensureConnection();

        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM streaks WHERE uuid=?")) {
            ps.setString(1, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long storedLastClaim = readStoredLastClaim(rs);
                    long normalizedLastClaim = normalizeEpochSeconds(storedLastClaim);

                    PlayerStreak loaded = new PlayerStreak(
                            uuid,
                            rs.getString("username"),
                            rs.getInt("current_streak"),
                            rs.getInt("highest_streak"),
                            normalizedLastClaim,
                            rs.getInt("grace_used"),
                            readStoredGraceCycleKey(rs)
                    );

                    cache.put(uuid, loaded);

                    if (normalizedLastClaim != storedLastClaim) {
                        saveInternal(loaded);
                    }

                    if (!Objects.equals(loaded.username, username)) {
                        loaded.username = username;
                        saveInternal(loaded);
                    }

                    return loaded;
                }
            }
        }

        PlayerStreak created = new PlayerStreak(
                uuid,
                username,
                0,
                0,
                0,
                0,
                getCurrentGraceCycleKey(currentEpochSecond())
        );
        cache.put(uuid, created);
        return created;
    }

    private PlayerStreak findByUuid(UUID uuid) throws Exception {
        PlayerStreak cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }

        ensureConnection();

        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM streaks WHERE uuid=?")) {
            ps.setString(1, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return loadFromResultSet(rs);
                }
            }
        }

        return null;
    }

    private PlayerStreak findByUsername(String username) throws Exception {
        if (username == null || username.isBlank()) {
            return null;
        }

        for (PlayerStreak cached : cache.values()) {
            if (cached.username != null && cached.username.equalsIgnoreCase(username)) {
                return cached;
            }
        }

        ensureConnection();

        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT * FROM streaks
            WHERE username COLLATE NOCASE = ?
            ORDER BY CASE WHEN username = ? THEN 0 ELSE 1 END
            LIMIT 1
        """)) {
            ps.setString(1, username);
            ps.setString(2, username);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return loadFromResultSet(rs);
                }
            }
        }

        return null;
    }

    private PlayerStreak loadFromResultSet(ResultSet rs) throws Exception {
        UUID uuid = UUID.fromString(rs.getString("uuid"));
        long storedLastClaim = readStoredLastClaim(rs);
        long normalizedLastClaim = normalizeEpochSeconds(storedLastClaim);

        PlayerStreak loaded = new PlayerStreak(
                uuid,
                rs.getString("username"),
                rs.getInt("current_streak"),
                rs.getInt("highest_streak"),
                normalizedLastClaim,
                rs.getInt("grace_used"),
                readStoredGraceCycleKey(rs)
        );

        cache.put(uuid, loaded);

        if (normalizedLastClaim != storedLastClaim) {
            saveInternal(loaded);
        }

        return loaded;
    }

    private void saveInternal(PlayerStreak streak) throws Exception {
        ensureConnection();

        streak.lastClaim = normalizeEpochSeconds(streak.lastClaim);

        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO streaks
            (uuid, username, current_streak, highest_streak, last_claim, grace_used, grace_week)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(uuid) DO UPDATE SET
                username = excluded.username,
                current_streak = excluded.current_streak,
                highest_streak = excluded.highest_streak,
                last_claim = excluded.last_claim,
                grace_used = excluded.grace_used,
                grace_week = excluded.grace_week
        """)) {
            ps.setString(1, streak.uuid.toString());
            ps.setString(2, streak.username);
            ps.setInt(3, streak.current);
            ps.setInt(4, streak.highest);
            ps.setLong(5, streak.lastClaim);
            ps.setInt(6, streak.graceUsed);
            ps.setInt(7, streak.graceWeek);
            ps.executeUpdate();
        }

        invalidateLeaderboardCache();
    }

    private Projection project(PlayerStreak streak, long now, int maxGrace) {
        int currentGraceCycle = getCurrentGraceCycleKey(now);
        int effectiveGraceUsed = streak.graceWeek == currentGraceCycle ? streak.graceUsed : 0;

        if (streak.lastClaim <= 0) {
            return new Projection(
                    0,
                    streak.highest,
                    0,
                    currentGraceCycle,
                    maxGrace,
                    true,
                    false,
                    false,
                    null
            );
        }

        long lastClaim = normalizeEpochSeconds(streak.lastClaim);
        long elapsed = Math.max(0L, now - lastClaim);
        long claimPeriodSeconds = getClaimPeriodSeconds();
        long missedWindows = Math.max(0L, (elapsed / claimPeriodSeconds) - 1L);
        int availableBefore = Math.max(0, maxGrace - effectiveGraceUsed);
        boolean pause = plugin.getConfig().getBoolean("axrewards.login-streaks.pause", false);

        if (missedWindows > availableBefore) {
            if (pause) {
                return new Projection(
                        streak.current,
                        streak.highest,
                        effectiveGraceUsed,
                        currentGraceCycle,
                        0,
                        false,
                        false,
                        true,
                        Duration.ZERO
                );
            }

            return new Projection(
                    0,
                    streak.highest,
                    maxGrace,
                    currentGraceCycle,
                    0,
                    false,
                    true,
                    false,
                    Duration.ZERO
            );
        }

        int consumedGrace = (int) missedWindows;
        int availableAfter = Math.max(0, availableBefore - consumedGrace);
        long resetWindow = ((long) availableBefore + 2L) * claimPeriodSeconds;
        Duration remaining = Duration.ofSeconds(Math.max(0L, resetWindow - elapsed));

        return new Projection(
                streak.current,
                streak.highest,
                effectiveGraceUsed + consumedGrace,
                currentGraceCycle,
                availableAfter,
                false,
                false,
                false,
                remaining
        );
    }

    private void updateLeaderboardsIfNeeded() throws Exception {
        long intervalMinutes = Math.max(0L, getConfigLongWithFallback("leaderboard_update_interval_minutes", 10L));
        long intervalMillis = intervalMinutes * 60_000L;
        long now = System.currentTimeMillis();

        if (intervalMillis > 0L && now - lastLeaderboardUpdate < intervalMillis) {
            return;
        }

        List<PlayerStreak> rows = loadAllRows();
        long nowEpoch = currentEpochSecond();

        List<PlayerStreak> projectedCurrent = new ArrayList<>();
        for (PlayerStreak row : rows) {
            try {
                int maxGracePerCycle = resolveMaxGracePerCycleBlocking(row.uuid, row.username);
                Projection projection = project(row, nowEpoch, maxGracePerCycle);
                if (projection.current() <= 0) {
                    continue;
                }

                PlayerStreak projected = row.copy();
                projected.current = projection.current();
                projected.graceUsed = projection.graceUsed();
                projected.graceWeek = projection.graceCycle();
                projectedCurrent.add(projected);
            } catch (Exception ex) {
                plugin.getLogger().warning("Skipping leaderboard entry for " + row.username + ": " + ex.getMessage());
            }
        }

        cachedTopCurrent = projectedCurrent.stream()
                .sorted(Comparator
                        .comparingInt((PlayerStreak streak) -> streak.current)
                        .reversed()
                        .thenComparing(streak -> streak.username == null ? "" : streak.username, String.CASE_INSENSITIVE_ORDER))
                .limit(100)
                .toList();

        cachedTopHighest = rows.stream()
                .map(PlayerStreak::copy)
                .sorted(Comparator
                        .comparingInt((PlayerStreak streak) -> streak.highest)
                        .reversed()
                        .thenComparing(streak -> streak.username == null ? "" : streak.username, String.CASE_INSENSITIVE_ORDER))
                .limit(100)
                .toList();

        lastLeaderboardUpdate = now;
    }

    private List<PlayerStreak> loadAllRows() throws Exception {
        ensureConnection();

        List<PlayerStreak> rows = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM streaks");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                rows.add(new PlayerStreak(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("username"),
                        rs.getInt("current_streak"),
                        rs.getInt("highest_streak"),
                        normalizeEpochSeconds(readStoredLastClaim(rs)),
                        rs.getInt("grace_used"),
                        readStoredGraceCycleKey(rs)
                ));
            }
        }

        return rows;
    }

    private List<PlayerStreak> copyLeaderboard(List<PlayerStreak> source, int limit) {
        return source.stream()
                .limit(Math.max(0, limit))
                .map(PlayerStreak::copy)
                .toList();
    }

    private void invalidateLeaderboardCache() {
        lastLeaderboardUpdate = 0L;
    }

    private int getCurrentGraceCycleKey(long nowEpochSecond) {
        long anchorSeconds = getGraceResetAnchorEpochSecond();
        long cycleSeconds = getGraceCycleSeconds();
        long cycleIndex = Math.floorDiv(nowEpochSecond - anchorSeconds, cycleSeconds);
        return Math.toIntExact(cycleIndex);
    }

    private long getGraceResetAnchorEpochSecond() {
        ZonedDateTime epoch = Instant.EPOCH.atZone(GRACE_ZONE);
        DayOfWeek resetDay = getConfiguredGraceResetDayOfWeek();
        LocalTime resetTime = getConfiguredGraceResetTime();

        ZonedDateTime anchor = epoch
                .with(TemporalAdjusters.nextOrSame(resetDay))
                .with(resetTime)
                .withSecond(0)
                .withNano(0);

        if (anchor.isBefore(epoch)) {
            anchor = anchor.plusWeeks(1);
        }

        return anchor.toEpochSecond();
    }

    private long getClaimPeriodSeconds() {
        return Math.max(1L, getDurationConfig("claim_period", Duration.ofDays(1)).toSeconds());
    }

    private long getGraceCycleSeconds() {
        return Math.max(1L, getDurationConfig("grace_reset_period", Duration.ofDays(7)).toSeconds());
    }

    private Duration getDurationConfig(String key, Duration def) {
        String raw = getConfigStringWithFallback(key, null);
        if (raw == null || raw.isBlank()) {
            return def;
        }

        Duration parsed = parseDuration(raw.trim());
        if (parsed == null || parsed.isZero() || parsed.isNegative()) {
            return def;
        }

        return parsed;
    }

    private Duration parseDuration(String raw) {
        try {
            return Duration.parse(raw.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
        }

        Matcher matcher = DURATION_TOKEN.matcher(raw);
        long totalSeconds = 0L;
        int end = 0;
        boolean matched = false;

        while (matcher.find()) {
            if (!raw.substring(end, matcher.start()).trim().isEmpty()) {
                return null;
            }

            long amount = Long.parseLong(matcher.group(1));
            char unit = Character.toLowerCase(matcher.group(2).charAt(0));

            switch (unit) {
                case 's' -> totalSeconds += amount;
                case 'm' -> totalSeconds += amount * 60L;
                case 'h' -> totalSeconds += amount * 3_600L;
                case 'd' -> totalSeconds += amount * 86_400L;
                case 'w' -> totalSeconds += amount * 604_800L;
                default -> {
                    return null;
                }
            }

            matched = true;
            end = matcher.end();
        }

        if (!matched || !raw.substring(end).trim().isEmpty()) {
            return null;
        }

        return Duration.ofSeconds(totalSeconds);
    }

    private DayOfWeek getConfiguredGraceResetDayOfWeek() {
        int rawDay = Math.max(0, Math.min(6, getConfigIntWithFallback("grace_reset_day", 0)));

        return switch (rawDay) {
            case 1 -> DayOfWeek.MONDAY;
            case 2 -> DayOfWeek.TUESDAY;
            case 3 -> DayOfWeek.WEDNESDAY;
            case 4 -> DayOfWeek.THURSDAY;
            case 5 -> DayOfWeek.FRIDAY;
            case 6 -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    private LocalTime getConfiguredGraceResetTime() {
        String raw = getConfigStringWithFallback("grace_reset_time", "0:00");

        try {
            return LocalTime.parse(raw.trim(), GRACE_TIME_FORMAT);
        } catch (DateTimeParseException ex) {
            return LocalTime.MIDNIGHT;
        }
    }

    private long normalizeEpochSeconds(long value) {
        if (value >= MILLIS_THRESHOLD) {
            return value / 1000L;
        }
        return value;
    }

    private long readStoredLastClaim(ResultSet rs) throws Exception {
        return rs.getLong("last_claim");
    }

    private int readStoredGraceCycleKey(ResultSet rs) throws Exception {
        int storedGraceWeek = rs.getInt("grace_week");

        if (storedGraceWeek <= 53 || storedGraceWeek >= 100000) {
            return getCurrentGraceCycleKey(currentEpochSecond());
        }

        return storedGraceWeek;
    }

    private int getConfigIntWithFallback(String key, int def) {
        String axPath = "axrewards.login-streaks." + key;
        String oldPath = "login_streaks." + key;
        if (plugin.getConfig().contains(axPath)) {
            return plugin.getConfig().getInt(axPath);
        }
        return plugin.getConfig().getInt(oldPath, def);
    }

    private long getConfigLongWithFallback(String key, long def) {
        String axPath = "axrewards.login-streaks." + key;
        String oldPath = "login_streaks." + key;
        if (plugin.getConfig().contains(axPath)) {
            return plugin.getConfig().getLong(axPath);
        }
        return plugin.getConfig().getLong(oldPath, def);
    }

    private String getConfigStringWithFallback(String key, String def) {
        String axPath = "axrewards.login-streaks." + key;
        String oldPath = "login_streaks." + key;
        if (plugin.getConfig().contains(axPath)) {
            return plugin.getConfig().getString(axPath, def);
        }
        return plugin.getConfig().getString(oldPath, def);
    }

    private long currentEpochSecond() {
        return Instant.now().getEpochSecond();
    }

    private void ensureConnection() {
        if (connection == null) {
            throw new IllegalStateException("Streak database is not initialized.");
        }
    }

    private void closeConnectionQuietly() {
        if (connection == null) {
            return;
        }

        try {
            connection.close();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to close streak database: " + ex.getMessage());
        } finally {
            connection = null;
        }
    }

    private <T> CompletableFuture<T> runOnDbThread(Callable<T> callable) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Streak manager is already closed."));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return callable.call();
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, dbExecutor);
    }

}
