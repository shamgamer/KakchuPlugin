package dev.shoam.streaks;

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
                                player.sendMessage("§cCould not load the streak leaderboard right now.");
                                return;
                            }

                            player.sendMessage("§6Top Login Streaks");
                            if (list.isEmpty()) {
                                player.sendMessage("§7No active streaks yet.");
                                return;
                            }

                            int index = 1;
                            for (PlayerStreak streak : list) {
                                player.sendMessage("§e" + index + ". §f" + streak.username + " §7- §a" + streak.current);
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
                                player.sendMessage("§cCould not load the highest streak leaderboard right now.");
                                return;
                            }

                            player.sendMessage("§6Highest Streaks");
                            if (list.isEmpty()) {
                                player.sendMessage("§7No streak history yet.");
                                return;
                            }

                            int index = 1;
                            for (PlayerStreak streak : list) {
                                player.sendMessage("§e" + index + ". §f" + streak.username + " §7- §a" + streak.highest);
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
            sender.sendMessage("§cOnly players can use /streak.");
            return true;
        }

        return handleStatusLookup(sender, player, player.getUniqueId(), player.getName(), true);
    }

    public boolean handleGetStatus(@NonNull CommandSender sender, @NonNull String targetName) {
        StreakTargetResolver.resolve(plugin, manager, targetName, true)
                .whenComplete((target, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("Failed to resolve streak target " + targetName + ": " + throwable.getMessage());
                        sender.sendMessage("§cCould not look up " + targetName + " right now.");
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
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage("§cUsage: /sham streak set <player> <value> [modify-last-claim]");
            return true;
        }

        String targetName = args[0];
        int value;
        try {
            value = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid number.");
            return true;
        }

        if (value < 0) {
            sender.sendMessage("§cStreak value cannot be negative.");
            return true;
        }

        boolean modifyLastClaim = args.length < 3 || args[2].equalsIgnoreCase("true");
        if (args.length == 3) {
            if (!args[2].equalsIgnoreCase("true") && !args[2].equalsIgnoreCase("false")) {
                sender.sendMessage("§cModify-last-claim must be true or false.");
                return true;
            }
        }

        StreakTargetResolver.resolve(plugin, manager, targetName, false)
                .thenCompose(target -> {
                    if (!target.found()) {
                        return CompletableFuture.completedFuture(new SetResult(target, null));
                    }
                    return manager.setStreakAsync(target.uuid(), target.name(), value, modifyLastClaim)
                            .thenApply(streak -> new SetResult(target, streak));
                })
                .whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("Failed to set streak for " + targetName + ": " + throwable.getMessage());
                        sender.sendMessage("§cCould not set the streak for " + targetName + ".");
                        return;
                    }

                    if (!result.target().found()) {
                        sender.sendMessage(StreakTargetResolver.failureMessage(result.target()));
                        return;
                    }

                    sender.sendMessage("§aSet " + result.target().name() + "'s streak to " + result.streak().current);
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
                        sender.sendMessage(self ? "§cCould not load your streak right now." : "§cCould not load the streak for " + resolvedName + ".");
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
                        sender.sendMessage("§cCould not load the streak for " + target.name() + ".");
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
            sender.sendMessage("§6Streak for " + resolvedName + ":");
        }
        sender.sendMessage("§aAvailable graces: §e" + status.availableGraces() + "/" + status.maxGraces() + " §7(" + formatDuration(status.timeUntilReset()) + ")");
        sender.sendMessage("§aGraces reset in: §e" + formatDuration(graceReset));
        sender.sendMessage("§aActive Streak: §e" + status.current() + " §7| §aHighest Streak: §e" + status.highest());
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
