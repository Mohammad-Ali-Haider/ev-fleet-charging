package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.ActionType;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Tariff;
import com.moveinsync.evfleet.domain.Trip;
import com.moveinsync.evfleet.domain.Vehicle;
import java.util.ArrayList;
import java.util.List;

/**
 * The basic move of a cab: drive empty to a pickup and run the trip, keeping the battery at or above the floor the whole way.
 */
final class TripLegs {

    /** Where a cab is, when it is free, and how much energy it has. */
    record State(long time, String location, long energyWh) {

        /** A cab's starting state from its record. */
        static State of(Vehicle vehicle) {
            return new State(vehicle.availableAt(), vehicle.location(), vehicle.energyWh());
        }
    }

    /** Actions that take a cab from one state to a new one, and the electricity they cost. */
    record Leg(List<Action> actions, State end, long costMicros) {
    }

    /** Why a cab cannot reach a trip directly. */
    enum DirectFailure {
        NONE, TIME, ENERGY, ROUTE_BLOCKED
    }

    /** Pickup check that builds no actions, used for the assignment matrix. */
    record DirectCheck(DirectFailure failure, long deadheadWh, long energyAfterWh) {
    }

    private final Dataset dataset;

    private final TravelProvider travel;

    private final EnergyModel energy;

    /** Uses the travel and energy models of this dataset. */
    TripLegs(Dataset dataset, TravelProvider travel, EnergyModel energy) {
        this.dataset = dataset;
        this.travel = travel;
        this.energy = energy;
    }

    /** The 15% battery floor in Wh. */
    long floor(Vehicle vehicle) {
        return energy.floor(vehicle);
    }

    /** Energy the trip itself uses for this cab. */
    long tripUse(Vehicle vehicle, Trip trip) {
        return energy.consumption(vehicle, trip.distanceM(), dataset);
    }

    /** Energy to drive empty between two locations. */
    long driveUse(Vehicle vehicle, String origin, String destination) {
        return energy.consumption(vehicle, travel.route(origin, destination).metres(), dataset);
    }

    /** Can the cab reach the pickup in time and finish above the floor? Builds no actions. */
    DirectCheck checkDirect(Vehicle vehicle, State state, Trip trip) {
        if (travel.isBlocked(state.location(), trip.origin())) {
            return new DirectCheck(DirectFailure.ROUTE_BLOCKED, 0, 0);
        }

        var route = travel.route(state.location(), trip.origin());

        if (state.time() + route.seconds() > trip.start()) {
            return new DirectCheck(DirectFailure.TIME, 0, 0);
        }

        long deadhead = energy.consumption(vehicle, route.metres(), dataset);
        long after = state.energyWh() - deadhead - tripUse(vehicle, trip);

        if (after < energy.floor(vehicle)) {
            return new DirectCheck(DirectFailure.ENERGY, deadhead, after);
        }

        return new DirectCheck(DirectFailure.NONE, deadhead, after);
    }

    /** Drive straight to the pickup and run the trip, or null when time, energy or the route forbids it. */
    Leg direct(Vehicle vehicle, State state, Trip trip) {
        var check = checkDirect(vehicle, state, trip);

        if (check.failure() != DirectFailure.NONE) {
            return null;
        }

        var route = travel.route(state.location(), trip.origin());
        long depart = trip.start() - route.seconds();
        long atPickup = state.energyWh() - check.deadheadWh();
        var actions = new ArrayList<Action>(3);

        if (depart > state.time()) {
            actions.add(new Action(ActionType.WAIT, vehicle.id(), state.time(), depart, state.location(), state.location(), state.energyWh(), state.energyWh(),
                null, null, 0, 0));
        }
        if (route.seconds() > 0) {
            actions.add(new Action(ActionType.DRIVE, vehicle.id(), depart, trip.start(), state.location(), trip.origin(), state.energyWh(), atPickup, null,
                null, 0, 0));
        }
        actions.add(new Action(ActionType.TRIP, vehicle.id(), trip.start(), trip.start() + trip.durationS(), trip.origin(), trip.destination(), atPickup,
            check.energyAfterWh(), trip.id(), null, 0, 0));

        return new Leg(actions, new State(trip.start() + trip.durationS(), trip.destination(), check.energyAfterWh()), 0);
    }

    /** Cheapest tariff overlapping [from, to); unbounded when the window is empty. */
    long cheapestPrice(long from, long to) {
        long cheapest = Long.MAX_VALUE;

        for (Tariff tariff : dataset.tariffs()) {
            if (tariff.start() < to && from < tariff.end()) {
                cheapest = Math.min(cheapest, tariff.microsPerKwh());
            }
        }

        return cheapest;
    }
}
