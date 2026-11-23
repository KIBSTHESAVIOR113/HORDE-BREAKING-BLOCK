package io.github.mebahel.zombiehorde.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * REFERENCE: ordered list of horizontal waypoints toward a far target, longest step first.
 * The goal must try them IN ORDER inside the same attempt (findPath -> moveTo; next candidate on failure),
 * because Pathfinder#findPath fails beyond the mob's FOLLOW_RANGE (vanilla zombie: 35) or when a detour is needed.
 * If the target itself is within the first step, the exact target is the only/first candidate.
 */
public final class WaypointLadder {
    public record Point(int x, int z) {}

    public static final double[] DEFAULT_STEPS = {32.0, 24.0, 16.0, 8.0};

    private WaypointLadder() {}

    public static List<Point> candidates(double originX, double originZ, double targetX, double targetZ, double[] steps) {
        double dx = targetX - originX;
        double dz = targetZ - originZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        List<Point> out = new ArrayList<>();
        if (dist < 0.001) {
            return out;
        }
        if (dist <= steps[0]) {
            out.add(new Point((int) Math.floor(targetX), (int) Math.floor(targetZ)));
        }
        double ux = dx / dist;
        double uz = dz / dist;
        for (double step : steps) {
            double d = Math.min(step, dist);
            Point p = new Point((int) Math.floor(originX + ux * d), (int) Math.floor(originZ + uz * d));
            if (!out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }
}
