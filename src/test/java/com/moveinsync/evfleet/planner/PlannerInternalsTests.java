package com.moveinsync.evfleet.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.ActionType;
import com.moveinsync.evfleet.domain.Charger;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Location;
import com.moveinsync.evfleet.domain.Tariff;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Tests of planner building blocks that are not visible outside the planner package. */
class PlannerInternalsTests {

    /** Exact reference: floor(n / d) for d > 0. */
    private static long floorOf(BigInteger n, BigInteger d) {
        var parts = n.divideAndRemainder(d);

        return parts[0].longValueExact() - (n.signum() < 0 && parts[1].signum() != 0 ? 1 : 0);
    }

    /** For fractions a and b: a + b, a - b, a × b, a ÷ b and comparison all match exact BigInteger arithmetic, including after overflow. */
    @Test
    void primitiveFractionMatchesBigIntegerAcrossTheOverflowSwitch() {
        var random = new Random(6);

        for (int i = 0; i < 20000; i++) {
            // Every third round uses values big enough that products overflow a long, forcing the BigInteger path.
            long scale = i % 3 == 0 ? 1_000_000_000_000L : 1_000_000L;
            long aTop = random.nextLong() % scale;
            long aBottom = 1 + Math.abs(random.nextLong() % scale);
            long bTop = random.nextLong() % scale;
            long bBottom = 1 + Math.abs(random.nextLong() % scale);

            var a = Fraction.of(aTop, aBottom);
            var b = Fraction.of(bTop, bBottom);

            // Exact reference: a ± b = (aTop·bBottom ± bTop·aBottom) / (aBottom·bBottom), and a × b = (aTop·bTop) / (aBottom·bBottom).
            var aTopTimesBBottom = BigInteger.valueOf(aTop).multiply(BigInteger.valueOf(bBottom));
            var bTopTimesABottom = BigInteger.valueOf(bTop).multiply(BigInteger.valueOf(aBottom));
            var bottoms = BigInteger.valueOf(aBottom).multiply(BigInteger.valueOf(bBottom));
            var tops = BigInteger.valueOf(aTop).multiply(BigInteger.valueOf(bTop));

            assertEquals(floorOf(aTopTimesBBottom.add(bTopTimesABottom), bottoms), a.add(b).floor());
            assertEquals(floorOf(aTopTimesBBottom.subtract(bTopTimesABottom), bottoms), a.sub(b).floor());
            assertEquals(floorOf(tops, bottoms), a.mul(b).floor());
            assertEquals(-floorOf(tops.negate(), bottoms), a.mul(b).ceil());
            assertEquals(aTopTimesBBottom.compareTo(bTopTimesABottom), Integer.signum(a.compareTo(b)));

            if (bTop != 0) {
                // a ÷ b = (aTop·bBottom) / (aBottom·bTop), with the sign moved to the top.
                var quotientTop = aTopTimesBBottom;
                var quotientBottom = BigInteger.valueOf(aBottom).multiply(BigInteger.valueOf(bTop));
                if (quotientBottom.signum() < 0) {
                    quotientTop = quotientTop.negate();
                    quotientBottom = quotientBottom.negate();
                }

                assertEquals(floorOf(quotientTop, quotientBottom), a.div(b).floor());
            }
        }
    }

    /** The Hungarian solver finds the same minimum total cost as trying every possible assignment. */
    @Test
    void hungarianAssignmentMatchesBruteForce() {
        var random = new Random(7);

        for (int round = 0; round < 300; round++) {
            int rows = 1 + random.nextInt(5);
            int columns = rows + random.nextInt(3);
            var cost = new long[rows][columns];

            for (var row : cost) {
                for (int column = 0; column < columns; column++) {
                    row[column] = random.nextInt(10) == 0 ? 1_000_000_000_000L : random.nextInt(1000);
                }
            }

            var assigned = AssignmentSolver.solve(cost);
            long total = 0;
            var usedColumns = new boolean[columns];

            for (int row = 0; row < rows; row++) {
                assertEquals(false, usedColumns[assigned[row]], "columns must be distinct");
                usedColumns[assigned[row]] = true;
                total += cost[row][assigned[row]];
            }
            assertEquals(bruteForce(cost, 0, new boolean[columns]), total);
        }
    }

    /** Minimum total cost found by trying every assignment of the remaining rows. */
    private static long bruteForce(long[][] cost, int row, boolean[] used) {
        if (row == cost.length) {
            return 0;
        }

        long best = Long.MAX_VALUE;

        for (int column = 0; column < used.length; column++) {
            if (!used[column]) {
                used[column] = true;
                best = Math.min(best, cost[row][column] + bruteForce(cost, row + 1, used));
                used[column] = false;
            }
        }

        return best;
    }

    /** Freeing a booked session makes its time available again. */
    @Test
    void calendarReleaseUndoesReserve() {
        var dataset = new Dataset(0, 1000, List.of(new Location("a", 0, 0)), List.of(), List.of(), List.of(new Charger("c", "h", "a", 60000, "CCS", List.of())),
            List.of(new Tariff(0, 1000, 1)), 10000, 10, 9000, 3500, "offline-euclidean-v1", List.of(), List.of(), Map.of(), null, null);
        var calendar = new ChargerCalendar(dataset);
        var session = List.of(new Action(ActionType.CHARGE, "v", 100, 200, "a", "a", 0, 0, null, "c", 0, 0));

        assertEquals(List.of(0L), calendar.starts("c", 0, 1000, 100, List.of()));
        calendar.reserve(session);
        assertEquals(List.of(200L), calendar.starts("c", 50, 1000, 100, List.of()));
        calendar.release(session);
        assertEquals(List.of(50L), calendar.starts("c", 50, 1000, 100, List.of()));
    }
}
