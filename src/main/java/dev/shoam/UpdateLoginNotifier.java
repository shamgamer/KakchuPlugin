package win.shamserver;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class UpdateLoginNotifier implements Listener {
    private final Manager plugin;

    private final Set<UUID> notifiedThisVersion = ConcurrentHashMap.newKeySet();
    private String lastVersion;

    public UpdateLoginNotifier(Manager plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("update-checker.notify-ops-on-login", true)) return;

        var player = event.getPlayer();
        if (!player.isOp()) return;

        String version = plugin.getUpdateAvailableVersion();
        String msg = plugin.getUpdateAvailableMessage();
        if (version == null || msg == null || msg.isBlank()) return;

        // If a new latest version is detected, reset per-player notification tracking.
        if (lastVersion == null || !lastVersion.equals(version)) {
            lastVersion = version;
            notifiedThisVersion.clear();
        }

        // Only schedule if this OP hasn't been notified yet
        if (!notifiedThisVersion.add(player.getUniqueId())) return;

        long delayTicks = plugin.getConfig().getLong(
                "update-checker.notify-ops-on-login-delay-ticks", 30L
        );

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage("§e" + msg);
        }, delayTicks);
    }
}