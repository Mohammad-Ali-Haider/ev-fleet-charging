package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Trip;
import com.moveinsync.evfleet.planner.ChargeStops.ChargeGoal;
import com.moveinsync.evfleet.planner.TripLegs.DirectFailure;
import com.moveinsync.evfleet.planner.TripLegs.Leg;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Greedy construction, batch by batch in time order.
 *
 * <p>
 * A batch is a run of trips that all overlap in time, so one cab can serve at most one of them. In each batch: (1) when repairing, trips stay with their
 * original cab if a charging stop makes that possible; (2) the rest are matched to cabs that can reach them directly, as a minimum-cost assignment (most trips
 * covered, then fewest changed assignments, then least empty driving); (3) trips still open try a just-in-time charging stop; (4) idle cabs top up for the work
 * ahead.
 */
final class BatchConstruction {

    // Hungarian is O(n^2 m); bigger batches use a greedy pass so time and cost tiers stay bounded.
    private static final int MAX_MATRIX_ROWS = 600;

    // Assignment cost tiers: an uncovered trip outweighs any number of changes, which outweigh any
    // empty driving. Pair costs stay below 1e9 + 1e3, so 600 rows never cross a tier.
    private static final long NOT_ALLOWED = 1_000_000_000_000_000L;

    private static final long CHANGED = 1_000_000_000_000L;

    private static final int CHARGE_PROBES = 40;

    private static final int CHARGE_PROBES_AFTER_FEASIBLE = 12;

    private final PlanningRun run;

    private final ChargingPasses charging;

    /** Works on the shared state of one solve. */
    BatchConstruction(PlanningRun run, ChargingPasses charging) {
        this.run = run;
        this.charging = charging;
    }

    /** Builds the first full schedule, batch by batch in time order. */
    void construct() {
        var batches = overlappingBatches();
        int remaining = run.trips.size();
        long remainingMetres = run.trips.stream().mapToLong(Trip::distanceM).sum();

        for (int index = 0; index < batches.size(); index++) {
            var batch = batches.get(index);
            remaining -= batch.size();
            remainingMetres -= batch.stream().mapToLong(Trip::distanceM).sum();

            // Out of time: every remaining trip goes to a backup, marked as not tried.
            if (run.outOfTime()) {
                batch.forEach(run::skip);
                continue;
            }

            // Trips that cannot finish by their latest allowed time go straight to backup.
            var open = new ArrayList<Trip>();

            for (var trip : batch) {
                if (run.windowInfeasible(trip)) {
                    run.schedule.markBackup(trip);
                } else {
                    open.add(trip);
                }
            }

            // Step 1 (repairs only): keep trips on their old cab by adding a charging stop. Step 2: match the rest to cabs that can drive there directly.
            var used = new HashSet<String>();
            var unmatched = assignDirect(keepWithCharge(open, used), used);

            // Step 3: trips no cab can reach directly try a cab that charges first; otherwise they go to backup.
            for (var trip : unmatched) {
                if (run.outOfTime()) {
                    run.skip(trip);
                } else if (!insertWithCharge(trip, used)) {
                    run.schedule.markBackup(trip);
                }
            }

            // Step 4: idle cabs that are running low go and charge before the next batch starts.
            long nextStart = index + 1 < batches.size() ? batches.get(index + 1).get(0).start() : run.dataset.end();
            charging.chargeIdleCabs(nextStart, remaining, remaining == 0 ? 0 : remainingMetres / remaining);
        }
    }

    /** Consecutive trips that all overlap in time; no cab can serve two trips of one batch. */
    private List<List<Trip>> overlappingBatches() {
        var batches = new ArrayList<List<Trip>>();
        var current = new ArrayList<Trip>();
        long earliestEnd = Long.MAX_VALUE;

        for (var trip : run.trips) {
            if (!current.isEmpty() && trip.start() >= earliestEnd) {
                batches.add(current);
                current = new ArrayList<>();
                earliestEnd = Long.MAX_VALUE;
            }
            current.add(trip);
            earliestEnd = Math.min(earliestEnd, trip.start() + trip.durationS());
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }

        return batches;
    }

    /**
     * When repairing, a trip whose original cab is now short of charge stays with that cab if a charging stop fits before the pickup; only trips that truly
     * cannot stay go to matching.
     */
    private List<Trip> keepWithCharge(List<Trip> open, Set<String> used) {
        if (run.preferred.isEmpty()) {
            return open;
        }

        var rest = new ArrayList<Trip>();

        for (var trip : open) {
            var day = run.schedule.day(run.preferred.get(trip.id()));

            if (day != null && !used.contains(day.vehicle.id()) && !run.outOfTime()
                && run.legs.checkDirect(day.vehicle, day.end, trip).failure() == DirectFailure.ENERGY) {
                // The old cab is now short of charge: can it charge and still make this pickup?
                var leg = run.stops.viaCharger(day.vehicle, day.end, trip, ChargeGoal.MINIMUM, run.schedule.calendar, run.deadline);

                if (leg != null) {
                    run.schedule.append(day, trip, leg);
                    used.add(day.vehicle.id());
                    continue;
                }
            }
            rest.add(trip);
        }

        return rest;
    }

