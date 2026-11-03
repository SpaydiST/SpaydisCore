package macronux.spaydiscore.features.limbo;

/** Pure geometry for the circular Limbo boundary. */
final class LimboGeometry {

    private LimboGeometry() {
    }

    /** Keep the player's 0.6-block body inside even a one-block world border. */
    static double returnCoordinateInsideBorder(double coordinate, double center, double size) {
        if (!Double.isFinite(coordinate) || !Double.isFinite(center)
                || !Double.isFinite(size) || size <= 0.62) return Double.NaN;
        double reach = size * 0.5 - 0.31;
        return Math.max(center - reach, Math.min(center + reach, coordinate));
    }

    static double reactiveStarRadius(long stillTicks, double movementSquared) {
        if (movementSquared > 0.0225 && movementSquared < 576.0) return 1.25;
        double settled = Math.max(0.0, Math.min(1.0, (stillTicks - 100.0) / 200.0));
        return 1.0 - settled * 0.35;
    }

    static boolean trailActive(long startedNanos, long nowNanos) {
        // Dust at scale 0.45 lives up to 18 client ticks; leave one second to fade.
        return nowNanos - startedNanos >= 0L && nowNanos - startedNanos < 9_000_000_000L;
    }

    static int starSector(double coordinate) {
        return (int) Math.floor(coordinate / 32.0);
    }

    static boolean starWithinView(double dx, double dz) {
        return dx * dx + dz * dz <= 34.0 * 34.0;
    }

    static WrappedPoint wrap(
            double x,
            double z,
            double centerX,
            double centerZ,
            double innerRadius,
            double outerRadius) {
        return wrap(x, z, centerX, centerZ, innerRadius, outerRadius, 0.0);
    }

    static WrappedPoint wrap(
            double x,
            double z,
            double centerX,
            double centerZ,
            double innerRadius,
            double outerRadius,
            double safeMargin) {
        if (!Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(centerX) || !Double.isFinite(centerZ)) {
            return null;
        }

        double inner = Math.max(0.0, Double.isFinite(innerRadius) ? innerRadius : 0.0);
        double outer = Math.max(0.0, Double.isFinite(outerRadius) ? outerRadius : 0.0);
        if (outer < inner) {
            double swap = inner;
            inner = outer;
            outer = swap;
        }

        double dx = x - centerX;
        double dz = z - centerZ;
        double radius = Math.hypot(dx, dz);
        if (!(radius > outer)) {
            return null;
        }

        double margin = Math.max(0.0, Double.isFinite(safeMargin) ? safeMargin : 0.0);
        margin = Math.min(margin, Math.max(0.0, (outer - inner) * 0.5));
        double safeInner = inner + margin;
        double safeOuter = Math.max(safeInner, outer - margin);
        double wrappedRadius = Math.max(safeInner,
                Math.min(safeOuter, outer - (radius - outer)));
        double angle = Math.atan2(dz, dx) + Math.PI;
        return new WrappedPoint(
                centerX + Math.cos(angle) * wrappedRadius,
                centerZ + Math.sin(angle) * wrappedRadius);
    }

    record WrappedPoint(double x, double z) {
    }
}
