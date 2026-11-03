package macronux.spaydiscore.features.limbo;

import java.util.Locale;
import java.util.function.IntConsumer;

/** Per-player density within the shared adaptive star budget. */
public enum StarQuality {
    LOW(0.25, 4), NORMAL(1.0, 1), HIGH(1.5, 1);

    private final double localScale;
    private final int globalScale;

    StarQuality(double localScale, int globalScale) {
        this.localScale = localScale;
        this.globalScale = globalScale;
    }

    public int localAnchors(int adaptiveLimit) {
        return Math.max(0, Math.min(128, (int) Math.ceil(adaptiveLimit * localScale)));
    }

    public int globalPeriod(int players) {
        long period = 5L * Math.max(1, players) * globalScale;
        if (this == HIGH) period = Math.max(1L, period * 2L / 3L);
        return (int) Math.min(Integer.MAX_VALUE, period);
    }

    /** Visit only anchors due this tick, keeping the original index/period phase. */
    void forEachGlobalStar(int players, long tick, int starCount, IntConsumer action) {
        int period = globalPeriod(players);
        for (long index = Math.floorMod(tick, (long) period); index < starCount; index += period) {
            action.accept((int) index);
        }
    }

    public static StarQuality parse(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "low", "minimal" -> LOW;
            case "normal" -> NORMAL;
            case "high" -> HIGH;
            default -> null;
        };
    }
}
