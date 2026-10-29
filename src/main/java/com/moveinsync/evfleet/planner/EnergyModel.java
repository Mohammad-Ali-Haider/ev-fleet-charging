package com.moveinsync.evfleet.planner;

import static com.moveinsync.evfleet.planner.Fraction.*;

import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Vehicle;

/** Battery physics: consumption, the 15% floor, and the two-part charging curve with tariff costs. */
public final class EnergyModel {

    /** Result of a charging session: energy reached, energy drawn from the grid, and cost. */
    public record Charge(long energyWh, long gridWh, long costMicros) {
    }

    /** The 15% battery floor in Wh, rounded up. */
    public long floor(Vehicle vehicle) {
        return (vehicle.capacityWh() * 15 + 99) / 100;
    }

    /** Charge level where the curve switches from fast to slow charging (80%). */
    public static long fastLimit(Vehicle vehicle) {
        return vehicle.capacityWh() * 4 / 5;
    }

    /** Energy to drive a distance, including traffic and air conditioning, rounded up. */
    public long consumption(Vehicle vehicle, long metres, Dataset dataset) {
        long numerator = Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(metres, vehicle.whPerKm()), vehicle.acBp()), dataset.trafficBp());
        long denominator = 1000L * 10000 * 10000;

        return (numerator + denominator - 1) / denominator;
    }

    /** Seconds to charge from one level to another (fast to 80%, slow above). */
    public long duration(Vehicle vehicle, long energy, long target, int watts, Dataset dataset) {
        var threshold = of(vehicle.capacityWh() * 4, 5);
        var initial = of(energy);
        var goal = of(target);

        var fast = goal.min(threshold).sub(initial).max(ZERO);
        var slow = goal.sub(initial.max(threshold)).max(ZERO);

        var rate = of((long) Math.min(vehicle.maxPowerW(), watts) * dataset.efficiencyBp(), 3600L * 10000);

        return fast.div(rate).add(slow.div(rate.mul(of(dataset.slowBp(), 10000)))).ceil();
    }

    /** Energy and cost of charging for a given time from a given start. */
    public Charge charge(Vehicle vehicle, long energy, long start, long seconds, int watts, Dataset dataset) {
        var cursor = of(start);
        var remaining = of(seconds);
        var current = of(energy);
        var cap = of(vehicle.capacityWh());

        var threshold = of(vehicle.capacityWh() * 4, 5);
        var eta = of(dataset.efficiencyBp(), 10000);

        var grid = ZERO;
        var cost = ZERO;

        while (remaining.compareTo(ZERO) > 0 && current.compareTo(cap) < 0) {
            boolean fast = current.compareTo(threshold) < 0;
            var power = of(Math.min(vehicle.maxPowerW(), watts));

            if (!fast) {
                power = power.mul(of(dataset.slowBp(), 10000));
            }

            var boundary = fast ? threshold : cap;
            var span = remaining.min(boundary.sub(current).mul(3600).div(power.mul(eta)));

            for (var tariff : dataset.tariffs()) {
                var overlap = cursor.add(span).min(of(tariff.end())).sub(cursor.max(of(tariff.start()))).max(ZERO);
                var wh = power.mul(overlap).div(3600);
                grid = grid.add(wh);
                cost = cost.add(wh.mul(tariff.microsPerKwh()).div(1000));
            }
            current = current.add(power.mul(eta).mul(span).div(3600));
            cursor = cursor.add(span);
            remaining = remaining.sub(span);
        }

        return new Charge(current.floor(), grid.ceil(), cost.ceil());
    }
}