    /** Cost of giving a trip to a cab directly; NOT_ALLOWED if it cannot reach it safely. */
    private long pairCost(FleetSchedule.CabDay day, Trip trip) {
        var check = run.legs.checkDirect(day.vehicle, day.end, trip);

        if (check.failure() != DirectFailure.NONE) {
            return NOT_ALLOWED;
        }

        String wanted = run.preferred.get(trip.id());
        long changed = wanted != null && !wanted.equals(day.vehicle.id()) ? CHANGED : 0;
        // Least empty driving first; among equals, the cab left with the least spare charge (best fit).
        long spare = check.energyAfterWh() - run.legs.floor(day.vehicle);

        return changed + check.deadheadWh() * 1000 + spare / 1000;
    }

    /** Assigns trips that some cab can reach without charging; returns the rest in start order. */
    private List<Trip> assignDirect(List<Trip> open, Set<String> used) {
        if (open.isEmpty()) {
            return open;
        }

        var days = run.schedule.days();
        var costs = new long[open.size()][];
        var reachable = new ArrayList<Integer>();
        var seen = new boolean[days.size()];

        for (int row = 0; row < open.size(); row++) {
            costs[row] = new long[days.size()];
            for (int column = 0; column < days.size(); column++) {
                costs[row][column] = pairCost(days.get(column), open.get(row));
                if (costs[row][column] < NOT_ALLOWED && !seen[column]) {
                    seen[column] = true;
                    reachable.add(column);
                }
            }
        }

        // Pick the cheapest one-to-one matching of trips to cabs (Hungarian), or a greedy pass for huge batches.
        var chosen = open.size() <= MAX_MATRIX_ROWS ? optimalColumns(costs, reachable) : greedyColumns(costs);
        var unmatched = new ArrayList<Trip>();

        for (int row = 0; row < open.size(); row++) {
            int column = chosen[row];

            if (column < 0 || costs[row][column] >= NOT_ALLOWED) {
                unmatched.add(open.get(row));
                continue;
            }

            var day = days.get(column);
            var trip = open.get(row);
            // This cab drives empty to the pickup and runs the trip.
            run.schedule.append(day, trip, run.legs.direct(day.vehicle, day.end, trip));
            used.add(day.vehicle.id());
        }

        return unmatched;
    }

    /** Hungarian assignment over cabs that can reach at least one trip, padded so rows fit. */
    private int[] optimalColumns(long[][] costs, List<Integer> reachable) {
        int rows = costs.length;
        int columns = Math.max(rows, reachable.size());
        var matrix = new long[rows][columns];

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                matrix[row][column] = column < reachable.size() ? costs[row][reachable.get(column)] : NOT_ALLOWED;
            }
        }

        var assigned = AssignmentSolver.solve(matrix);
        var result = new int[rows];

        for (int row = 0; row < rows; row++) {
            result[row] = assigned[row] < reachable.size() ? reachable.get(assigned[row]) : -1;
        }

        return result;
    }

    /** In trip order, the cheapest free cab; used only for batches too large for the matrix. */
    private int[] greedyColumns(long[][] costs) {
        var result = new int[costs.length];
        var taken = new HashSet<Integer>();

        for (int row = 0; row < costs.length; row++) {
            int best = -1;

            for (int column = 0; column < costs[row].length; column++) {
                if (!taken.contains(column) && costs[row][column] < NOT_ALLOWED && (best < 0 || costs[row][column] < costs[row][best])) {
                    best = column;
                }
            }
            result[row] = best;
            if (best >= 0) {
                taken.add(best);
            }
        }

        return result;
    }

    /** Tries a charging stop before the trip with the nearest cabs that are free but short of charge. */
    private boolean insertWithCharge(Trip trip, Set<String> used) {
        String wanted = run.preferred.get(trip.id());
        var candidates = new ArrayList<FleetSchedule.CabDay>();
        var deadhead = new LinkedHashMap<String, Long>();

        for (var day : run.schedule.days()) {
            if (used.contains(day.vehicle.id())) {
                continue;
            }

            var check = run.legs.checkDirect(day.vehicle, day.end, trip);

            if (check.failure() == DirectFailure.ENERGY) {
                candidates.add(day);
                deadhead.put(day.vehicle.id(), check.deadheadWh());
            }
        }
        // Try the original cab first (repairs), then the nearest cabs.
        candidates.sort(Comparator.<FleetSchedule.CabDay>comparingInt(day -> day.vehicle.id().equals(wanted) ? 0 : 1).thenComparingLong(
            day -> deadhead.get(day.vehicle.id())).thenComparing(day -> day.vehicle.id()));

        FleetSchedule.CabDay bestDay = null;
        Leg bestLeg = null;
        int probes = 0;

        for (var day : candidates) {
            if (run.outOfTime() || probes >= CHARGE_PROBES || (bestLeg != null && probes >= CHARGE_PROBES_AFTER_FEASIBLE)) {
                break;
            }

            var leg = run.stops.viaCharger(day.vehicle, day.end, trip, ChargeGoal.upTo(EnergyModel.fastLimit(day.vehicle)), run.schedule.calendar,
                run.deadline);
            probes++;
            if (leg == null) {
                continue;
            }
            if (day.vehicle.id().equals(wanted)) {
                bestDay = day;
                bestLeg = leg;
                break;
            }
            if (bestLeg == null || leg.costMicros() < bestLeg.costMicros()) {
                bestDay = day;
                bestLeg = leg;
            }
        }
        if (bestLeg == null) {
            return false;
        }
        run.schedule.append(bestDay, trip, bestLeg);
        used.add(bestDay.vehicle.id());

        return true;
    }
}
