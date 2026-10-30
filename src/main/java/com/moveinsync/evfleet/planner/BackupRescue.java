package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.Trip;
import com.moveinsync.evfleet.planner.TimelineBuilder.ChargingStrategy;
import com.moveinsync.evfleet.planner.TripLegs.DirectFailure;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Local search for coverage: each backup trip is offered to a nearby cab, which may hand at most two of its overlapping trips to other cabs. A move is kept
 * only when every affected cab's day can still be served safely; otherwise all of them are restored.
 */
final class BackupRescue {

    private static final int CANDIDATE_CABS = 20;

    private static final int MAX_MOVED_TRIPS = 2;

    /** A cab's day before a move, so the move can be undone. */
    private record Snapshot(FleetSchedule.CabDay day, List<Trip> trips, List<Action> actions) {

        /** Copies a cab's current trips and actions. */
        static Snapshot of(FleetSchedule.CabDay day) {
            return new Snapshot(day, List.copyOf(day.trips), List.copyOf(day.actions));
        }
    }

    private final PlanningRun run;

    /** Works on the shared state of one solve. */
    BackupRescue(PlanningRun run) {
        this.run = run;
    }

    /** Tries to cover each backup trip, in time order, until the deadline. */
    void rescueBackups() {
        for (var trip : run.trips) {
            if (run.outOfTime()) {
                return;
            }
            if (run.schedule.assignee(trip.id()) == null && !run.notTried(trip) && !run.windowInfeasible(trip)) {
                tryRescue(trip);
            }
        }
    }

    /** Tries the nearest cabs as hosts for one backup trip; undoes failed attempts. */
    private boolean tryRescue(Trip backup) {
        for (var host : nearestCabs(backup, null)) {
            if (run.outOfTime()) {
                return false;
            }

            // The host must drop its trips that clash with the backup; skip hosts that would drop more than two.
            var moved = overlapping(host, backup);

            if (moved.size() > MAX_MOVED_TRIPS) {
                continue;
            }

            // Rebuild the host's day with the backup trip in and the clashing trips out.
            var hostTrips = FleetSchedule.sortedWith(host.trips, backup, moved);
            var hostActions = rebuild(host, hostTrips);

            if (hostActions == null) {
                continue;
            }

            // Commit the host's new day, remembering the old one so the move can be undone.
            var changed = new ArrayList<Snapshot>();
            changed.add(Snapshot.of(host));
            run.schedule.replace(host, hostTrips, hostActions);

            // Every dropped trip must find another cab; otherwise undo all changes, newest first.
            if (placeAll(moved, host, changed)) {
                return true;
            }
            for (int index = changed.size() - 1; index >= 0; index--) {
                var snapshot = changed.get(index);
                run.schedule.replace(snapshot.day(), snapshot.trips(), snapshot.actions());
            }
        }

        return false;
    }

    /** Gives each moved trip to some other nearby cab that is free at that time. */
    private boolean placeAll(List<Trip> moved, FleetSchedule.CabDay host, List<Snapshot> changed) {
        for (var trip : moved) {
            boolean placed = false;

            for (var receiver : nearestCabs(trip, host)) {
                // Only a cab with no clashing trip can take it, and its rebuilt day must stay safe.
                if (!overlapping(receiver, trip).isEmpty()) {
                    continue;
                }

                var receiverTrips = FleetSchedule.sortedWith(receiver.trips, trip, List.of());
                var receiverActions = rebuild(receiver, receiverTrips);

                if (receiverActions != null) {
                    changed.add(Snapshot.of(receiver));
                    run.schedule.replace(receiver, receiverTrips, receiverActions);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                return false;
            }
        }

        return true;
    }

    /** Cabs that could reach the trip's pickup in time, nearest first. */
    private List<FleetSchedule.CabDay> nearestCabs(Trip trip, FleetSchedule.CabDay excluded) {
        var candidates = new ArrayList<FleetSchedule.CabDay>();
        var distance = new LinkedHashMap<String, Long>();

        for (var day : run.schedule.days()) {
            if (day == excluded || day.vehicle.availableAt() > trip.start()) {
                continue;
            }

            var position = day.positionBefore(trip.start());
            var check = run.legs.checkDirect(day.vehicle, position, trip);

            if (check.failure() == DirectFailure.ROUTE_BLOCKED || check.failure() == DirectFailure.TIME) {
                continue;
            }
            distance.put(day.vehicle.id(), run.legs.driveUse(day.vehicle, position.location(), trip.origin()));
            candidates.add(day);
        }
        candidates.sort(Comparator.<FleetSchedule.CabDay>comparingLong(day -> distance.get(day.vehicle.id())).thenComparing(day -> day.vehicle.id()));

        return candidates.subList(0, Math.min(CANDIDATE_CABS, candidates.size()));
    }

    /** The cab's trips that overlap the given trip in time. */
    private static List<Trip> overlapping(FleetSchedule.CabDay day, Trip trip) {
        return day.trips.stream().filter(other -> other.start() < trip.start() + trip.durationS() && trip.start() < other.start() + other.durationS()).toList();
    }

    /** Rebuild a cab's day with a changed trip list; null when it cannot be served safely. */
    private List<Action> rebuild(FleetSchedule.CabDay day, List<Trip> trips) {
        run.schedule.calendar.release(day.actions);
        var built = run.timelines.build(day.vehicle, trips, run.schedule.calendar, ChargingStrategy.JUST_IN_TIME, run.deadline);
        run.schedule.calendar.reserve(day.actions);

        return built;
    }
}
