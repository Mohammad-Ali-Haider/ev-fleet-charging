package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.ActionType;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Trip;
import com.moveinsync.evfleet.domain.Vehicle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable working state of one planning run: each cab's ordered trips, its timed actions, and the shared charger calendar. Replacing a cab's day keeps all
 * three consistent.
 */
final class FleetSchedule {

    private static final Comparator<Trip> BY_START = Comparator.comparingLong(Trip::start).thenComparing(Trip::id);

    /** One cab's planned day. */
    static final class CabDay {

        final Vehicle vehicle;

        final List<Trip> trips = new ArrayList<>();

        final List<Action> actions = new ArrayList<>();

        TripLegs.State end;

        /** An empty day starting from the cab's current state. */
        CabDay(Vehicle vehicle) {
            this.vehicle = vehicle;
            this.end = TripLegs.State.of(vehicle);
        }

        /** Total charging cost of this cab's day. */
        long chargingCostMicros() {
            long cost = 0;

            for (var action : actions) {
                cost += action.costMicros();
            }

            return cost;
        }

        /** True if this cab charges at least once. */
        boolean charges() {
            return actions.stream().anyMatch(action -> action.kind() == ActionType.CHARGE);
        }

        /** Location and free time just before {@code time}: after the last trip that ends by then. */
        TripLegs.State positionBefore(long time) {
            var position = TripLegs.State.of(vehicle);

            for (var action : actions) {
                if (action.kind() == ActionType.TRIP && action.end() <= time) {
                    position = new TripLegs.State(action.end(), action.destination(), action.afterWh());
                }
            }

            return position;
        }
    }

    private final Map<String, CabDay> days = new LinkedHashMap<>();

    private final Map<String, String> assignments = new LinkedHashMap<>();

    final ChargerCalendar calendar;

    /** One empty day per cab, and a calendar with each plug's outages. */
    FleetSchedule(Dataset dataset) {
        calendar = new ChargerCalendar(dataset);

        var vehicles = new ArrayList<>(dataset.vehicles());
        vehicles.sort(Comparator.comparing(Vehicle::id));
        for (var vehicle : vehicles) {
            days.put(vehicle.id(), new CabDay(vehicle));
        }
    }

    /** All cab days, in id order. */
    List<CabDay> days() {
        return List.copyOf(days.values());
    }

    /** One cab's day, or null if unknown. */
    CabDay day(String vehicleId) {
        return days.get(vehicleId);
    }

    /** Record a backup (null) or keep the assignment that an appended leg created. */
    void markBackup(Trip trip) {
        assignments.put(trip.id(), null);
    }

    /** The cab assigned to a trip, or null for a backup. */
    String assignee(String tripId) {
        return assignments.get(tripId);
    }

    /** Trip to cab for every trip decided so far. */
    Map<String, String> assignments() {
        return assignments;
    }

    /** Add a leg to the end of a cab's day during chronological construction. */
    void append(CabDay day, Trip trip, TripLegs.Leg leg) {
        day.trips.add(trip);
        day.actions.addAll(leg.actions());
        day.end = leg.end();
        calendar.reserve(leg.actions());
        assignments.put(trip.id(), day.vehicle.id());
    }

    /** Add an opportunistic charging session, with no trip, to the end of a cab's day. */
    void appendIdle(CabDay day, TripLegs.Leg leg) {
        day.actions.addAll(leg.actions());
        day.end = leg.end();
        calendar.reserve(leg.actions());
    }

    /** Swap a cab's whole day for a rebuilt one with the given trips. */
    void replace(CabDay day, List<Trip> trips, List<Action> actions) {
        calendar.release(day.actions);
        for (var old : day.trips) {
            if (day.vehicle.id().equals(assignments.get(old.id()))) {
                assignments.put(old.id(), null);
            }
        }

        var ordered = new ArrayList<>(trips);
        ordered.sort(BY_START);
        day.trips.clear();
        day.trips.addAll(ordered);
        day.actions.clear();
        day.actions.addAll(actions);
        day.end = endState(day);
        calendar.reserve(actions);
        for (var trip : ordered) {
            assignments.put(trip.id(), day.vehicle.id());
        }
    }

    private static TripLegs.State endState(CabDay day) {
        if (day.actions.isEmpty()) {
            return TripLegs.State.of(day.vehicle);
        }

        var last = day.actions.get(day.actions.size() - 1);

        return new TripLegs.State(last.end(), last.destination(), last.afterWh());
    }

    /** A trip list with one trip added and some removed, sorted by start. */
    static List<Trip> sortedWith(List<Trip> trips, Trip added, List<Trip> removed) {
        var result = new ArrayList<Trip>(trips.size() + 1);

        for (var trip : trips) {
            if (!removed.contains(trip)) {
                result.add(trip);
            }
        }
        if (added != null) {
            result.add(added);
        }
        result.sort(BY_START);

        return result;
    }

    /** Every cab's actions in one list. */
    List<Action> allActions() {
        var result = new ArrayList<Action>();

        for (var day : days.values()) {
            result.addAll(day.actions);
        }

        return result;
    }
}
