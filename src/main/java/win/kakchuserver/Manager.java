package win.kakchuserver;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Manager extends JavaPlugin {

    private static Manager instance;
    private Alerts alertsHandler;
    private UptimeTracker tracker;

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

    @Override
    public void onEnable() {
        instance = this;

        // Ensure config exists before anything that reads it
        saveDefaultConfig();

        // === Discord alerts (attach EARLY to avoid missing startup WARN/ERROR) ===
        enableDiscordAlertsEarly();

        getLogger().info("Kakchu Plugin enabled!");

        // === Update checker ===
        if (getConfig().getBoolean("update-checker.enabled", true)) {
            long hours = Math.max(1, getConfig().getLong("update-checker.interval-hours", 12));
            long periodTicks = hours * 60L * 60L * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, new UpdateChecker(this), 20L * 10L, periodTicks);
            Bukkit.getPluginManager().registerEvents(new UpdateLoginNotifier(this), this);
        }

        // === Uptime tracker ===
        try {
            tracker = new UptimeTracker(this);
            tracker.start();
            getLogger().info("✅ UptimeTracker started.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "❌ Failed to start UptimeTracker: " + e.getMessage(), e);
            tracker = null;
        }

        // === Auto-register all commands from plugin.yml ===
        @SuppressWarnings("deprecation") // Paper deprecates getDescription(), but it is still the simplest reliable way.
        var commands = getDescription().getCommands();

        if (!commands.isEmpty()) {
            for (String cmdName : commands.keySet()) {
                if ("uptime".equalsIgnoreCase(cmdName) && tracker != null) {
                    registerCommand(cmdName, new UptimeTracker.UptimeCommand(tracker));
                } else {
                    registerCommand(cmdName, new Commands());
                }
            }
        } else {
            getLogger().info("No commands found in plugin.yml to register.");
        }

        // === Restarter scheduling ===
        long startDelay = getConfig().getLong("restarter.start-delay-ticks", 432000L); // default 6 hours
        long interval = getConfig().getLong("restarter.interval-ticks", 10L);          // default 10 ticks (0.5s)

        if (startDelay < 0) {
            getLogger().warning("restarter.start-delay-ticks is negative; using 0.");
            startDelay = 0;
        }
        if (interval < 1) {
            getLogger().warning("restarter.interval-ticks must be >= 1; using 10.");
            interval = 10;
        }
        if (interval > 20) {
            getLogger().warning("Restarter interval is greater than 20 ticks (" + interval + "). " +
                    "The restarter checks hour:minute:second equality and may miss exact-second matches. " +
                    "Consider using interval <= 20 for robust behaviour.");
        }

        try {
            Bukkit.getScheduler().runTaskTimer(this, new Restarter(this), startDelay, interval);
            getLogger().info("⏰ Restarter scheduled with startDelay=" + startDelay + " ticks, interval=" + interval + " ticks.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to schedule Restarter task: " + e.getMessage(), e);
        }
    }

    private void enableDiscordAlertsEarly() {
        // Support both current (discord alerts.*) and legacy configs (discord.* / alerts.*).
        final var cfg = getConfig();

        String tokenNew = trimToEmpty(cfg.getString("discord alerts.token", ""));
        String channelNew = trimToEmpty(cfg.getString("discord alerts.channel", ""));
        String pingNew = trimToEmpty(cfg.getString("discord alerts.ping", ""));

        // Legacy (<= 2.7.0RC8 and earlier)
        String tokenLegacy = trimToEmpty(cfg.getString("discord.token", ""));
        String channelLegacy = trimToEmpty(cfg.getString("discord.channel", ""));
        String pingLegacy = trimToEmpty(cfg.getString("discord.ping", ""));

        String token = !tokenNew.isEmpty() ? tokenNew : tokenLegacy;
        String channelId = !channelNew.isEmpty() ? channelNew : channelLegacy;

        String pingType = !pingNew.isEmpty() ? pingNew : pingLegacy;
        pingType = pingType.trim();
        if (pingType.isEmpty()) pingType = "@everyone";

        // Ignore list (new key first, then legacy fallbacks)
        List<String> ignoreNew = cfg.getStringList("discord alerts.ignore");
        List<String> ignoreLegacyDiscord = cfg.getStringList("discord.ignore");
        List<String> ignoreLegacyAlerts = cfg.getStringList("alerts.ignore");

        List<String> ignore = ignoreNew;
        if (ignore.isEmpty()) ignore = ignoreLegacyDiscord;
        if (ignore.isEmpty()) ignore = ignoreLegacyAlerts;
        ignore = new ArrayList<>(ignore);

        boolean usedLegacyValues =
                (tokenNew.isEmpty() && !tokenLegacy.isEmpty()) ||
                        (channelNew.isEmpty() && !channelLegacy.isEmpty()) ||
                        (pingNew.isEmpty() && !pingLegacy.isEmpty()) ||
                        (ignoreNew.isEmpty() && (!ignoreLegacyDiscord.isEmpty() || !ignoreLegacyAlerts.isEmpty()));

        if (token.isBlank() || channelId.isBlank()) {
            getLogger().warning("⚠️ Discord alert token/channel not set in config.yml.");
            return;
        }
        if (usedLegacyValues) {
            getLogger().warning("⚠️ Using legacy config keys for Discord alerts (discord.* / alerts.*). Please migrate to 'discord alerts:' in config.yml.");
        }

        try {
            // Constructor is non-blocking; it starts JDA init on its own thread.
            alertsHandler = new Alerts(token, channelId, pingType, ignore);

            Logger rootLogger = Logger.getLogger("");
            rootLogger.addHandler(alertsHandler);

            getLogger().info("✅ Discord alerts enabled.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "❌ Failed to enable Discord alerts: " + e.getMessage(), e);
        }
    }

    private static String trimToEmpty(String s) {
        return (s == null) ? "" : s.trim();
    }

    @Override
    public void onDisable() {
        if (tracker != null) {
            try {
                tracker.stop();
                getLogger().info("✅ UptimeTracker stopped and saved.");
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to stop UptimeTracker cleanly: " + e.getMessage(), e);
            }
        }

        if (alertsHandler != null) {
            Logger rootLogger = Logger.getLogger("");
            rootLogger.removeHandler(alertsHandler);
            try {
                alertsHandler.close();
            } catch (Exception ignored) {
            }
        }

        getLogger().info("Kakchu Plugin disabled!");
    }

    private void registerCommand(String name, CommandExecutor executor) {
        var cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
        } else {
            getLogger().warning("Command '" + name + "' not found in plugin.yml");
        }
    }

    public static Manager getInstance() {
        return instance;
    }
}