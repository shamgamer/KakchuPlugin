package win.shamserver;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import win.shamserver.streaks.LoginStreakManager;

public final class PlayerNotifier {

    private final JavaPlugin plugin;
    private final LoginStreakManager streakManager;

    public PlayerNotifier(JavaPlugin plugin, LoginStreakManager manager) {
        this.plugin = plugin;
        this.streakManager = manager;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(
                new LoginRewardReminder(plugin, streakManager),
                plugin
        );
    }
}

/* ===================== INTERNAL NOTIFIERS ===================== */

class LoginRewardReminder implements Listener {

    private static final String PERMISSION = "shamplugin.loginrewards";

    private final JavaPlugin plugin;
    private final LoginStreakManager streakManager;
    private final MiniMessage mini = MiniMessage.miniMessage();

    LoginRewardReminder(JavaPlugin plugin, LoginStreakManager manager) {
        this.plugin = plugin;
        this.streakManager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("axrewards.login-reminder.enabled", true)) return;

        Player player = event.getPlayer();
        if (!player.hasPermission(PERMISSION)) return;

        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                || !Bukkit.getPluginManager().isPluginEnabled("AxRewards")) {
            return;
        }

        long delay = plugin.getConfig().getLong("axrewards.login-reminder.delay-ticks", 2L);
        String placeholder = plugin.getConfig().getString(
                "axrewards.login-reminder.placeholder",
                "%axrewards_collectable%"
        );
        String command = plugin.getConfig().getString(
                "axrewards.login-reminder.command",
                "/daily"
        );

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            String raw = PlaceholderAPI.setPlaceholders(player, placeholder).trim();
            if (raw.isEmpty()) return;

            int count;
            try {
                count = Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                return;
            }

            if (count <= 0) return;

            String template = count == 1
                    ? plugin.getConfig().getString("axrewards.login-reminder.message-single")
                    : plugin.getConfig().getString("axrewards.login-reminder.message-multiple");

            if (template == null || template.isEmpty()) return;

            streakManager.getCurrentStreakAsync(player.getUniqueId(), player.getName(), player)
                    .whenComplete((streak, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }

                        int currentStreak = throwable == null ? streak : 0;
                        if (throwable != null) {
                            plugin.getLogger().warning("Failed to load login streak for " + player.getName() + ": " + throwable.getMessage());
                        }

                        String rendered = template
                                .replace("<count>", String.valueOf(count))
                                .replace("<cmd>", command)
                                .replace("<stk>", String.valueOf(currentStreak));

                        Component message = mini.deserialize(rendered)
                                .replaceText(builder ->
                                        builder.matchLiteral(command)
                                                .replacement(
                                                        Component.text(command)
                                                                .clickEvent(ClickEvent.runCommand(command))
                                                )
                                );

                        player.sendMessage(message);
                        playSound(player);
                    }));

        }, delay);
    }

    private void playSound(Player player) {
        if (!plugin.getConfig().getBoolean("axrewards.login-reminder.sound.enabled", false)) return;

        String soundName = plugin.getConfig().getString(
                "axrewards.login-reminder.sound.name",
                "entity.experience_orb.pickup"
        ).toLowerCase();

        NamespacedKey key;
        if (soundName.contains(":")) {
            key = NamespacedKey.fromString(soundName);
        } else {
            key = NamespacedKey.minecraft(soundName);
        }

        if (key == null) return;

        Sound sound = Registry.SOUNDS.get(key);
        if (sound == null) return;

        float volume = (float) plugin.getConfig().getDouble("axrewards.login-reminder.sound.volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble("axrewards.login-reminder.sound.pitch", 1.0);

        player.playSound(player.getLocation(), sound, volume, pitch);
    }
}