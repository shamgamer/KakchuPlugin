package dev.shoam;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import dev.shoam.streaks.LoginStreakManager;
import dev.shoam.streaks.RewardCommand;
import dev.shoam.streaks.StreakCommands;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Manager extends JavaPlugin {

    private static Manager instance;
    private Alerts alertsHandler;
    private UptimeTracker tracker;
    private LoginStreakManager streakManager;

    private volatile String updateAvailableMessage;
    private volatile String updateAvailableVersion;

    public String getUpdateAvailableMessage() {
        return updateAvailableMessage;
    }

    public String getUpdateAvailableVersion() {
        return updateAvailableVersion;
    }

    public void setUpdateAvailable(String version, String message) {
        this.updateAvailableVersion = version;
        this.updateAvailableMessage = message;
    }

    public void clearUpdateAvailable() {
        this.updateAvailableVersion = null;
        this.updateAvailableMessage = null;
    }

    public boolean isDiscordAlertsEnabled() {
        return alertsHandler != null;
    }

    public Alerts getAlertsHandler() {
        return alertsHandler;
    }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        new VersionSupportChecker(this).logStartupReport();

        if (getConfig().getBoolean("discord alerts.enabled", true)) {
            enableDiscordAlertsEarly();
        }

        //  getLogger().info("✅ Sham Plugin enabled!");

        if (getConfig().getBoolean("update-checker.enabled", true)) {
            try {
                long hours = Math.max(1, getConfig().getLong("update-checker.interval-hours", 12));
                long periodTicks = hours * 60L * 60L * 20L;
                Bukkit.getScheduler().runTaskTimerAsynchronously(this, new UpdateChecker(this), 20L * 10L, periodTicks);
                Bukkit.getPluginManager().registerEvents(new UpdateLoginNotifier(this), this);
            } catch (Throwable t) {
                warnOptionalFeatureLoadFailure("❌ update checker ", t);
            }
        }

        if (getConfig().getBoolean("uptime.enabled", true)) {
            try {
                tracker = new UptimeTracker(this);
                tracker.start();
            getLogger().info("✅ UptimeTracker started.");
            } catch (Exception e) {
            getLogger().log(Level.SEVERE, "❌ Failed to start UptimeTracker: " + e.getMessage(), e);
                tracker = null;
            }
        }

        if (getConfig().getBoolean("map.enabled", true)) {
            registerCommand("map", new Commands());
        }
        if (getConfig().getBoolean("help.enabled", true)) {
            registerCommand("help", new Commands());
        }

        UptimeTracker.UptimeCommand uptimeCommand = null;
        if (tracker != null) {
            uptimeCommand = new UptimeTracker.UptimeCommand(tracker);
        }

        StreakCommands streakCommands = null;
        RewardCommand rewardCommand = null;

        long startDelay = getConfig().getLong("restarter.start-delay-ticks", 432000L);
        long interval = getConfig().getLong("restarter.interval-ticks", 10L);

        try {
            Bukkit.getScheduler().runTaskTimer(this, new Restarter(this), startDelay, interval);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "❌ Failed to schedule Restarter task: " + e.getMessage(), e);
        }

        if (getConfig().getBoolean("axrewards.login-streaks.enabled", true)) {
            streakManager = new LoginStreakManager(this);
            if (!streakManager.init()) {
                getLogger().severe("❌ Login streaks disabled because the streak database failed to initialize.");
                streakManager.shutdown();
                streakManager = null;
            } else {
                streakCommands = new StreakCommands(this, streakManager);
                rewardCommand = new RewardCommand(this, streakManager);

                Objects.requireNonNull(getCommand("streak"), "⚠️ Command 'streak' missing from plugin.yml").setExecutor(streakCommands);
                Objects.requireNonNull(getCommand("streaktop"), "⚠️ Command 'streaktop' missing from plugin.yml").setExecutor(streakCommands);
                Objects.requireNonNull(getCommand("higheststreaktop"), "⚠️ Command 'higheststreaktop' missing from plugin.yml").setExecutor(streakCommands);

                new PlayerNotifier(this, streakManager).register();
            }
        }

        ShamCommand shamCommand = new ShamCommand(uptimeCommand, streakCommands, rewardCommand);
        Objects.requireNonNull(getCommand("sham"), "⚠️ Command 'sham' missing from plugin.yml").setExecutor(shamCommand);
        Objects.requireNonNull(getCommand("sham"), "⚠️ Command 'sham' missing from plugin.yml").setTabCompleter(shamCommand);
    }

    private void enableDiscordAlertsEarly() {
        final var cfg = getConfig();

        String webhookUrl = trimToEmpty(cfg.getString("discord alerts.webhook-url", ""));
        String legacyToken = trimToEmpty(cfg.getString("discord alerts.token", ""));
        String legacyChannel = trimToEmpty(cfg.getString("discord alerts.channel", ""));
        String pingType = trimToEmpty(cfg.getString("discord alerts.ping", ""));

        Alerts.Severity alertLevel = parseAlertSeverity(
                cfg.getString("discord alerts.alert-level"),
                Alerts.Severity.WARN,
                "alert-level",
                false
        );
        Alerts.Severity pingLevel = parseAlertSeverity(
                cfg.getString("discord alerts.ping-level"),
                Alerts.Severity.ERROR,
                "ping-level",
                true
        );

        if (pingType.isEmpty()) {
            pingType = "@everyone";
        }

        List<String> ignore = new ArrayList<>(cfg.getStringList("discord alerts.ignore"));

        if (!legacyToken.isBlank() || !legacyChannel.isBlank()) {
            getLogger().warning("⚠️ Legacy Discord bot settings detected under 'discord alerts.token/channel'. Switch to 'discord alerts.webhook-url' for alerts.");
        }

        if (webhookUrl.isBlank()) {
            getLogger().warning("⚠️ Discord alert webhook-url not set in config.yml.");
            return;
        }

        try {
            alertsHandler = new Alerts(webhookUrl, pingType, ignore, alertLevel, pingLevel);

            Logger rootLogger = Logger.getLogger("");
            rootLogger.addHandler(alertsHandler);

            getLogger().info("✅ Discord alerts enabled.");
        } catch (Throwable t) {
            warnOptionalFeatureLoadFailure("❌ Discord alerts ", t);
        }
    }

    private Alerts.Severity parseAlertSeverity(String raw, Alerts.Severity fallback, String keyName, boolean allowNone) {
        String value = trimToEmpty(raw);
        if (allowNone && value.equalsIgnoreCase("none")) {
            return null;
        }

        Alerts.Severity parsed = Alerts.Severity.fromConfig(value);
        if (parsed != null) {
            return parsed;
        }

        String accepted = allowNone ? "info, warn, error, none" : "info, warn, error";
        getLogger().warning("❌ Invalid discord alerts " + keyName + " value '" + value + "'. Using default '" +
                (fallback == null ? "none" : fallback.getConfigValue()) + "'. Accepted values: " + accepted + ".");
        return fallback;
    }

    private static String trimToEmpty(String s) {
        return (s == null) ? "" : s.trim();
    }

    @Override
    public void onDisable() {

        if (tracker != null) {
            try {
                tracker.stop();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "❌ Failed to stop UptimeTracker cleanly: " + e.getMessage(), e);
            }
        }

        if (streakManager != null) {
            try {
                streakManager.shutdown();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "❌ Failed to stop LoginStreakManager cleanly: " + e.getMessage(), e);
            }
        }

        if (alertsHandler != null) {
            Logger rootLogger = Logger.getLogger("");
            rootLogger.removeHandler(alertsHandler);
            alertsHandler.close();
        }

        getLogger().info("Sham Plugin disabled!");
    }

    private void registerCommand(String name, CommandExecutor executor) {

        var cmd = getCommand(name);

        if (cmd != null) {
            cmd.setExecutor(executor);
        } else {
            getLogger().warning("❌ Command '" + name + "' not found in plugin.yml");
        }
    }

    public static Manager getInstance() {
        return instance;
    }

    private void warnOptionalFeatureLoadFailure(String featureName, Throwable t) {
        String message = "❌ Failed to enable " + featureName + ": " + t.getMessage();
        if (isLikelyOriginalJarIssue(t)) {
            message += " | This usually means the unshaded original plugin jar was deployed. Use the built ShamPlugin jar, not the original-* jar.";
        }
        getLogger().log(Level.WARNING, message, t);
    }

    private boolean isLikelyOriginalJarIssue(Throwable t) {
        try {
            String location = String.valueOf(getClass().getProtectionDomain().getCodeSource().getLocation());
            return location.contains("original-");
        } catch (Exception ignored) {
            return t instanceof NoClassDefFoundError || t instanceof ClassNotFoundException;
        }
    }
}
