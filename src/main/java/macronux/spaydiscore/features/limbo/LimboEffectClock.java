package macronux.spaydiscore.features.limbo;

/** A monotonic, real-time token bucket for limiting limbo effect updates. */
final class LimboEffectClock {
    private static final long CREDITS_PER_UPDATE = 1_000_000_000L;
    private static final long MAX_CREDIT = 2 * CREDITS_PER_UPDATE;

    private final long updatesPerSecond;
    private long credit;
    private long frame;
    private long lastNanos;
    private boolean started;

    LimboEffectClock(int updatesPerSecond) {
        if (updatesPerSecond <= 0 || updatesPerSecond > CREDITS_PER_UPDATE) {
            throw new IllegalArgumentException("updatesPerSecond must be between 1 and 1,000,000,000");
        }
        this.updatesPerSecond = updatesPerSecond;
        reset();
    }

    boolean advance(long nowNanos) {
        if (!started) {
            started = true;
            lastNanos = nowNanos;
        } else {
            long elapsed = nowNanos - lastNanos;
            if (elapsed < 0) return false;
            lastNanos = nowNanos;

            long missingCredit = MAX_CREDIT - credit;
            long nanosToFill = (missingCredit + updatesPerSecond - 1) / updatesPerSecond;
            if (elapsed >= nanosToFill) credit = MAX_CREDIT;
            else credit += elapsed * updatesPerSecond;
        }

        if (credit < CREDITS_PER_UPDATE) return false;
        credit -= CREDITS_PER_UPDATE;
        frame++;
        return true;
    }

    long frame() {
        return frame;
    }

    void reset() {
        // Two intervals allow one spare update for jitter while capping bursts at two.
        credit = MAX_CREDIT;
        frame = 0;
        lastNanos = 0;
        started = false;
    }
}
