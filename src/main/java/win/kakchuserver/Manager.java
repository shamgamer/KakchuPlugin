package win.kakchuserver;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Manager extends JavaPlugin {

    private static Manager instance;
    private Alerts alertsHandler;
    private UptimeTracker tracker;

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("Kakchu Plugin enabled!");

        // Ensure config exists before anything that reads it
        saveDefaultConfig();

        // === Uptime tracker ===
        try {
            tracker = new UptimeTracker(this);
            tracker.start();
            getLogger().info("✅ UptimeTracker started.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "❌ Failed to start UptimeTracker: " + e.getMessage(), e);
            tracker = null;
        }

        // Auto-register all commands from plugin.yml.
        var commands = getDescription().getCommands(); // this is non-null in the Bukkit API
        if (commands != null && !commands.isEmpty()) {
            for (String cmdName : commands.keySet()) {
                if ("uptime".equalsIgnoreCase(cmdName) && tracker != null) {
                    // Prefer the uptime command with live tracker
                    registerCommand(cmdName, new UptimeTracker.UptimeCommand(tracker));
                } else {
                    // Fallback/default handler — your existing Commands class
                    registerCommand(cmdName, new Commands());
                }
            }
        } else {
            getLogger().info("No commands found in plugin.yml to register.");
        }

        // === Discord alerts ===
        String token = getConfig().getString("discord.token", "");
        String channelId = getConfig().getString("discord.channel", "");
        String pingType = getConfig().getString("discord.ping", "@everyone");

        if (token != null && channelId != null && !token.isEmpty() && !channelId.isEmpty()) {
            // initialize Alerts off the main thread (network work)
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    alertsHandler = new Alerts(token, channelId, pingType);
                    // On success, attach logging handler on main thread
                    Bukkit.getScheduler().runTask(this, () -> {
                        Logger rootLogger = Logger.getLogger("");
                        rootLogger.addHandler(alertsHandler);
                        getLogger().info("✅ Discord alerts enabled.");
                    });
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, "❌ Failed to enable Discord alerts: " + e.getMessage(), e);
                }
            });
        } else {
            getLogger().warning("⚠️ Discord alert token/channel not set in config.yml.");
        }

        // === Restarter scheduling ===
        // NOTE: use keys under the top-level "restarter" section (not under "discord")
        long startDelay = getConfig().getLong("restarter.start-delay-ticks", 432000L); // default 6 hours (432k ticks)
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
            } catch (Exception e) {
                // ignore
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