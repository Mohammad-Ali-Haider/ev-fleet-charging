package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.planner.TimelineBuilder.ChargingStrategy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The two charging decisions that look beyond the next trip: topping up idle cabs during construction, and moving each cab's charging into the cheapest window
 * its timetable allows once its trips are known.
 */
final class ChargingPasses {

    private final PlanningRun run;

    /** Works on the shared state of one solve. */
    ChargingPasses(PlanningRun run) {
        this.run = run;
    }

    /**
     * Sends idle cabs that are too low for an average trip to charge before the next batch, sized to their share of the remaining trips. Charging only when a
     * trip forces it leaves cabs with no time to charge on a busy day; this keeps plugs working while cabs are idle.
     */
    void chargeIdleCabs(long nextStart, int remainingTrips, long averageMetres) {
        if (remainingTrips == 0) {
            return;
        }

        var schedule = run.schedule;
        var days = new ArrayList<>(schedule.days());
        days.sort(
            Comparator.<FleetSchedule.CabDay>comparingLong(day -> day.end.energyWh() - run.legs.floor(day.vehicle)).thenComparing(day -> day.vehicle.id()));
        double tripsPerCab = (double) remainingTrips / days.size();

        for (var day : days) {
            if (run.outOfTime()) {
                return;
            }
            if (day.end.time() >= nextStart) {
                continue;
            }

            var vehicle = day.vehicle;
            // An average trip plus a quarter for the empty drive to its pickup.
            long tripNeed = run.energy.consumption(vehicle, averageMetres + averageMetres / 4, run.dataset);
            long spare = day.end.energyWh() - run.legs.floor(vehicle);

            // Only cabs too low for about one and a half average trips go to charge.
            if (spare >= tripNeed + tripNeed / 2) {
                continue;
            }

            // Aim for this cab's share of the remaining work, capped at 80% where charging slows down.
            long share = (long) Math.ceil(tripsPerCab * tripNeed) + tripNeed;
            long target = Math.min(EnergyModel.fastLimit(vehicle), run.legs.floor(vehicle) + share);

            if (target <= day.end.energyWh()) {
                continue;
            }

            var leg = run.stops.idleCharge(vehicle, day.end, target, nextStart, schedule.calendar, run.deadline);

            if (leg != null) {
                schedule.appendIdle(day, leg);
            }
        }
    }

    /** Rebuilds each charging cab's day with {@link ChargingStrategy#CHEAPEST_WINDOW}, keeping the rebuild only when it costs less. */
    void shiftToCheaperWindows() {
        var schedule = run.schedule;
        var charging = new ArrayList<>(schedule.days().stream().filter(FleetSchedule.CabDay::charges).toList());
        charging.sort(Comparator.comparingLong(FleetSchedule.CabDay::chargingCostMicros).reversed().thenComparing(day -> day.vehicle.id()));

        for (var day : charging) {
            if (run.outOfTime()) {
                return;
            }

            // Free this cab's plug bookings, rebuild its day with cheapest-window charging, and keep it only if cheaper.
            schedule.calendar.release(day.actions);
            var rebuilt = run.timelines.build(day.vehicle, day.trips, schedule.calendar, ChargingStrategy.CHEAPEST_WINDOW, run.deadline);
            schedule.calendar.reserve(day.actions);

            if (rebuilt != null && rebuilt.stream().mapToLong(Action::costMicros).sum() < day.chargingCostMicros()) {
                schedule.replace(day, List.copyOf(day.trips), rebuilt);
            }
        }
    }
}
