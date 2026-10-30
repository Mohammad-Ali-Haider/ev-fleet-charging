package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Dataset;
import java.util.ArrayList;
import java.util.Comparator;

/** A count of backups no plan can avoid, to show how far from optimal a plan might be. */
public final class LowerBound {

    /** A trip starting (+1) or ending (-1) at a moment. */
    private record Point(long at, int delta) {
    }

    /** Impossible trips plus the peak excess of overlapping trips over cabs. */
    public int calculate(Dataset dataset) {
        var model = new EnergyModel();
        var points = new ArrayList<Point>();
        int impossible = 0;

        for (var t : dataset.trips()) {
            boolean possible = t.start() + t.durationS() <= Math.min(t.latestFinish(), dataset.end())
                && dataset.vehicles().stream().anyMatch(v -> model.consumption(v, t.distanceM(), dataset) <= v.capacityWh() - model.floor(v));

            if (!possible) {
                impossible++;
            } else {
                points.add(new Point(t.start(), 1));
                points.add(new Point(t.start() + t.durationS(), -1));
            }
        }
        points.sort(Comparator.comparingLong(Point::at).thenComparingInt(Point::delta));

        int active = 0;
        int peak = 0;

        for (var p : points) {
            active += p.delta();
            peak = Math.max(peak, active);
        }

        return impossible + Math.max(0, peak - dataset.vehicles().size());
    }
}
