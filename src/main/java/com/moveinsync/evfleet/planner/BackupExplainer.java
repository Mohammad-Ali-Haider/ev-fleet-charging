package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.ActionType;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Reason;
import com.moveinsync.evfleet.domain.Trip;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explains each backup trip by checking every cab against it in the final schedule.
 *
 * <p>
 * Per cab, the first matching cause wins: busy, route blocked, short of charge. A cab short of charge is "congested" when a charge would fit with every plug
 * free but not with the actual bookings. The trip's reason is the most actionable cause found across cabs.
 */
final class BackupExplainer {

    private static final int CONGESTION_PROBES = 8;

    private final Dataset dataset;

    private final TripLegs legs;

    private final ChargeStops stops;

    private final ChargerCalendar freePlugs;

    /** Prepares a calendar with only outages, to test "would a free plug have helped?". */
    BackupExplainer(Dataset dataset, TripLegs legs, ChargeStops stops) {
        this.dataset = dataset;
        this.legs = legs;
        this.stops = stops;
        this.freePlugs = ChargerCalendar.outagesOnly(dataset);
    }

    /** Reason for a trip the planner never reached before its deadline. */
    Reason searchBudget() {
        return reason(BackupCause.SEARCH_BUDGET, Map.of(), 0);
    }

    /** Checks every cab against the trip and returns the dominant reason. */
    Reason explain(Trip trip, FleetSchedule schedule, long deadline) {
        if (trip.start() + trip.durationS() > Math.min(trip.latestFinish(), dataset.end())) {
            return reason(BackupCause.TRIP_WINDOW_INFEASIBLE, Map.of(), 0);
        }

        boolean anyBatteryFits = dataset.vehicles().stream().anyMatch(v -> legs.tripUse(v, trip) <= v.capacityWh() - legs.floor(v));

        if (!anyBatteryFits) {
            return reason(BackupCause.TRIP_EXCEEDS_BATTERY, Map.of(), 0);
        }

        int busy = 0;
        int blocked = 0;
        int shortOfCharge = 0;
        int congested = 0;
        var needCharge = new ArrayList<FleetSchedule.CabDay>();
        var deadhead = new HashMap<String, Long>();

        for (var day : schedule.days()) {
            if (System.nanoTime() >= deadline) {
                break;
            }

            var vehicle = day.vehicle;
            boolean overlapping = day.actions.stream().anyMatch(
                action -> action.kind() == ActionType.TRIP && action.start() < trip.start() + trip.durationS() && trip.start() < action.end());
            var position = day.positionBefore(trip.start());

            if (overlapping || vehicle.availableAt() > trip.start()) {
                busy++;
                continue;
            }

            var check = legs.checkDirect(vehicle, position, trip);

            switch (check.failure()) {
                case TIME -> busy++;
                case ROUTE_BLOCKED -> blocked++;
                case NONE -> shortOfCharge++; // Enough for this trip, but not with its later trips.
                case ENERGY -> {
                    shortOfCharge++;
                    needCharge.add(day);
                    deadhead.put(vehicle.id(), check.deadheadWh());
                }
            }
        }

        // Charger checks are the expensive part, so only the nearest cabs short of charge are probed.
        needCharge.sort(Comparator.<FleetSchedule.CabDay>comparingLong(day -> deadhead.get(day.vehicle.id())).thenComparing(day -> day.vehicle.id()));
        for (var day : needCharge.subList(0, Math.min(CONGESTION_PROBES, needCharge.size()))) {
            if (System.nanoTime() >= deadline) {
                break;
            }

            var position = day.positionBefore(trip.start());

            var minimum = ChargeStops.ChargeGoal.MINIMUM;

            if (stops.viaCharger(day.vehicle, position, trip, minimum, schedule.calendar, deadline) == null
                && stops.viaCharger(day.vehicle, position, trip, minimum, freePlugs, deadline) != null) {
                congested++;
            }
        }

        var evidence = new LinkedHashMap<String, Integer>();
        evidence.put("busyCabs", busy);
        evidence.put("cabsShortOfCharge", shortOfCharge);
        evidence.put("cabsBlockedByChargerCongestion", congested);
        evidence.put("cabsCutOffByBlockedRoute", blocked);

        if (congested > 0) {
            return reason(BackupCause.CHARGER_CONGESTION, evidence, congested);
        }
        if (shortOfCharge > 0) {
            return reason(BackupCause.NO_CAB_WITH_ENOUGH_CHARGE_IN_TIME, evidence, shortOfCharge);
        }
        if (blocked > 0 && busy == 0) {
            return reason(BackupCause.ROUTE_BLOCKED, evidence, blocked);
        }

        return reason(BackupCause.NO_CAB_FREE_IN_TIME, evidence, busy + blocked);
    }

    /** Builds a reason from a cause, its evidence and the cab count. */
    private static Reason reason(BackupCause cause, Map<String, Integer> evidence, int cabs) {
        return new Reason(cause.name(), evidence, cause.explain(cabs));
    }

    /** Why a trip was handed to a backup petrol or diesel cab, from most to least specific. */
    enum BackupCause {

        TRIP_WINDOW_INFEASIBLE("The trip cannot finish before its latest allowed finish or the end of the planning day."),

        TRIP_EXCEEDS_BATTERY("No cab can run this trip even from a full battery without dropping below the 15% floor."),

        CHARGER_CONGESTION("%d cab(s) could reach the pickup in time after a charge, but every compatible charger was already booked in that window."),

        NO_CAB_WITH_ENOUGH_CHARGE_IN_TIME(
            "%d cab(s) were free in time but lacked the charge for this trip and their later commitments, and could not charge before the pickup."),

        ROUTE_BLOCKED("Every free cab is cut off from the pickup by a blocked route."),

        NO_CAB_FREE_IN_TIME("All %d cab(s) are on other trips or cannot reach the pickup by the start time."),

        SEARCH_BUDGET("The planning time budget ran out before this trip was tried; it is not proven infeasible.");

        private final String template;

        /** Stores the explanation sentence. */
        BackupCause(String template) {
            this.template = template;
        }

        /** The explanation sentence with the cab count filled in. */
        String explain(int cabs) {
            return template.contains("%d") ? template.formatted(cabs) : template;
        }
    }
}
