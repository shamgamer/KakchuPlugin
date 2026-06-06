package win.shamserver.streaks;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

final class StreakTargetResolver {

    private static final Pattern JAVA_USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    enum Failure {
        DATABASE_MISS,
        PROFILE_MISS,
        INVALID_PROFILE_NAME
    }

    record Target(
            UUID uuid,
            String name,
            Player player,
            PlayerStreak streak,
            Failure failure,
            String input
    ) {
        boolean found() {
            return uuid != null;
        }
    }

    private StreakTargetResolver() {
    }

    static CompletableFuture<Target> resolve(JavaPlugin plugin,
                                             LoginStreakManager manager,
                                             String rawInput,
                                             boolean requireExistingStreak) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isEmpty()) {
            return CompletableFuture.completedFuture(failed(Failure.PROFILE_MISS, input));
        }

        Player online = plugin.getServer().getPlayerExact(input);
        if (online != null) {
            if (!requireExistingStreak) {
                return CompletableFuture.completedFuture(found(online.getUniqueId(), online.getName(), online, null, input));
            }

            return manager.findStreakByUuidAsync(online.getUniqueId())
                    .thenCompose(streak -> {
                        if (streak != null) {
                            return CompletableFuture.completedFuture(found(streak.uuid, streak.username, online, streak, input));
                        }

                        return manager.findStreakByUsernameAsync(input)
                                .thenApply(namedStreak -> namedStreak == null
                                        ? failed(Failure.DATABASE_MISS, input)
                                        : found(namedStreak.uuid, namedStreak.username, null, namedStreak, input));
                    });
        }

        return manager.findStreakByUsernameAsync(input)
                .thenCompose(streak -> {
                    if (streak != null) {
                        return CompletableFuture.completedFuture(found(streak.uuid, streak.username, null, streak, input));
                    }

                    if (!isJavaUsername(input)) {
                        return CompletableFuture.completedFuture(failed(Failure.INVALID_PROFILE_NAME, input));
                    }

                    return resolveProfileOnMain(plugin, manager, input, requireExistingStreak);
                });
    }

    static String failureMessage(Target target) {
        return switch (target.failure()) {
            case DATABASE_MISS -> "§cNo streak record found for " + target.input() + ".";
            case PROFILE_MISS -> "§cNo player profile found for " + target.input() + ".";
            case INVALID_PROFILE_NAME -> "§cNo streak record found for " + target.input()
                    + ", and that name cannot be used for Java profile lookup.";
        };
    }

    private static CompletableFuture<Target> resolveProfileOnMain(JavaPlugin plugin,
                                                                  LoginStreakManager manager,
                                                                  String input,
                                                                  boolean requireExistingStreak) {
        CompletableFuture<Target> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                OfflinePlayer offline = plugin.getServer().getOfflinePlayerIfCached(input);
                if (offline == null) {
                    offline = plugin.getServer().getOfflinePlayer(input);
                }

                if (!offline.isOnline() && !offline.hasPlayedBefore()) {
                    future.complete(failed(Failure.PROFILE_MISS, input));
                    return;
                }

                String resolvedName = offline.getName() != null ? offline.getName() : input;
                Player player = offline.getPlayer();

                if (!requireExistingStreak) {
                    future.complete(found(offline.getUniqueId(), resolvedName, player, null, input));
                    return;
                }

                manager.findStreakByUuidAsync(offline.getUniqueId())
                        .whenComplete((streak, throwable) -> {
                            if (throwable != null) {
                                future.completeExceptionally(throwable);
                                return;
                            }

                            future.complete(streak == null
                                    ? failed(Failure.DATABASE_MISS, input)
                                    : found(streak.uuid, streak.username, player, streak, input));
                        });
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });

        return future;
    }

    private static boolean isJavaUsername(String input) {
        return JAVA_USERNAME.matcher(input).matches();
    }

    private static Target found(UUID uuid, String name, Player player, PlayerStreak streak, String input) {
        return new Target(uuid, name, player, streak, null, input);
    }

    private static Target failed(Failure failure, String input) {
        return new Target(null, null, null, null, failure, input);
    }
}
