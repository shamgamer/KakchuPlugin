package dev.shoam.streaks;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginStreakManagerLeaderboardTest {

    @Test
    void retainsAllCandidatesWhenFewerThanCapacity() {
        List<PlayerStreak> candidates = List.of(streak("Alex", 4, 9), streak("Bea", 7, 8));

        List<PlayerStreak> result = select(candidates, LoginStreakManager.CURRENT_LEADERBOARD_ORDER);

        assertEquals(List.of("Bea", "Alex"), names(result));
    }

    @Test
    void retainsExactlyTheBestHundredFromMoreThanCapacity() {
        List<PlayerStreak> candidates = new ArrayList<>();
        for (int value = 0; value < 150; value++) {
            candidates.add(streak(String.format("Player%03d", value), value, value));
        }

        List<PlayerStreak> result = select(candidates, LoginStreakManager.CURRENT_LEADERBOARD_ORDER);

        assertEquals(100, result.size());
        assertEquals("Player149", result.getFirst().username);
        assertEquals("Player050", result.getLast().username);
    }

    @Test
    void matchesThePreviousFullSortAfterProjectionAndBreaksTiesByName() {
        List<PlayerStreak> projected = new ArrayList<>();
        for (int value = 0; value < 125; value++) {
            PlayerStreak candidate = streak(String.format("Player%03d", value), value, value);
            candidate.current = value % 3 == 0 ? 0 : value - 5;
            if (candidate.current > 0) {
                projected.add(candidate);
            }
        }
        projected.add(streak("zeta", 42, 42));
        projected.add(streak("Alpha", 42, 42));

        List<PlayerStreak> expected = projected.stream()
                .sorted(LoginStreakManager.CURRENT_LEADERBOARD_ORDER)
                .limit(100)
                .toList();
        List<PlayerStreak> result = select(projected, LoginStreakManager.CURRENT_LEADERBOARD_ORDER);

        assertEquals(names(expected), names(result));
        assertTrue(names(result).indexOf("Alpha") < names(result).indexOf("zeta"));
    }

    @Test
    void highestStreakSelectionMatchesThePreviousFullSort() {
        List<PlayerStreak> candidates = new ArrayList<>();
        for (int value = 0; value < 125; value++) {
            candidates.add(streak(String.format("Player%03d", value), 0, value));
        }

        List<PlayerStreak> expected = candidates.stream()
                .sorted(LoginStreakManager.HIGHEST_LEADERBOARD_ORDER)
                .limit(100)
                .toList();

        assertEquals(names(expected), names(select(candidates, LoginStreakManager.HIGHEST_LEADERBOARD_ORDER)));
    }

    private List<PlayerStreak> select(List<PlayerStreak> candidates, Comparator<PlayerStreak> order) {
        PriorityQueue<PlayerStreak> top = new PriorityQueue<>(100, order.reversed());
        for (PlayerStreak candidate : candidates) {
            LoginStreakManager.offerTopCandidate(top, candidate, order);
        }
        return LoginStreakManager.sortedTopCandidates(top, order);
    }

    private PlayerStreak streak(String username, int current, int highest) {
        return new PlayerStreak(UUID.randomUUID(), username, current, highest, 0, 0, 0);
    }

    private List<String> names(List<PlayerStreak> streaks) {
        return streaks.stream().map(streak -> streak.username).toList();
    }
}
