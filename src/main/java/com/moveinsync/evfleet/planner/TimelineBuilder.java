package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.Trip;
import com.moveinsync.evfleet.domain.Vehicle;
import com.moveinsync.evfleet.planner.TripLegs.Leg;
import com.moveinsync.evfleet.planner.TripLegs.State;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds a cab's whole day from its ordered trips, choosing charging stops with a {@link ChargingStrategy}.
 */
final class TimelineBuilder {

    private final TripLegs legs;

    private final ChargeStops stops;

    /** Uses trip legs and charge stops to build whole days. */
    TimelineBuilder(TripLegs legs, ChargeStops stops) {
        this.legs = legs;
        this.stops = stops;
    }

    /** The cab's day for this trip sequence, or null when the sequence cannot be served safely. */
    List<Action> build(Vehicle vehicle, List<Trip> sequence, ChargerCalendar calendar, ChargingStrategy strategy, long deadline) {
        var laterNeed = laterNeed(vehicle, sequence);
        var state = State.of(vehicle);
        var actions = new ArrayList<Action>();
        long lastStart = sequence.isEmpty() ? state.time() : sequence.get(sequence.size() - 1).start();

        for (int index = 0; index < sequence.size(); index++) {
            var trip = sequence.get(index);
            var direct = legs.direct(vehicle, state, trip);
            var gap = gap(vehicle, state, trip, laterNeed[index], lastStart);
            Leg leg = null;

            // Charge before this trip if the strategy wants to (for example in a cheap window); otherwise drive straight there.
            if (strategy.chargeVoluntarily(gap)) {
                leg = stops.viaCharger(vehicle, state, trip, strategy.goal(vehicle, gap), calendar, deadline);
            }
            if (leg == null) {
                leg = direct;
            }
            // The cab cannot reach this trip directly, so a charging stop is the only option.
            if (leg == null) {
                leg = stops.viaCharger(vehicle, state, trip, strategy.goal(vehicle, gap), calendar, deadline);
            }
            if (leg == null) {
                return null;
            }
            actions.addAll(leg.actions());
            state = leg.end();
        }

        return actions;
    }

    private ChargingStrategy.Gap gap(Vehicle vehicle, State state, Trip trip, long laterNeedWh, long lastStart) {
        long need;

        try {
            need = legs.driveUse(vehicle, state.location(), trip.origin()) + legs.tripUse(vehicle, trip) + laterNeedWh;
        } catch (DomainException.RouteBlocked blocked) {
            need = Long.MAX_VALUE / 4;
        }

        return new ChargingStrategy.Gap(state.energyWh() - legs.floor(vehicle), need, laterNeedWh, legs.cheapestPrice(state.time(), trip.start()),
            legs.cheapestPrice(trip.start(), lastStart));
    }

    /** Energy each trip's successors need: later pickup drives plus later trips. */
    private long[] laterNeed(Vehicle vehicle, List<Trip> sequence) {
        var result = new long[sequence.size()];

        for (int index = sequence.size() - 2; index >= 0; index--) {
            var next = sequence.get(index + 1);
            long drive;

            try {
                drive = legs.driveUse(vehicle, sequence.get(index).destination(), next.origin());
            } catch (DomainException.RouteBlocked blocked) {
                drive = 0;
            }
            result[index] = result[index + 1] + drive + legs.tripUse(vehicle, next);
        }

        return result;
    }

    /**
     * Decides when a cab stops to charge in the idle gap before its next trip, and how much it buys. The base class charges only when it must, and only the
     * minimum; subclasses override the two decisions they change.
     */
    abstract static class ChargingStrategy {

        /**
         * The idle gap before one trip in a cab's day.
         *
         * @param spareWh
         *            energy above the battery floor when the gap starts
         * @param restOfDayNeedWh
         *            energy for the pickup drive, this trip and every later trip and drive
         * @param laterNeedWh
         *            energy for the trips and drives after this trip
         * @param gapPriceMicrosPerKwh
         *            cheapest tariff that overlaps this gap
         * @param laterPriceMicrosPerKwh
         *            cheapest tariff that overlaps any later gap of the same cab
         */
        record Gap(long spareWh, long restOfDayNeedWh, long laterNeedWh, long gapPriceMicrosPerKwh, long laterPriceMicrosPerKwh) {

            /** True if this gap is at least as cheap as any later one. */
            boolean cheapestLeft() {
                return gapPriceMicrosPerKwh <= laterPriceMicrosPerKwh;
            }
        }

        /** Charges only when the next trip is otherwise unreachable, keeping plug sessions short so other cabs can use the plugs. */
        static final ChargingStrategy JUST_IN_TIME = new JustInTime();

        /** Moves charging into the cheapest window the cab's timetable leaves room for. */
        static final ChargingStrategy CHEAPEST_WINDOW = new CheapestWindow();

        /** Charge even though the trip is reachable without charging; a cab that cannot reach the trip always charges. */
        abstract boolean chargeVoluntarily(Gap gap);

        /** How much to buy when the cab does stop in this gap: by default only what the next trip needs. */
        ChargeStops.ChargeGoal goal(Vehicle vehicle, Gap gap) {
            return ChargeStops.ChargeGoal.MINIMUM;
        }
    }

    /** Never charges voluntarily and keeps the base class's minimum top-up. */
    static final class JustInTime extends ChargingStrategy {

        /** Just-in-time charging never charges voluntarily. */
        @Override
        boolean chargeVoluntarily(Gap gap) {
            return false;
        }
    }

    /**
     * Buys the energy for the rest of the day in a gap that is at least as cheap as every later gap, so charging moves off-peak. In a dearer gap it falls back
     * to the base class's minimum and leaves the rest for the cheaper window.
     */
    static final class CheapestWindow extends ChargingStrategy {

        /** Charge now if the rest of the day needs it and now is the cheapest window left. */
        @Override
        boolean chargeVoluntarily(Gap gap) {
            return gap.spareWh() < gap.restOfDayNeedWh() && gap.cheapestLeft();
        }

        /** The rest of the day's energy in the cheapest window, otherwise the base class's minimum. */
        @Override
        ChargeStops.ChargeGoal goal(Vehicle vehicle, Gap gap) {
            return gap.cheapestLeft() ? ChargeStops.ChargeGoal.restOfDay(gap.laterNeedWh()) : super.goal(vehicle, gap);
        }
    }
}
