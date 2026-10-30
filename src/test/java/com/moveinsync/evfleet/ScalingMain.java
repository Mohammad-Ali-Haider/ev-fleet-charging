package com.moveinsync.evfleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.evfleet.domain.Fixtures;
import com.moveinsync.evfleet.planner.Planner;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/** Where the planner stops scaling: fixed 2-second budget, growing fleets with the same trips-per-cab ratio. */
public class ScalingMain {

    public static void main(String[] args) throws Exception {
        measure();
    }

    static void measure() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();

        for (int n : new int[]{100, 300, 600, 1200, 2000}) {
            var d = Fixtures.fleet(n, Math.max(1, n * 40 / 300), n * 5, 6);
            System.gc();
            for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
                pool.resetPeakUsage();
            }

            long started = System.nanoTime();
            var p = Planner.standard().solve(d, 2, null);
            double seconds = (System.nanoTime() - started) / 1e9;
            rows.add(Map.of("vehicles", n, "trips", n * 5, "chargers", d.chargers().size(), "seconds", seconds, "backups", p.totalBackupCount(), "valid",
                p.validation().valid(), "searchExhausted", p.searchExhausted(), "sumPoolPeakHeapBytes", BenchmarkMain.peakHeapBytes()));
            System.out.println(rows.get(rows.size() - 1));
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("evidence/scaling.json").toFile(),
            Map.of("notes",
                "Full planner (construction, rescue, charging re-plan, validation) under a fixed 2-second budget, warm JVM, GC before each size. Sum of "
                    + "heap-pool peaks is approximate, not process RSS. Quality can decline under the time cap while validity remains mandatory.",
                "samples", rows));
    }
}
