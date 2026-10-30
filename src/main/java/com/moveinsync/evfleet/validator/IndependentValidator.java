package com.moveinsync.evfleet.validator;

import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.ActionType;
import com.moveinsync.evfleet.domain.Charger;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Location;
import com.moveinsync.evfleet.domain.Plan;
import com.moveinsync.evfleet.domain.Report;
import com.moveinsync.evfleet.domain.RouteBlock;
import com.moveinsync.evfleet.domain.Trip;
import com.moveinsync.evfleet.domain.Vehicle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Reference replay never calls planner/travel/energy helpers. */
public final class IndependentValidator {

    private static final MathContext MC = new MathContext(50, RoundingMode.HALF_EVEN);

    /** Shorthand for BigDecimal.valueOf. */
    private static BigDecimal bd(long n) {
        return BigDecimal.valueOf(n);
    }

    /** The value, or zero if negative. */
    private static BigDecimal zero(BigDecimal n) {
        return n.max(BigDecimal.ZERO);
    }

    /** Charging result computed independently of the planner. */
    public record ChargeCheck(long energyWh, long gridWh, long costMicros) {
    }

    /** Re-computes a charging session with BigDecimal, independently of the planner. */
    public ChargeCheck charge(Dataset dataset, Vehicle vehicle, Charger charger, long initial, long start, long end) {
        BigDecimal current = bd(initial);
        BigDecimal cursor = bd(start);
        BigDecimal remaining = bd(end - start);
        BigDecimal cap = bd(vehicle.capacityWh());
        BigDecimal threshold = cap.multiply(new BigDecimal("0.8"));
        BigDecimal grid = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;

        while (remaining.signum() > 0 && current.compareTo(cap) < 0) {
            boolean fast = current.compareTo(threshold) < 0;
            BigDecimal watts = bd(Math.min(vehicle.maxPowerW(), charger.powerW()));

            if (!fast) {
                watts = watts.multiply(bd(dataset.slowBp())).divide(bd(10000), MC);
            }

            BigDecimal boundary = fast ? threshold : cap;
            BigDecimal rate = watts.multiply(bd(dataset.efficiencyBp())).divide(bd(36000000), MC);
            BigDecimal untilBoundary = boundary.subtract(current).divide(rate, MC);
            boolean reachesBoundary = untilBoundary.compareTo(remaining) <= 0;
            BigDecimal span = remaining.min(untilBoundary);

            for (var tariff : dataset.tariffs()) {
                var overlap = zero(cursor.add(span, MC).min(bd(tariff.end())).subtract(cursor.max(bd(tariff.start())), MC));
                var wh = watts.multiply(overlap, MC).divide(bd(3600), MC);
                grid = grid.add(wh, MC);
                cost = cost.add(wh.multiply(bd(tariff.microsPerKwh()), MC).divide(bd(1000), MC), MC);
            }
            current = reachesBoundary ? boundary : current.add(span.multiply(rate, MC), MC).min(boundary);
            cursor = cursor.add(span, MC);
            remaining = reachesBoundary ? remaining.subtract(span, MC) : BigDecimal.ZERO;
            if (span.signum() <= 0) {
                break;
            }
        }

        var roundingEpsilon = new BigDecimal("1e-30");

        return new ChargeCheck(current.add(roundingEpsilon).setScale(0, RoundingMode.FLOOR).longValueExact(),
            grid.subtract(roundingEpsilon).setScale(0, RoundingMode.CEILING).longValueExact(),
            cost.subtract(roundingEpsilon).setScale(0, RoundingMode.CEILING).longValueExact());
    }

