package macronux.spaydiscore.config;

/** Shared bounds and overflow guards for values loaded from plugin configuration. */
public final class ConfigGuard {

    private ConfigGuard() {
    }

    public static long nonNegativeLong(long value, long fallback) {
        return value >= 0 ? value : Math.max(0L, fallback);
    }

    public static int atLeastOne(int value, int fallback) {
        return value > 0 ? value : Math.max(1, fallback);
    }

    public static double finiteNonNegative(double value, double fallback) {
        if (Double.isFinite(value) && value >= 0.0) {
            return value;
        }
        return Double.isFinite(fallback) && fallback >= 0.0 ? fallback : 0.0;
    }

    public static double unitInterval(double value, double fallback) {
        double safe = Double.isFinite(value) ? value : fallback;
        if (!Double.isFinite(safe)) {
            safe = 0.0;
        }
        return Math.max(0.0, Math.min(1.0, safe));
    }

    public static int boundedNonNegative(int value, int fallback, int maximum) {
        int safeMaximum = Math.max(0, maximum);
        int safeFallback = Math.max(0, Math.min(safeMaximum, fallback));
        if (value < 0) return safeFallback;
        return Math.min(value, safeMaximum);
    }

    public static double boundedFiniteNonNegative(double value, double fallback, double maximum) {
        double safeMaximum = Double.isFinite(maximum) && maximum >= 0.0 ? maximum : 0.0;
        double safe = finiteNonNegative(value, fallback);
        return Math.min(safe, safeMaximum);
    }

    public static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }
}
