package win.shamserver.streaks;

import java.util.UUID;

public class PlayerStreak {

    public UUID uuid;
    public String username;

    public int current;
    public int highest;

    public long lastClaim;

    public int graceUsed;
    public int graceWeek;

    public PlayerStreak(
            UUID uuid,
            String username,
            int current,
            int highest,
            long lastClaim,
            int graceUsed,
            int graceWeek
    ) {

        this.uuid = uuid;
        this.username = username;

        this.current = current;
        this.highest = highest;

        this.lastClaim = lastClaim;

        this.graceUsed = graceUsed;
        this.graceWeek = graceWeek;

    }

    public PlayerStreak copy() {
        return new PlayerStreak(
                uuid,
                username,
                current,
                highest,
                lastClaim,
                graceUsed,
                graceWeek
        );
    }

}