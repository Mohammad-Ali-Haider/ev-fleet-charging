package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.ActionType;
import com.moveinsync.evfleet.domain.Charger;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.Trip;
import com.moveinsync.evfleet.domain.Vehicle;
import com.moveinsync.evfleet.planner.TripLegs.Leg;
import com.moveinsync.evfleet.planner.TripLegs.State;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Finds a free charger slot for a cab: either a stop before a trip (charge, then drive to the pickup) or an idle session with no trip attached.
 */
final class ChargeStops {

    /**
     * How much to charge at a stop. The stop always covers the onward drive and the trip; on top of that it aims for {@code laterNeedWh} more (the rest of the
     * day) and at least {@code atLeastWh} in absolute charge, capped by the battery. {@link #MINIMUM} charges only for the next trip.
     */
    record ChargeGoal(long laterNeedWh, long atLeastWh) {

        static final ChargeGoal MINIMUM = new ChargeGoal(0, 0);

        /** Charge for this trip plus everything later in the day. */
        static ChargeGoal restOfDay(long laterNeedWh) {
            return new ChargeGoal(laterNeedWh, 0);
        }

        /** Charge to at least this absolute level. */
        static ChargeGoal upTo(long atLeastWh) {
            return new ChargeGoal(0, atLeastWh);
        }

        /** True when the goal is only the next trip. */
        boolean minimal() {
            return laterNeedWh == 0 && atLeastWh == 0;
        }
    }

    /** Plugs that share a location, connector and power behave identically apart from bookings. */
    private record ChargerGroup(String location, String connector, int powerW, List<String> plugIds) {
    }

    /** A bookable session; {@code gainWh} is the extra charge it leaves at the pickup versus driving there directly. */
    private record ChargeOption(ChargerGroup group, String plugId, long start, long seconds, long atHubWh, long gainWh, EnergyModel.Charge charge) {
    }

    private final Dataset dataset;

    private final TravelProvider travel;

    private final EnergyModel energy;

    private final TripLegs legs;

    private final List<ChargerGroup> groups;

    /** Groups identical plugs at the same location so each group is evaluated once. */
    ChargeStops(Dataset dataset, TravelProvider travel, EnergyModel energy, TripLegs legs) {
        this.dataset = dataset;
        this.travel = travel;
        this.energy = energy;
        this.legs = legs;

        var byKey = new LinkedHashMap<String, List<Charger>>();
        var chargers = new ArrayList<>(dataset.chargers());
        chargers.sort(Comparator.comparing(Charger::id));
        for (var charger : chargers) {
            byKey.computeIfAbsent(charger.location() + '\u0000' + charger.connector() + '\u0000' + charger.powerW(), key -> new ArrayList<>()).add(charger);
        }

        groups = byKey.values().stream().map(members -> new ChargerGroup(members.get(0).location(), members.get(0).connector(), members.get(0).powerW(),
            members.stream().map(Charger::id).toList())).toList();
    }

    /**
     * Charge at a hub, then drive to the pickup and run the trip.
     *
     * @return the best feasible leg, or null when no compatible charger fits before the pickup
     */
    Leg viaCharger(Vehicle vehicle, State state, Trip trip, ChargeGoal goal, ChargerCalendar calendar, long deadline) {
        long floor = energy.floor(vehicle);
        long tripUse = legs.tripUse(vehicle, trip);
        // Charge the cab would have at the pickup without stopping; a stop must beat this to be worth it.
        long directAtPickup = travel.isBlocked(state.location(), trip.origin())
            ? Long.MIN_VALUE / 4
            : state.energyWh() - legs.driveUse(vehicle, state.location(), trip.origin());
        ChargeOption best = null;

        for (var group : groups) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            if (!group.connector().equals(vehicle.connector()) || travel.isBlocked(state.location(), group.location())
                || travel.isBlocked(group.location(), trip.origin())) {
                continue;
            }

            var approach = travel.route(state.location(), group.location());
            var onward = travel.route(group.location(), trip.origin());
            long atHub = state.energyWh() - energy.consumption(vehicle, approach.metres(), dataset);
            long earliest = state.time() + approach.seconds();
            long latest = trip.start() - onward.seconds();

            if (atHub < floor || earliest >= latest) {
                continue;
            }

            long onwardUse = energy.consumption(vehicle, onward.metres(), dataset);
            // Charge at least enough for the onward drive and the trip, more if the goal asks for the rest of the day.
            long minimum = floor + onwardUse + tripUse;
            long fill = Math.max(minimum, Math.min(vehicle.capacityWh(), Math.max(minimum + goal.laterNeedWh(), goal.atLeastWh())));
            var option = bestOption(vehicle, group, atHub, minimum, fill, earliest, latest, calendar, onwardUse, directAtPickup);

            if (option != null && (best == null || better(option, best, goal.minimal()))) {
                best = option;
            }
        }

