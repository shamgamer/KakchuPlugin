package dev.shoam.streaks;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

interface GraceOverrideResolver {

    CompletableFuture<Integer> resolveGraceOverride(UUID uuid, String username);
}
