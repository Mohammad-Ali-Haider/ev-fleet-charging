package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Plan;
import java.util.Comparator;

/** Builds a trip assignment and charging schedule for one dataset snapshot. */
public interface Planner {

    /**
     * @param budgetSeconds
     *            wall-clock budget; on expiry the best safe plan so far is returned
     * @param previous
     *            the plan being repaired, whose assignments should change as little as possible; null for an initial plan
     */
    Plan solve(Dataset dataset, double budgetSeconds, Plan previous);

    /** The production planner: greedy construction plus local search, checked by the independent validator. */
    static Planner standard() {
        return new ValidatingPlanner(new GreedyPlanner());
    }

    /**
     * Plan quality, compared lexicographically: fewer backup trips first, then (when repairing) fewer changed assignments, then lower charging cost. No amount
     * of electricity saving outweighs a covered trip.
     */
    static Comparator<Plan> score(boolean repairing) {
        Comparator<Plan> byBackups = Comparator.comparingInt(Plan::backupCount);

        return repairing
            ? byBackups.thenComparingInt(Plan::changedAssignments).thenComparingLong(Plan::mutableCostMicros)
            : byBackups.thenComparingLong(Plan::mutableCostMicros);
    }
}
