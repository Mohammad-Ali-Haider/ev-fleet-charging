package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.ActionType;
import com.moveinsync.evfleet.domain.Charger;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Disruption;
import com.moveinsync.evfleet.domain.DisruptionKind;
import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.InputRules;
import com.moveinsync.evfleet.domain.Interval;
import com.moveinsync.evfleet.domain.Plan;
import com.moveinsync.evfleet.domain.PrefixEvidence;
import com.moveinsync.evfleet.domain.Trip;
import com.moveinsync.evfleet.domain.Vehicle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Incremental repair retains other vehicles' full future work and plug slots.
 */
public final class RepairPreparer {

    // Cabs reopened besides the impacted ones; small so a repair changes few assignments.
    private static final int HELPER_CABS = 2;

    private final EnergyModel model = new EnergyModel();

    /** The previous retained work merged back into the dataset and plan. */
    private record Reopened(Dataset dataset, Plan plan) {
    }

    /** Undoes the freezing of an earlier repair so this event sees the whole remaining day. */
    private Reopened thaw(Dataset dataset, Plan plan) {
        var prefixEvidence = dataset.prefixEvidence();

        if (prefixEvidence == null) {
            return new Reopened(dataset, plan);
        }

        var retained = new HashSet<>(prefixEvidence.actions());
        var tripIds = prefixEvidence.trips().stream().map(Trip::id).collect(Collectors.toSet());
        var history = dataset.history().stream().filter(action -> !retained.contains(action)).toList();
        var historical = new LinkedHashMap<>(dataset.historyAssignments());
        tripIds.forEach(historical::remove);

        var vehicles = new LinkedHashMap<String, Vehicle>();

        for (var vehicle : dataset.vehicles()) {
            vehicles.put(vehicle.id(), vehicle);
        }

        for (var vehicle : prefixEvidence.vehicles()) {
            vehicles.put(vehicle.id(), vehicle);
        }

        var trips = new LinkedHashMap<String, Trip>();

        for (var trip : dataset.trips()) {
            trips.put(trip.id(), trip);
        }

        for (var trip : prefixEvidence.trips()) {
            trips.put(trip.id(), trip);
        }

        var actions = new ArrayList<>(prefixEvidence.actions());
        actions.addAll(plan.actions());

        var assignments = new LinkedHashMap<>(plan.assignments());

        for (var action : prefixEvidence.actions()) {
            if (action.tripId() != null) {
                assignments.put(action.tripId(), action.vehicleId());
            }
        }

        return new Reopened(
            dataset.withScheduleState(new ArrayList<>(vehicles.values()), new ArrayList<>(trips.values()), prefixEvidence.chargers(), history, historical, null,
                dataset.asOf()),
            new Plan(actions, assignments, plan.backups(), history, historical, plan.lowerBound(), plan.changedAssignments(), plan.searchExhausted(),
                plan.validation(), plan.elapsedS(), plan.asOf()));
    }

