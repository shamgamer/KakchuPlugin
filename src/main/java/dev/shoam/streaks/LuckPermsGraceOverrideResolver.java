package dev.shoam.streaks;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import org.bukkit.Server;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class LuckPermsGraceOverrideResolver implements GraceOverrideResolver {

    private static final String GRACE_PERMISSION_PREFIX = "shamplugin.graces.";

    private final UserManager userManager;

    private LuckPermsGraceOverrideResolver(UserManager userManager) {
        this.userManager = userManager;
    }

    static GraceOverrideResolver tryCreate(Server server) {
        LuckPerms luckPerms = server.getServicesManager().load(LuckPerms.class);
        if (luckPerms == null) {
            return null;
        }

        return new LuckPermsGraceOverrideResolver(luckPerms.getUserManager());
    }

    @Override
    public CompletableFuture<Integer> resolveGraceOverride(UUID uuid, String username) {
        User loadedUser = userManager.getUser(uuid);
        if (loadedUser != null) {
            return CompletableFuture.completedFuture(resolveGraceOverride(loadedUser));
        }

        return userManager.loadUser(uuid)
                .thenApply(user -> {
                    try {
                        return resolveGraceOverride(user);
                    } finally {
                        userManager.cleanupUser(user);
                    }
                });
    }

    private int resolveGraceOverride(User user) {
        return resolveGraceOverride(user.getCachedData().getPermissionData().getPermissionMap());
    }

    private int resolveGraceOverride(Map<String, Boolean> permissionMap) {
        int override = Integer.MAX_VALUE;

        for (Map.Entry<String, Boolean> entry : permissionMap.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }

            String permission = entry.getKey();
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
}
