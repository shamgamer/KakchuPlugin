package win.shamserver.streaks;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

interface GraceOverrideResolver {

    CompletableFuture<Integer> resolveGraceOverride(UUID uuid, String username);
}