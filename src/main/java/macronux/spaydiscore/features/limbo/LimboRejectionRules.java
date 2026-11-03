package macronux.spaydiscore.features.limbo;

/** Small, dependency-free bounds used by the Limbo rejection animation. */
public final class LimboRejectionRules {

    private LimboRejectionRules() {
    }

    static double progress(int tick, int durationTicks) {
        if (durationTicks <= 1) return tick <= 0 ? 0.0 : 1.0;
        if (tick <= 0) return 0.0;
        return Math.min(1.0, tick / (double) (durationTicks - 1));
    }

    static double headFlightRadius(double randomUnit) {
        if (!Double.isFinite(randomUnit)) randomUnit = 0.0;
        return 3.0 + 1.0 * Math.max(0.0, Math.min(1.0, randomUnit));
    }
}