    /** Builds the reduced input for a live re-plan: freeze the past, reopen affected cabs. */
    public Dataset prepare(Dataset original, Plan previous, Disruption event) {
        // Start from the full remaining day, undoing any freeze left by an earlier repair.
        var reopened = thaw(original, previous);
        Dataset dataset = reopened.dataset();
        Plan previousPlan = reopened.plan();
        InputRules.event(event, dataset);

        var vehicles = dataset.vehicles().stream().collect(Collectors.toMap(Vehicle::id, x -> x));
        var chargers = dataset.chargers().stream().collect(Collectors.toMap(Charger::id, x -> x));
        validateObservation(dataset, event, vehicles, chargers);

        var byVehicle = new HashMap<String, List<Action>>();

        for (var action : previousPlan.actions()) {
            byVehicle.computeIfAbsent(action.vehicleId(), k -> new ArrayList<>()).add(action);
        }

        // Cabs to re-plan: those the event affects, plus the two nearest helper cabs.
        var impacted = event.kind().directlyImpacted(event, previousPlan.actions());
        var neighbourhood = new HashSet<>(impacted);
        neighbourhood.addAll(helpers(dataset, event, impacted, byVehicle));

        var history = new ArrayList<>(dataset.history());
        var historical = new LinkedHashMap<>(dataset.historyAssignments());
        var anchors = new ArrayList<Vehicle>();
        var prefixAnchors = new ArrayList<Vehicle>();
        var prefixActions = new ArrayList<Action>();

        // For each cab: freeze what has already happened, then decide which future work stays fixed.
        for (var vehicle : dataset.vehicles()) {
            boolean affected = event.kind() == DisruptionKind.SOC && event.assetId().equals(vehicle.id());
            boolean interrupted = false;

            if (affected && event.at() < vehicle.availableAt()) {
                throw new DomainException.SafetyIncident("FROZEN_COMMITMENT");
            }

            long now = vehicle.availableAt();
            long energy = vehicle.energyWh();
            String place = vehicle.location();
            int historyStart = history.size();
            var ordered = new ArrayList<>(byVehicle.getOrDefault(vehicle.id(), List.of()));
            ordered.sort(Comparator.comparingLong(Action::start).thenComparingLong(Action::end));

            // Past: actions finished before the event become history; an action in progress is handled case by case.
            for (var action : ordered) {
                if (action.end() <= event.at()) {
                    history.add(action);
                    now = action.end();
                    place = action.destination();
                    energy = action.afterWh();
                    if (action.tripId() != null) {
                        historical.put(action.tripId(), vehicle.id());
                    }
                } else if (action.start() < event.at() && event.at() < action.end()) {
                    if (action.kind() == ActionType.WAIT) {
                        history.add(action.withMeasurements(event.at(), action.beforeWh(), action.afterWh(), 0, 0));
                        now = event.at();
                    } else if (affected) {
                        throw new DomainException.SafetyIncident("ACTIVE_ACTION_REQUIRES_REMAINING_ROUTE");
                    } else if (event.kind() == DisruptionKind.CHARGER_FAILURE && event.assetId().equals(action.chargerId())) {
                        interrupted = true;

                        Long observed = event.observedEnergy().get(vehicle.id());

                        if (observed == null) {
                            throw new DomainException.SafetyIncident("UNCONFIRMED_CHARGING_ENERGY");
                        }
                        if (observed < model.floor(vehicle) || observed > vehicle.capacityWh()) {
                            throw new DomainException.SafetyIncident("INVALID_CHARGING_OBSERVATION");
                        }

                        var charged = model.charge(vehicle, action.beforeWh(), action.start(), event.at() - action.start(),
                            chargers.get(action.chargerId()).powerW(), dataset);
                        history.add(action.withMeasurements(event.at(), action.beforeWh(), observed, charged.gridWh(), charged.costMicros()));
                        now = event.at();
                        place = action.destination();
                        energy = observed;
                    } else {
                        history.add(action);
                        now = action.end();
                        place = action.destination();
                        energy = action.afterWh();
                        if (action.tripId() != null) {
                            historical.put(action.tripId(), vehicle.id());
                        }
                    }
                }
            }
            // The reporting cab continues from its measured charge and location.
            now = Math.max(now, event.at());
            if (affected) {
                energy = event.energyWh();
                place = event.location();
            }

            var prefixAnchor = affected || interrupted ? vehicle.withState(now, place, energy) : vehicle;
            int futureStart = history.size();

            // Future: other cabs keep their plan; reopened cabs keep only the next freezeS seconds; unsafe work or work on the failed charger is reopened.
            for (var action : ordered) {
                if (action.end() <= event.at() || (action.kind() == ActionType.WAIT && neighbourhood.contains(vehicle.id())) || action.start() < event.at()) {
                    continue;
                }
                if (neighbourhood.contains(vehicle.id()) && action.start() >= event.at() + event.freezeS()) {
                    break;
                }
                if (event.kind() == DisruptionKind.CHARGER_FAILURE && event.assetId().equals(action.chargerId())) {
                    break;
                }
                if (action.start() < now || !action.origin().equals(place)) {
                    break;
                }

                long after = energy;
                long grid = 0;
                long cost = 0;

                if (action.kind() == ActionType.DRIVE || action.kind() == ActionType.TRIP) {
                    after -= action.beforeWh() - action.afterWh();
                } else if (action.kind() == ActionType.CHARGE) {
                    var charged = model.charge(vehicle, energy, action.start(), action.end() - action.start(), chargers.get(action.chargerId()).powerW(),
                        dataset);
                    after = charged.energyWh();
                    grid = charged.gridWh();
                    cost = charged.costMicros();
                }
                // Safety overrides imminent preference.
                if (after < model.floor(vehicle)) {
                    break;
                }

                var frozen = action.withMeasurements(action.end(), energy, after, grid, cost);
                history.add(frozen);
                now = frozen.end();
                place = frozen.destination();
                energy = frozen.afterWh();
                if (frozen.tripId() != null) {
                    historical.put(frozen.tripId(), vehicle.id());
                }
            }
            anchors.add(vehicle.withState(now, place, energy));
            prefixAnchors.add(prefixAnchor);
            prefixActions.addAll(history.subList(affected || interrupted ? futureStart : historyStart, history.size()));
        }

        for (var trip : dataset.trips()) {
            if (trip.start() < event.at() && previousPlan.assignments().get(trip.id()) == null) {
                historical.put(trip.id(), null);
            }
        }

        // The failed charger is unusable from now on; plug sessions kept by frozen cabs stay booked.
        var contextChargers = new ArrayList<Charger>();

        for (var charger : dataset.chargers()) {
            contextChargers.add(event.kind() == DisruptionKind.CHARGER_FAILURE && charger.id().equals(event.assetId())
                ? charger.outage(new Interval(event.at(), dataset.end()))
                : charger);
        }

        var reservedChargers = new ArrayList<Charger>();

        for (var charger : contextChargers) {
            var held = charger;

            for (var action : history) {
                if (charger.id().equals(action.chargerId()) && action.end() > event.at()) {
                    held = held.outage(new Interval(action.start(), action.end()));
                }
            }
            reservedChargers.add(held);
        }

        var frozenIds = prefixActions.stream().map(Action::tripId).filter(Objects::nonNull).collect(Collectors.toSet());
        var evidence = new PrefixEvidence(prefixAnchors, prefixActions, dataset.trips().stream().filter(trip -> frozenIds.contains(trip.id())).toList(),
            contextChargers);

        return dataset.withScheduleState(anchors, dataset.trips().stream().filter(trip -> !historical.containsKey(trip.id())).toList(), reservedChargers,
            history, historical, evidence, event.at());
    }

