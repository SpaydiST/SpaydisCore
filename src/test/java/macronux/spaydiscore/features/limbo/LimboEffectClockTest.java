package macronux.spaydiscore.features.limbo;

public final class LimboEffectClockTest {
    private static final long FIFTY_MILLIS = 50_000_000L;

    public static void run() {
        exactTwentyUpdatesPerSecond();
        jitterDoesNotDropUpdates();
        highFrequencyCallsStayRateLimited();
        acceptedFramesCoverGlobalPhases();
        pausesAndRepeatedTimesAreBounded();
        backwardsTimeDoesNotMintCredit();
        nanoTimeWrapIsSafe();
        resetRestoresInitialState();
        oneHertzWorks();
        rejectsInvalidRates();
        System.out.println("Limbo effect clock checks passed");
    }

    private static void exactTwentyUpdatesPerSecond() {
        LimboEffectClock clock = new LimboEffectClock(20);
        require(clock.advance(0), "the initially full clock emits immediately");
        for (int tick = 1; tick <= 72_000; tick++) {
            require(clock.advance(tick * FIFTY_MILLIS), "20 Hz clock emits at each interval");
        }
        require(clock.frame() == 72_001, "accepted updates advance the frame exactly once");
        require(clock.advance(72_000L * FIFTY_MILLIS), "the spare interval remains available at the same time");
        require(!clock.advance(72_000L * FIFTY_MILLIS), "same-time calls cannot exceed the burst allowance");
        require(clock.frame() == 72_002, "rejected updates do not advance the frame");
    }

    private static void jitterDoesNotDropUpdates() {
        LimboEffectClock clock = new LimboEffectClock(20);
        require(clock.advance(0), "jitter clock starts with an update");
        long now = 0;
        for (int pair = 0; pair < 1_000; pair++) {
            now += 45_000_000L;
            require(clock.advance(now), "45 ms update is accepted");
            now += 55_000_000L;
            require(clock.advance(now), "following 55 ms update is accepted");
        }
        require(clock.frame() == 2_001, "alternating jitter preserves the nominal 20 Hz rate");
    }

    private static void highFrequencyCallsStayRateLimited() {
        LimboEffectClock clock = new LimboEffectClock(20);
        long accepted = 0;
        long now = 0;
        for (int tick = 0; tick < 12_000; tick++) {
            now = tick * 1_000_000_000L / 4_947;
            if (clock.advance(now)) accepted++;
        }
        long maximum = 2 + now / FIFTY_MILLIS;
        require(accepted <= maximum, "4,947 calls per second stay within the 20 Hz budget");
        require(clock.frame() == accepted, "frame tracks only accepted high-frequency calls");
        System.out.println("4,947 TPS limiter: " + accepted + " accepted updates from 12,000 ticks");
    }

    private static void acceptedFramesCoverGlobalPhases() {
        int players = 2_000;
        int starCount = 10_000;
        int period = StarQuality.NORMAL.globalPeriod(players);
        require(period == starCount, "phase test uses one star per period");

        LimboEffectClock clock = new LimboEffectClock(20);
        boolean[] acceptedFramePhases = new boolean[starCount];
        boolean[] alignedServerTickPhases = new boolean[starCount];
        for (int sample = 0; sample < period; sample++) {
            require(clock.advance(sample * FIFTY_MILLIS), "each 20 Hz sample is accepted");
            StarQuality.NORMAL.forEachGlobalStar(players, clock.frame(), starCount,
                    index -> acceptedFramePhases[index] = true);
            StarQuality.NORMAL.forEachGlobalStar(players, (long) sample * period, starCount,
                    index -> alignedServerTickPhases[index] = true);
        }

        require(count(acceptedFramePhases) == starCount,
                "sequential accepted frames visit every global star phase");
        require(count(alignedServerTickPhases) == 1,
                "server tick samples aligned to the period repeat one phase");
    }

    private static int count(boolean[] values) {
        int count = 0;
        for (boolean value : values) if (value) count++;
        return count;
    }

    private static void pausesAndRepeatedTimesAreBounded() {
        LimboEffectClock clock = new LimboEffectClock(20);
        require(clock.advance(0), "pause test starts with an update");
        long afterPause = Long.MAX_VALUE / 2;
        require(clock.advance(afterPause), "long pause refills the clock");
        require(clock.advance(afterPause), "long pause allows one spare update");
        require(!clock.advance(afterPause), "long pause cannot create a burst larger than two");
        require(!clock.advance(afterPause), "repeated same-time calls remain rejected");
        require(clock.frame() == 3, "only accepted pause-test calls advance the frame");
    }

    private static void backwardsTimeDoesNotMintCredit() {
        LimboEffectClock clock = new LimboEffectClock(1_000_000_000);
        require(clock.advance(100), "high-rate clock emits its first initial permit");
        require(clock.advance(100), "high-rate clock emits its second initial permit");
        require(!clock.advance(99), "backwards time is rejected");
        require(!clock.advance(100), "backwards time cannot mint a permit");
        require(clock.frame() == 2, "backwards time does not advance the frame");
    }

    private static void nanoTimeWrapIsSafe() {
        LimboEffectClock clock = new LimboEffectClock(20);
        long start = Long.MAX_VALUE - 25_000_000L;
        require(clock.advance(start), "wrap test starts with an update");
        require(clock.advance(start + FIFTY_MILLIS), "forward nanoTime wrap preserves elapsed time");
        require(clock.frame() == 2, "wrapped time advances the frame once");
    }

    private static void resetRestoresInitialState() {
        LimboEffectClock clock = new LimboEffectClock(20);
        require(clock.advance(0), "reset test starts with an update");
        clock.reset();
        require(clock.frame() == 0, "reset clears the global frame phase");
        require(clock.advance(-100), "reset restores the immediate initial permit");
        require(clock.advance(-100), "reset restores the spare initial permit");
        require(!clock.advance(-100), "reset still bounds same-time emissions");
        require(clock.frame() == 2, "reset frames count accepted calls after reset only");
    }

    private static void oneHertzWorks() {
        LimboEffectClock clock = new LimboEffectClock(1);
        require(clock.advance(0), "1 Hz clock emits immediately");
        require(clock.advance(0), "initial capacity permits one spare 1 Hz update");
        require(!clock.advance(0), "1 Hz clock rejects a third same-time update");
        require(!clock.advance(999_999_999L), "1 Hz clock waits for a full second");
        require(clock.advance(1_000_000_000L), "1 Hz clock emits after one second");
        require(clock.frame() == 3, "1 Hz frame count reflects accepted calls");
    }

    private static void rejectsInvalidRates() {
        requireThrows(() -> new LimboEffectClock(0), "zero rate is invalid");
        requireThrows(() -> new LimboEffectClock(-1), "negative rate is invalid");
        requireThrows(() -> new LimboEffectClock(1_000_000_001), "rate above nanosecond precision is invalid");
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
