package win.shamserver.streaks;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class StreakCommands implements CommandExecutor {

    private final LoginStreakManager manager;
    private final JavaPlugin plugin;

    public StreakCommands(JavaPlugin plugin, LoginStreakManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NonNull CommandSender sender, Command cmd, @NonNull String label, String @NonNull [] args) {
        String name = cmd.getName().toLowerCase();

        switch (name) {
            case "streak" -> {
                return handleSelfStatus(sender);
            }
            case "streaktop" -> {
                if (!(sender instanceof Player player)) return true;

                int limit = plugin.getConfig().getInt("axrewards.login-streaks.leaderboard_display_length", 10);
                manager.getTopCurrentAsync(limit)
                        .whenComplete((list, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (throwable != null) {
                                plugin.getLogger().warning("Failed to load current streak leaderboard: " + throwable.getMessage());
                                player.sendMessage("\u00A7cCould not load the streak leaderboard right now.");
                                return;
                            }

                            player.sendMessage("\u00A76Top Login Streaks");
                            if (list.isEmpty()) {
                                player.sendMessage("\u00A77No active streaks yet.");
                                return;
                            }

                            int index = 1;
                            for (PlayerStreak streak : list) {
                                player.sendMessage("\u00A7e" + index + ". \u00A7f" + streak.username + " \u00A77- \u00A7a" + streak.current);
                                index++;
                            }
                        }));
                return true;
            }
            case "higheststreaktop" -> {
                if (!(sender instanceof Player player)) return true;

                int limit = plugin.getConfig().getInt("axrewards.login-streaks.leaderboard_display_length", 10);
                manager.getTopHighestAsync(limit)
                        .whenComplete((list, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (throwable != null) {
                                plugin.getLogger().warning("Failed to load highest streak leaderboard: " + throwable.getMessage());
                                player.sendMessage("\u00A7cCould not load the highest streak leaderboard right now.");
                                return;
                            }

                            player.sendMessage("\u00A76Highest Streaks");
                            if (list.isEmpty()) {
                                player.sendMessage("\u00A77No streak history yet.");
                                return;
                            }

                            int index = 1;
                            for (PlayerStreak streak : list) {
                                player.sendMessage("\u00A7e" + index + ". \u00A7f" + streak.username + " \u00A77- \u00A7a" + streak.highest);
                                index++;
                            }
                        }));
                return true;
            }
        }

        return true;
    }

    public boolean handleSelfStatus(@NonNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("\u00A7cOnly players can use /streak.");
            return true;
        }

        return handleStatusLookup(sender, player, player.getUniqueId(), player.getName(), true);
    }

    public boolean handleGetStatus(@NonNull CommandSender sender, @NonNull String targetName) {
        StreakTargetResolver.resolve(plugin, manager, targetName, true)
                .whenComplete((target, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("Failed to resolve streak target " + targetName + ": " + throwable.getMessage());
                        sender.sendMessage("\u00A7cCould not look up " + targetName + " right now.");
                        return;
                    }

                    if (!target.found()) {
                        sender.sendMessage(StreakTargetResolver.failureMessage(target));
                        return;
                    }

                    handleExistingStatusLookup(sender, target);
                }));
        return true;
    }

    public boolean handleSet(@NonNull CommandSender sender, String @NonNull [] args) {
        if (args.length < 2) {
            sender.sendMessage("\u00A7cUsage: /sham streak set <player> <value>");
            return true;
        }

        String targetName = args[0];
        int value;
        try {
            value = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("\u00A7cInvalid number.");
            return true;
        }

        if (value < 0) {
            sender.sendMessage("\u00A7cStreak value cannot be negative.");
            return true;
        }

        StreakTargetResolver.resolve(plugin, manager, targetName, false)
                .thenCompose(target -> {
                    if (!target.found()) {
                        return CompletableFuture.completedFuture(new SetResult(target, null));
                    }
                    return manager.setStreakAsync(target.uuid(), target.name(), value)
                            .thenApply(streak -> new SetResult(target, streak));
                })
                .whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("Failed to set streak for " + targetName + ": " + throwable.getMessage());
                        sender.sendMessage("\u00A7cCould not set the streak for " + targetName + ".");
                        return;
                    }

                    if (!result.target().found()) {
                        sender.sendMessage(StreakTargetResolver.failureMessage(result.target()));
                        return;
                    }

                    sender.sendMessage("\u00A7aSet " + result.target().name() + "'s streak to " + result.streak().current);
                }));

        return true;
    }

    public List<String> completePlayerNames(@NonNull String input) {
        List<String> names = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .toList();
        return StringUtil.copyPartialMatches(input, names, new ArrayList<>());
    }

    private boolean handleStatusLookup(@NonNull CommandSender sender,
                                       Player player,
                                       @NonNull UUID uuid,
                                       @NonNull String resolvedName,
                                       boolean self) {
        manager.getStatusAsync(uuid, resolvedName, player)
                .whenComplete((status, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player != null && !player.isOnline()) {
                        return;
                    }
                    if (throwable != null) {
                        plugin.getLogger().warning("Failed to load streak for " + resolvedName + ": " + throwable.getMessage());
                        sender.sendMessage(self ? "\u00A7cCould not load your streak right now." : "\u00A7cCould not load the streak for " + resolvedName + ".");
                        return;
                    }

                    sendStatusMessage(sender, resolvedName, status, self);
                }));
        return true;
    }

    private void handleExistingStatusLookup(@NonNull CommandSender sender, StreakTargetResolver.Target target) {
        manager.getExistingStatusAsync(target.streak(), target.player())
                .whenComplete((status, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("Failed to load streak for " + target.name() + ": " + throwable.getMessage());
                        sender.sendMessage("\u00A7cCould not load the streak for " + target.name() + ".");
                        return;
                    }

                    sendStatusMessage(sender, target.name(), status, false);
                }));
    }

    private void sendStatusMessage(@NonNull CommandSender sender,
                                   @NonNull String resolvedName,
                                   LoginStreakManager.StreakStatus status,
                                   boolean self) {
        Duration graceReset = manager.getTimeUntilGraceReset();
        if (!self) {
            sender.sendMessage("\u00A76Streak for " + resolvedName + ":");
        }
        sender.sendMessage("\u00A7aAvailable graces: \u00A7e" + status.availableGraces() + "/" + status.maxGraces() + " \u00A77(" + formatDuration(status.timeUntilReset()) + ")");
        sender.sendMessage("\u00A7aGraces reset in: \u00A7e" + formatDuration(graceReset));
        sender.sendMessage("\u00A7aActive Streak: \u00A7e" + status.current() + " \u00A77| \u00A7aHighest Streak: \u00A7e" + status.highest());
    }

    private String formatDuration(Duration duration) {
        if (duration == null) {
            return "N/A";
        }

        long seconds = Math.max(0L, duration.getSeconds());

        long days = seconds / 86_400;
        seconds %= 86_400;

        long hours = seconds / 3_600;
        seconds %= 3_600;

        long minutes = seconds / 60;
        seconds %= 60;

        StringBuilder out = new StringBuilder();

        if (days > 0) {
            out.append(days).append("d ");
        }
        if (hours > 0) {
            out.append(hours).append("h ");
        }
        if (minutes > 0) {
            out.append(minutes).append("m ");
        }
        if (seconds > 0 || out.isEmpty()) {
            out.append(seconds).append("s");
        }

        return out.toString().trim();
    }

    private record SetResult(StreakTargetResolver.Target target, PlayerStreak streak) {
    }
}
