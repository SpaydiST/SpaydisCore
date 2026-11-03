package macronux.spaydiscore.features.limbo;

public final class LimboVisualTest {
    public static void run() {
        require(Math.abs(LimboGeometry.returnCoordinateInsideBorder(0, 100, 16) - 92.31) < 1.0e-9,
                "outside death/spawn coordinates project inside the current border");
        require(LimboGeometry.returnCoordinateInsideBorder(100, 100, 16) == 100,
                "inside coordinates are preserved");
        for (double center : new double[] {-100.25, -0.1, 0, 100.75}) {
            double projected = LimboGeometry.returnCoordinateInsideBorder(center + 1000, center, 1);
            require(Math.abs(projected - center) <= 0.1900001,
                    "one-block borders retain room for the player's body");
        }
        require(Double.isNaN(LimboGeometry.returnCoordinateInsideBorder(0, 0, 0.5)),
                "a border too small for the player cannot provide a return coordinate");
        require(Double.isNaN(LimboGeometry.returnCoordinateInsideBorder(Double.NaN, 0, 16)),
                "invalid return coordinates are rejected");
        long start = 12_000_000_000L;
        require(LimboGeometry.trailActive(start, start), "trail starts immediately");
        require(LimboGeometry.trailActive(start, start + 8_999_999_999L), "trail emits during its nine-second window");
        require(!LimboGeometry.trailActive(start, start + 9_000_000_000L), "last second is reserved for dust to fade");
        require(!LimboGeometry.trailActive(start, start + 10_000_000_000L), "no emission at ten seconds");
        require(!LimboGeometry.trailActive(start, start + 60_000_000_000L), "lag cannot extend the trail");
        require(!LimboGeometry.trailActive(start, start - 1L), "future start cannot emit");
        long wrapped = Long.MAX_VALUE - 1_000_000_000L;
        require(LimboGeometry.trailActive(wrapped, wrapped + 5_000_000_000L), "nano clock wrap is safe");
        require(LimboGeometry.reactiveStarRadius(300L, 0.0) == 0.65, "stationary stars draw closer");
        require(LimboGeometry.reactiveStarRadius(0L, 0.1) == 1.25, "running spreads stars out");
        require(LimboGeometry.reactiveStarRadius(0L, 1000) == 1.0, "teleport is not running");
        require(LimboGeometry.starSector(-0.01) == -1, "negative coordinates use floor sectors");
        require(LimboGeometry.starSector(-32) == -1 && LimboGeometry.starSector(-32.01) == -2,
                "sector edges work in negative coordinates");
        require(LimboGeometry.starWithinView(34.0, 0.0), "view boundary is included");
        require(!LimboGeometry.starWithinView(34.01, 0.0), "distant stars are culled");
        require(!LimboGeometry.starWithinView(Double.NaN, 0.0), "invalid positions cannot emit");
        for (double viewer : new double[] {-32.01, -0.01, 0.0, 31.99, 32.0}) {
            for (double offset : new double[] {-34, -32, 0, 32, 34}) {
                int distance = Math.abs(LimboGeometry.starSector(viewer + offset)
                        - LimboGeometry.starSector(viewer));
                require(distance <= 2, "sector lookup includes every visible base point");
            }
        }
        for (int players : new int[] {0, 1, 2, 20, 1000, Integer.MAX_VALUE}) {
            int low = StarQuality.LOW.globalPeriod(players);
            int normal = StarQuality.NORMAL.globalPeriod(players);
            int high = StarQuality.HIGH.globalPeriod(players);
            require(low >= normal && normal >= high && high > 0, "quality periods stay positive and ordered");
        }
        for (int budget : new int[] {0, 1, 8, 64, 128, 1000}) {
            int low = StarQuality.LOW.localAnchors(budget);
            int normal = StarQuality.NORMAL.localAnchors(budget);
            int high = StarQuality.HIGH.localAnchors(budget);
            require(0 <= low && low <= normal && normal <= high && high <= 128, "local quality is bounded");
        }
        require(StarQuality.parse("HIGH") == StarQuality.HIGH, "quality parsing ignores case");
        require(StarQuality.parse("oops") == null && StarQuality.parse(null) == null, "invalid quality is rejected");
        System.out.println("Limbo visual timing and quality checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