        return best == null ? null : chargeLeg(vehicle, state, trip, best);
    }

    /** The biggest target in {fill, midpoint, minimum} that fits a plug, at its cheapest start. */
    private ChargeOption bestOption(Vehicle vehicle, ChargerGroup group, long atHub, long minimum, long fill, long earliest, long latest,
        ChargerCalendar calendar, long onwardUse, long directAtPickup) {
        var targets = new ArrayList<Long>(3);
        targets.add(fill);
        if (fill > minimum) {
            targets.add((fill + minimum) / 2);
            targets.add(minimum);
        }

        for (long target : targets) {
            if (target <= atHub || target > vehicle.capacityWh()) {
                continue;
            }

            long seconds = energy.duration(vehicle, atHub, target, group.powerW(), dataset);
            var charges = new HashMap<Long, EnergyModel.Charge>();
            ChargeOption cheapest = null;

            for (var plug : group.plugIds()) {
                for (long start : calendar.starts(plug, earliest, latest, seconds, dataset.tariffs())) {
                    var charge = charges.computeIfAbsent(start, at -> energy.charge(vehicle, atHub, at, seconds, group.powerW(), dataset));

                    if (cheapest == null || charge.costMicros() < cheapest.charge().costMicros()
                        || (charge.costMicros() == cheapest.charge().costMicros() && start + seconds < cheapest.start() + cheapest.seconds())) {
                        long gain = charge.energyWh() - onwardUse - directAtPickup;
                        cheapest = new ChargeOption(group, plug, start, seconds, atHub, gain, charge);
                    }
                }
            }
            if (cheapest != null) {
                return cheapest;
            }
        }

        return null;
    }

    /**
     * A minimal stop compares total cost. A filling stop compares price per kWh gained at the pickup, so the energy burnt on a detour to a distant hub counts
     * against it, then prefers more gain.
     */
    private boolean better(ChargeOption candidate, ChargeOption incumbent, boolean minimal) {
        if (minimal) {
            if (candidate.charge().costMicros() != incumbent.charge().costMicros()) {
                return candidate.charge().costMicros() < incumbent.charge().costMicros();
            }
        } else {
            long candidateGain = Math.max(1, candidate.gainWh());
            long incumbentGain = Math.max(1, incumbent.gainWh());
            int byPrice = Long.compare(Math.multiplyExact(candidate.charge().costMicros(), incumbentGain),
                Math.multiplyExact(incumbent.charge().costMicros(), candidateGain));

            if (byPrice != 0) {
                return byPrice < 0;
            }
            if (candidateGain != incumbentGain) {
                return candidateGain > incumbentGain;
            }
        }

        return candidate.start() + candidate.seconds() < incumbent.start() + incumbent.seconds();
    }

    /** Charging session followed by the drive to the pickup and the trip. */
    private Leg chargeLeg(Vehicle vehicle, State state, Trip trip, ChargeOption option) {
        var session = sessionLeg(vehicle, state, option);
        var rest = legs.direct(vehicle, session.end(), trip);

        if (rest == null) {
            // The target already covers the onward drive and trip; this would be an energy-model bug.
            throw new DomainException("INTERNAL_CHARGE_TARGET_SHORT", 500);
        }

        var actions = new ArrayList<>(session.actions());
        actions.addAll(rest.actions());

        return new Leg(actions, rest.end(), session.costMicros());
    }

    /**
     * Send an idle cab to charge towards {@code targetWh}, with no trip attached. Sessions that end by {@code readyBy} are preferred so the cab is back in
     * service for the next trips; among those, more charge and then lower cost win.
     *
     * @return the drive, wait and charge actions, or null when no compatible plug is reachable
     */
    Leg idleCharge(Vehicle vehicle, State state, long targetWh, long readyBy, ChargerCalendar calendar, long deadline) {
        long floor = energy.floor(vehicle);
        Leg best = null;
        boolean bestReady = false;

        for (var group : groups) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            if (!group.connector().equals(vehicle.connector()) || travel.isBlocked(state.location(), group.location())) {
                continue;
            }

            var approach = travel.route(state.location(), group.location());
            long atHub = state.energyWh() - energy.consumption(vehicle, approach.metres(), dataset);
            long earliest = state.time() + approach.seconds();

            if (atHub < floor || earliest >= dataset.end()) {
                continue;
            }

            // Try the full target, then half of it; prefer a session that ends before the cab is needed again.
            for (long target : new long[]{targetWh, (targetWh + atHub) / 2}) {
                if (target <= atHub || target > vehicle.capacityWh()) {
                    continue;
                }

                long seconds = energy.duration(vehicle, atHub, target, group.powerW(), dataset);
                ChargeOption earliestOption = null;

                for (var plug : group.plugIds()) {
                    var starts = calendar.starts(plug, earliest, dataset.end(), seconds, List.of());

                    if (!starts.isEmpty() && (earliestOption == null || starts.get(0) < earliestOption.start())) {
                        earliestOption = new ChargeOption(group, plug, starts.get(0), seconds, atHub, 0, null);
                    }
                }
                if (earliestOption == null) {
                    continue;
                }

                var charge = energy.charge(vehicle, atHub, earliestOption.start(), seconds, group.powerW(), dataset);
                var leg = sessionLeg(vehicle, state, new ChargeOption(group, earliestOption.plugId(), earliestOption.start(), seconds, atHub, 0, charge));
                boolean ready = leg.end().time() <= readyBy;

                if (best == null || (ready && !bestReady) || (ready == bestReady && (leg.end().energyWh() > best.end().energyWh()
                    || (leg.end().energyWh() == best.end().energyWh() && leg.costMicros() < best.costMicros())))) {
                    best = leg;
                    bestReady = ready;
                }
                if (ready) {
                    break; // The bigger target already fits; smaller ones only add stops later.
                }
            }
        }

        return best;
    }

    /** Drive to the hub, wait for the plug, then charge. */
    private Leg sessionLeg(Vehicle vehicle, State state, ChargeOption option) {
        var group = option.group();
        var approach = travel.route(state.location(), group.location());
        long arrive = state.time() + approach.seconds();
        long end = option.start() + option.seconds();
        var actions = new ArrayList<Action>(3);

        if (approach.seconds() > 0) {
            actions.add(new Action(ActionType.DRIVE, vehicle.id(), state.time(), arrive, state.location(), group.location(), state.energyWh(), option.atHubWh(),
                null, null, 0, 0));
        }
        if (option.start() > arrive) {
            actions.add(new Action(ActionType.WAIT, vehicle.id(), arrive, option.start(), group.location(), group.location(), option.atHubWh(),
                option.atHubWh(), null, null, 0, 0));
        }
        actions.add(new Action(ActionType.CHARGE, vehicle.id(), option.start(), end, group.location(), group.location(), option.atHubWh(),
            option.charge().energyWh(), null, option.plugId(), option.charge().gridWh(), option.charge().costMicros()));

        return new Leg(actions, new State(end, group.location(), option.charge().energyWh()), option.charge().costMicros());
    }
}
