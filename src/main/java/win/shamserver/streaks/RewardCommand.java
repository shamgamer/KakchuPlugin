package win.shamserver.streaks;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class RewardCommand implements CommandExecutor, TabCompleter {

    private static final String[] REWARD_BASES = {
            "axrewards.login-streaks.rewards",
            "login_streaks.rewards"
    };

    private final JavaPlugin plugin;
    private final LoginStreakManager manager;

    public RewardCommand(JavaPlugin plugin, LoginStreakManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NonNull CommandSender sender, @NonNull Command cmd, @NonNull String label, String @NonNull [] args) {
        return handleReward(sender, args);
    }

    public boolean handleReward(@NonNull CommandSender sender, String @NonNull [] args) {
        if (sender instanceof Player player) {
            if (!player.isOp() && !player.hasPermission("shamplugin.admin")) {
                sender.sendMessage("§cYou do not have permission.");
                return true;
            }
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /sham streak reward <player> <type>");
            return true;
        }

        String targetName = args[0];
        String typeInput = args[1].trim();

        String typePath = resolveTypePath(typeInput);
        if (typePath == null) {
            sender.sendMessage("§7No rewards configured for type: " + typeInput);
            return true;
        }

        ConfigurationSection bonusesSection = plugin.getConfig().getConfigurationSection(typePath + ".bonuses");
        if (bonusesSection == null) {
            sender.sendMessage("§7No rewards configured for type: " + typeInput);
            return true;
        }

        boolean shouldIncrement = plugin.getConfig().getBoolean(typePath + ".streak", false);

        StreakTargetResolver.resolve(plugin, manager, targetName, false)
                .thenCompose(target -> {
                    if (!target.found()) {
                        return CompletableFuture.completedFuture(new RewardResult(target, null));
                    }
                    return manager.processClaimAsync(target.uuid(), target.name(), shouldIncrement, target.player())
                            .thenApply(claimResult -> new RewardResult(target, claimResult));
                })
                .whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("Failed to process streak claim for " + targetName + ": " + throwable.getMessage());
                        sender.sendMessage("§cCould not process the reward for " + targetName + ".");
                        return;
                    }

                    if (!result.target().found()) {
                        sender.sendMessage(StreakTargetResolver.failureMessage(result.target()));
                        return;
                    }

                    LoginStreakManager.ClaimResult claimResult = result.claimResult();
                    String resolvedName = result.target().name();
                    List<Integer> keys = bonusesSection.getKeys(false).stream().map(key -> {
                        try {
                            return Integer.parseInt(key);
                        } catch (NumberFormatException ex) {
                            return null;
                        }
                    }).filter(Objects::nonNull).sorted().collect(Collectors.toList());

                    Integer selectedThreshold = null;
                    for (Integer key : keys) {
                        if (key <= claimResult.rewardStreak()) {
                            selectedThreshold = key;
                        }
                    }

                    if (selectedThreshold == null) {
                        sender.sendMessage("§7No reward configured for streak " + claimResult.rewardStreak() + " (" + typeInput + ").");
                        sendStreakUpdateMessage(sender, resolvedName, shouldIncrement, claimResult);
                        return;
                    }

                    List<String> configuredCommands = getConfiguredCommands(typePath, selectedThreshold);
                    if (configuredCommands.isEmpty()) {
                        sender.sendMessage("§7No commands configured for threshold " + selectedThreshold + " (" + typeInput + ").");
                        sendStreakUpdateMessage(sender, resolvedName, shouldIncrement, claimResult);
                        return;
                    }

                    for (String configuredCommand : configuredCommands) {
                        if (configuredCommand == null || configuredCommand.isBlank()) {
                            continue;
                        }

                        String replaced = configuredCommand.replace("%player%", resolvedName);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), replaced);
                    }

                    sender.sendMessage("§aExecuted configured reward commands for " + resolvedName + " (threshold " + selectedThreshold + ", " + typeInput + ").");
                    sendStreakUpdateMessage(sender, resolvedName, shouldIncrement, claimResult);
                }));

        return true;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command cmd, @NonNull String alias, String @NonNull [] args) {
        if (args.length == 1) {
            return completePlayerNames(args[0]);
        }

        if (args.length == 2) {
            return completeRewardTypes(args[1]);
        }

        return Collections.emptyList();
    }

    public List<String> completePlayerNames(String input) {
        List<String> names = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
        return StringUtil.copyPartialMatches(input, names, new ArrayList<>());
    }

    public List<String> completeRewardTypes(String input) {
        return StringUtil.copyPartialMatches(input, getRewardTypes(), new ArrayList<>());
    }

    private String resolveTypePath(String typeInput) {
        if (typeInput == null || typeInput.isBlank()) return null;

        for (String base : REWARD_BASES) {
            ConfigurationSection rewards = plugin.getConfig().getConfigurationSection(base);
            if (rewards == null) continue;

            for (String key : rewards.getKeys(false)) {
                if (key.equalsIgnoreCase(typeInput)) {
                    return base + "." + key;
                }
            }
        }

        return null;
    }

    private List<String> getRewardTypes() {
        Set<String> types = new LinkedHashSet<>();

        for (String base : REWARD_BASES) {
            ConfigurationSection rewards = plugin.getConfig().getConfigurationSection(base);
            if (rewards != null) {
                types.addAll(rewards.getKeys(false));
            }
        }

        return new ArrayList<>(types);
    }

    private List<String> getConfiguredCommands(String typePath, int selectedThreshold) {
        String commandsPath = typePath + ".bonuses." + selectedThreshold + ".commands";
        List<String> configuredCommands = plugin.getConfig().getStringList(commandsPath);

        if (configuredCommands == null || configuredCommands.isEmpty()) {
            Object raw = plugin.getConfig().get(typePath + ".bonuses." + selectedThreshold);
            if (raw instanceof List<?>) {
                configuredCommands = ((List<?>) raw).stream().map(Object::toString).collect(Collectors.toList());
            } else if (raw instanceof String) {
                configuredCommands = Collections.singletonList((String) raw);
            }
        }

        return configuredCommands == null ? Collections.emptyList() : configuredCommands;
    }

    private void sendStreakUpdateMessage(CommandSender sender, String resolvedName, boolean shouldIncrement, LoginStreakManager.ClaimResult claimResult) {
        if (!shouldIncrement) {
            return;
        }

        if (claimResult.streakIncremented()) {
            sender.sendMessage("§aIncremented streak for " + resolvedName + " to " + claimResult.currentStreak());
            return;
        }

        sender.sendMessage("§eStreak for " + resolvedName + " remains " + claimResult.currentStreak() + ".");
    }

    private record RewardResult(StreakTargetResolver.Target target, LoginStreakManager.ClaimResult claimResult) {
    }
}