    /** Replays every cab's day and checks every hard rule; returns all violations. */
    public Report validate(Dataset dataset, Plan plan) {
        var errors = new TreeSet<String>();
        var prefixReport = validateRetainedPrefix(dataset);
        double minimum = prefixReport.minimumSoc() == null ? 1 : Math.min(1, prefixReport.minimumSoc());
        int prefixCount = prefixReport.actionsChecked();

        for (String violation : prefixReport.violations()) {
            errors.add("PREFIX_" + violation);
        }

        var vehicles = dataset.vehicles().stream().collect(Collectors.toMap(Vehicle::id, x -> x));
        var trips = dataset.trips().stream().collect(Collectors.toMap(Trip::id, x -> x));
        var plugs = dataset.chargers().stream().collect(Collectors.toMap(Charger::id, x -> x));
        var locations = dataset.locations().stream().collect(Collectors.toMap(Location::id, x -> x));

        var byVehicle = new HashMap<String, List<Action>>();
        var byPlug = new HashMap<String, List<Action>>();
        var counts = new HashMap<String, Integer>();
        var actual = new HashMap<String, String>();
        long costs = 0;

        if (!plan.history().equals(dataset.history()) || !plan.historyAssignments().equals(dataset.historyAssignments())) {
            errors.add("FROZEN_PREFIX_CHANGED");
        }
        if (!plan.assignments().keySet().equals(trips.keySet())) {
            errors.add("ASSIGNMENT_SET");
        }

        var backupIds = plan.assignments().entrySet().stream().filter(e -> e.getValue() == null).map(Map.Entry::getKey).collect(Collectors.toSet());

        if (!plan.backups().keySet().equals(backupIds)) {
            errors.add("BACKUP_EXPLANATIONS");
        }

        for (var action : plan.actions()) {
            byVehicle.computeIfAbsent(action.vehicleId(), k -> new ArrayList<>()).add(action);
            if (action.kind() == ActionType.CHARGE) {
                byPlug.computeIfAbsent(action.chargerId(), k -> new ArrayList<>()).add(action);
            }
            if (action.end() <= action.start() || action.start() < dataset.start() || action.end() > dataset.end()) {
                errors.add("ACTION_INTERVAL");
            }
        }

        for (var vehicle : dataset.vehicles()) {
            long energy = vehicle.energyWh();
            long now = vehicle.availableAt();
            String place = vehicle.location();
            minimum = Math.min(minimum, (double) energy / vehicle.capacityWh());
            if (energy * 100 < vehicle.capacityWh() * 15) {
                errors.add("INITIAL_FLOOR");
            }

            var actions = byVehicle.remove(vehicle.id());

            if (actions == null) {
                continue;
            }
            actions.sort(Comparator.comparingLong(Action::start).thenComparingLong(Action::end));

            for (var action : actions) {
                if (action.start() < now) {
                    errors.add("VEHICLE_OVERLAP");
                }
                if (!action.origin().equals(place) || action.beforeWh() != energy) {
                    errors.add("CONTINUITY");
                }
                if (!locations.containsKey(action.origin()) || !locations.containsKey(action.destination())) {
                    errors.add("LOCATION_REFERENCE");
                    continue;
                }

                long expected = energy;
                long grid = 0;
                long cost = 0;

                if (action.kind() == ActionType.TRIP || action.kind() == ActionType.DRIVE) {
                    long metres;

                    if (action.kind() == ActionType.TRIP) {
                        var trip = trips.get(action.tripId());

                        if (trip == null) {
                            errors.add("TRIP_REFERENCE");
                            continue;
                        }
                        counts.merge(trip.id(), 1, Integer::sum);
                        actual.put(trip.id(), vehicle.id());
                        if (action.start() != trip.start() || action.end() != trip.start() + trip.durationS() || action.end() > trip.latestFinish()
                            || !action.origin().equals(trip.origin()) || !action.destination().equals(trip.destination())) {
                            errors.add("TRIP_TIMING_ROUTE");
                        }
                        metres = trip.distanceM();
                    } else {
                        if (dataset.blockedRoutes().contains(new RouteBlock(action.origin(), action.destination()))) {
                            errors.add("MISSING_ROUTE");
                        }

                        var x = locations.get(action.origin());
                        var y = locations.get(action.destination());
                        metres = (long) Math.ceil(Math.sqrt(Math.pow(x.xM() - y.xM(), 2) + Math.pow(x.yM() - y.yM(), 2)));

                        long seconds = (metres * dataset.trafficBp() + dataset.speedMps() * 10000L - 1) / (dataset.speedMps() * 10000L);

                        if (action.end() - action.start() != seconds) {
                            errors.add("DRIVE_DURATION");
                        }
                    }

                    var numerator = BigInteger.valueOf(metres).multiply(BigInteger.valueOf(vehicle.whPerKm())).multiply(
                        BigInteger.valueOf(vehicle.acBp())).multiply(BigInteger.valueOf(dataset.trafficBp()));
                    var denominator = BigInteger.valueOf(100000000000L);
                    long used = numerator.add(denominator).subtract(BigInteger.ONE).divide(denominator).longValueExact();
                    expected = energy - used;
                } else if (action.kind() == ActionType.CHARGE) {
                    var charger = plugs.get(action.chargerId());

                    if (charger == null) {
                        errors.add("CHARGER_REFERENCE");
                        continue;
                    }
                    if (!charger.location().equals(place) || !action.destination().equals(place) || !charger.connector().equals(vehicle.connector())) {
                        errors.add("CHARGER_COMPATIBILITY");
                    }
                    if (charger.outages().stream().anyMatch(o -> action.start() < o.end() && o.start() < action.end())) {
                        errors.add("CHARGER_OUTAGE");
                    }

                    var charged = charge(dataset, vehicle, charger, energy, action.start(), action.end());
                    expected = charged.energyWh();
                    grid = charged.gridWh();
                    cost = charged.costMicros();
                } else if (!action.destination().equals(place)) {
                    errors.add("WAIT_TELEPORT");
                }
                if (action.kind() != ActionType.TRIP && action.tripId() != null) {
                    errors.add("UNEXPECTED_TRIP_REFERENCE");
                }
                if (action.kind() != ActionType.CHARGE && action.chargerId() != null) {
                    errors.add("UNEXPECTED_CHARGER_REFERENCE");
                }
                if (expected != action.afterWh() || grid != action.gridWh() || cost != action.costMicros()) {
                    errors.add("ENERGY_OR_COST_MISMATCH");
                }
                if (expected * 100 < vehicle.capacityWh() * 15 || expected > vehicle.capacityWh()) {
                    errors.add("BATTERY_FLOOR_OR_CAPACITY");
                }
                minimum = Math.min(minimum, (double) expected / vehicle.capacityWh());
                costs += cost;
                energy = expected;
                now = action.end();
                place = action.destination();
            }
        }
        if (!byVehicle.isEmpty()) {
            errors.add("VEHICLE_REFERENCE");
        }

        for (var entry : plan.assignments().entrySet()) {
            int n = counts.getOrDefault(entry.getKey(), 0);

            if (entry.getValue() == null) {
                if (n != 0) {
                    errors.add("BACKUP_HAS_EV_ACTION");
                }
            } else if (n != 1 || !entry.getValue().equals(actual.get(entry.getKey()))) {
                errors.add("TRIP_MISSING_OR_DUPLICATED");
            }
        }

        for (var sessions : byPlug.values()) {
            sessions.sort(Comparator.comparingLong(Action::start).thenComparingLong(Action::end));

            for (int i = 1; i < sessions.size(); i++) {
                if (sessions.get(i - 1).end() > sessions.get(i).start()) {
                    errors.add("CHARGER_CAPACITY");
                }
            }
        }

        return new Report(errors.isEmpty(), new ArrayList<>(errors), plan.actions().size() + prefixCount, vehicles.isEmpty() ? null : minimum, costs,
            "future suffix + independently replayed retained prefix; completed audit history" + " bound to input");
    }

    /** Re-checks the work kept from an earlier plan during a live re-plan. */
    private Report validateRetainedPrefix(Dataset dataset) {
        if (dataset.prefixEvidence() == null) {
            return new Report(true, List.of(), 0, null, 0, "retained prefix");
        }

        var prefixEvidence = dataset.prefixEvidence();
        var prefixDataset = dataset.withScheduleState(prefixEvidence.vehicles(), prefixEvidence.trips(), prefixEvidence.chargers(), List.of(), Map.of(), null,
            dataset.asOf());
        var assignments = new LinkedHashMap<String, String>();

        for (var action : prefixEvidence.actions()) {
            if (action.tripId() != null) {
                assignments.put(action.tripId(), action.vehicleId());
            }
        }

        var prefixPlan = new Plan(prefixEvidence.actions(), assignments, Map.of(), List.of(), Map.of(), 0, 0, false, null, 0, dataset.asOf());
        var report = validate(prefixDataset, prefixPlan);

        return report;
    }
}