    /**
     * Two extra cabs to reopen alongside the impacted ones: those closest to the first pickup the impacted cabs had planned after the event, preferring cabs
     * not on a trip at that moment and then cabs with more charge. Without such a pickup, the two cabs with the most charge are used.
     */
    private Set<String> helpers(Dataset dataset, Disruption event, Set<String> impacted, Map<String, List<Action>> byVehicle) {
        Action focus = null;

        for (var vehicleId : impacted) {
            for (var action : byVehicle.getOrDefault(vehicleId, List.of())) {
                if (action.kind() == ActionType.TRIP && action.start() >= event.at() && (focus == null || action.start() < focus.start())) {
                    focus = action;
                }
            }
        }

        var others = dataset.vehicles().stream().filter(vehicle -> !impacted.contains(vehicle.id())).toList();

        if (focus == null) {
            return others.stream().sorted(Comparator.comparingLong(Vehicle::energyWh).reversed().thenComparing(Vehicle::id)).limit(HELPER_CABS).map(
                Vehicle::id).collect(Collectors.toSet());
        }

        var travel = new OfflineTravel(dataset);
        var pickup = focus;
        var scores = new HashMap<String, long[]>();

        for (var vehicle : others) {
            String place = vehicle.location();
            long energy = vehicle.energyWh();
            boolean busy = vehicle.availableAt() > pickup.start();

            for (var action : byVehicle.getOrDefault(vehicle.id(), List.of())) {
                if (action.end() <= pickup.start() && action.end() >= vehicle.availableAt()) {
                    place = action.destination();
                    energy = action.afterWh();
                }
                if (action.kind() == ActionType.TRIP && action.start() < pickup.end() && pickup.start() < action.end()) {
                    busy = true;
                }
            }

            long metres = travel.isBlocked(place, pickup.origin()) ? Long.MAX_VALUE : travel.route(place, pickup.origin()).metres();
            scores.put(vehicle.id(), new long[]{busy ? 1 : 0, metres, -energy});
        }

        return others.stream().sorted(Comparator.<Vehicle>comparingLong(vehicle -> scores.get(vehicle.id())[0]).thenComparingLong(
            vehicle -> scores.get(vehicle.id())[1]).thenComparingLong(vehicle -> scores.get(vehicle.id())[2]).thenComparing(Vehicle::id)).limit(
                HELPER_CABS).map(Vehicle::id).collect(Collectors.toSet());
    }

    /** Rejects events that refer to unknown assets or impossible readings. */
    private void validateObservation(Dataset dataset, Disruption event, Map<String, Vehicle> vehicles, Map<String, Charger> chargers) {
        if (event.kind() == DisruptionKind.SOC) {
            var vehicle = vehicles.get(event.assetId());

            if (vehicle == null) {
                throw new DomainException("ASSET_NOT_FOUND", 404);
            }
            if (event.energyWh() == null || dataset.locations().stream().noneMatch(l -> l.id().equals(event.location()))) {
                throw new DomainException("SOC_REQUIRES_ENERGY_AND_LOCATION");
            }
            if (event.energyWh() > vehicle.capacityWh()) {
                throw new DomainException("ENERGY_EXCEEDS_CAPACITY");
            }
            if (event.energyWh() < model.floor(vehicle)) {
                throw new DomainException.SafetyIncident("BELOW_FLOOR");
            }
        } else if (!chargers.containsKey(event.assetId())) {
            throw new DomainException("ASSET_NOT_FOUND", 404);
        }
    }
}
