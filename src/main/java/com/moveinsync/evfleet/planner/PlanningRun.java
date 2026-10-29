package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Trip;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared state of one solve: the inputs, the schedule being built, and the deadline every phase checks. Discarded afterwards.
 */
final class PlanningRun {

    final Dataset dataset;

    /** Trip to cab in the plan being repaired; empty for an initial plan. */
    final Map<String, String> preferred;

    final long deadline;

    final EnergyModel energy;

    final TripLegs legs;

    final ChargeStops stops;

    final TimelineBuilder timelines;

    final FleetSchedule schedule;

    /** All trips by start time. */
    final List<Trip> trips;

    private final Set<String> notTried = new HashSet<>();

    private boolean exhausted;

    /** Builds the helpers and an empty schedule for one solve. */
    PlanningRun(Dataset dataset, Map<String, String> preferred, long deadline, EnergyModel energy) {
        this.dataset = dataset;
        this.preferred = preferred;
        this.deadline = deadline;
        this.energy = energy;

        var travel = new OfflineTravel(dataset);
        this.legs = new TripLegs(dataset, travel, energy);
        this.stops = new ChargeStops(dataset, travel, energy, legs);
        this.timelines = new TimelineBuilder(legs, stops);
        this.schedule = new FleetSchedule(dataset);

        var ordered = new ArrayList<>(dataset.trips());
        ordered.sort(Comparator.comparingLong(Trip::start).thenComparing(Trip::id));
        this.trips = ordered;
    }

    /** True once the deadline has passed; stays true so later phases stop too. */
    boolean outOfTime() {
        if (System.nanoTime() >= deadline) {
            exhausted = true;
        }

        return exhausted;
    }

    /** True if any phase ran out of time. */
    boolean exhausted() {
        return exhausted;
    }

    /** True if the trip cannot finish by its latest allowed time. */
    boolean windowInfeasible(Trip trip) {
        return trip.start() + trip.durationS() > Math.min(trip.latestFinish(), dataset.end());
    }

    /** Give the trip to a backup because the budget ran out before it was tried. */
    void skip(Trip trip) {
        schedule.markBackup(trip);
        notTried.add(trip.id());
    }

    /** True if the trip was skipped for lack of time. */
    boolean notTried(Trip trip) {
        return notTried.contains(trip.id());
    }
}
