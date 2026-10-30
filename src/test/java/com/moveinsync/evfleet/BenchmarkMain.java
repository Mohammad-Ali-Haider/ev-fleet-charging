package com.moveinsync.evfleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.ActionType;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Disruption;
import com.moveinsync.evfleet.domain.DisruptionKind;
import com.moveinsync.evfleet.domain.Fixtures;
import com.moveinsync.evfleet.domain.Plan;
import com.moveinsync.evfleet.domain.Tariff;
import com.moveinsync.evfleet.planner.Planner;
import com.moveinsync.evfleet.validator.IndependentValidator;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Real PostgreSQL benchmarks of the full service path (enqueue, claim, plan, validate, publish), plus planner-only quality numbers on the harder rush-hour
 * fixture.
 */
public class BenchmarkMain {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static double elapsed(long start) {
        return (System.nanoTime() - start) / 1e9;
    }

    private static Map<String, Object> stats(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        int n = sorted.size();

        return Map.of("n", n, "p50S", n % 2 == 0 ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2 : sorted.get(n / 2), "p99S",
            sorted.get((int) Math.ceil(.99 * n) - 1), "maxS", sorted.get(n - 1));
    }

    private static void write(String path, Object value) throws Exception {
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(path).toFile(), value);
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw new IllegalStateException(code);
        }
    }

    /** Trips that moved from one cab to another, ignoring backups that became covered. */
    private static long reassigned(Plan before, Plan after) {
        return before.allAssignments().entrySet().stream().filter(e -> e.getValue() != null && after.allAssignments().get(e.getKey()) != null
            && !Objects.equals(e.getValue(), after.allAssignments().get(e.getKey()))).count();
    }

    /** Share of grid energy bought in the cheapest tariff window. */
    private static double cheapestShare(Dataset dataset, Plan plan) {
        long cheapest = dataset.tariffs().stream().mapToLong(Tariff::microsPerKwh).min().orElse(0);
        double total = 0;
        double cheap = 0;

        for (var action : plan.actions()) {
            if (action.kind() != ActionType.CHARGE) {
                continue;
            }
            total += action.gridWh();
            for (var tariff : dataset.tariffs()) {
                long overlap = Math.max(0, Math.min(action.end(), tariff.end()) - Math.max(action.start(), tariff.start()));
                if (tariff.microsPerKwh() == cheapest) {
                    cheap += (double) action.gridWh() * overlap / Math.max(1, action.end() - action.start());
                }
            }
        }

        return total == 0 ? 1 : cheap / total;
    }

    private static Map<String, Integer> reasonCodes(Plan plan) {
        var codes = new TreeMap<String, Integer>();
        plan.backups().values().forEach(reason -> codes.merge(reason.code(), 1, Integer::sum));

        return codes;
    }

    public static void main(String[] args) throws Exception {
        int runs = args.length == 0 ? 10 : Integer.parseInt(args[0]);
        var app = DatabaseSupport.services();
        var raw = new ArrayList<Map<String, Object>>();
        var initialTimes = new ArrayList<Double>();
        var repairTimes = new ArrayList<Double>();
        var failureTimes = new ArrayList<Double>();

        for (int i = 0; i <= runs; i++) {
            String tenant = "benchmark-" + UUID.randomUUID();
            var d = Fixtures.fleet(300, 40, 1500, 6 + i);
            app.requests().importDataset(tenant, d);

            long t = System.nanoTime();
            String job = app.requests().requestPlan(tenant, "initial", 8);
            String id = app.planning().execute(app.repository().claim(job, 60000));
            double initial = elapsed(t);
            var p = app.queries().plan(tenant, id).plan();
            var report = new IndependentValidator().validate(d, p);
            require(report.valid(), "INITIAL_INVALID");

            // Mid-shift drift: the first cab to finish a trip arrives just above the 15% floor.
            var firstTrip = p.actions().stream().filter(a -> a.kind() == ActionType.TRIP).min(
                Comparator.comparingLong(Action::end).thenComparing(Action::vehicleId)).orElseThrow();
            var drift = new Disruption("drift", 1, firstTrip.end(), DisruptionKind.SOC, firstTrip.vehicleId(), 9500L, firstTrip.destination(), 60, Map.of());
            t = System.nanoTime();
            var accepted = app.disruptions().ingest(tenant, drift);
            require("ACCEPTED".equals(accepted.status()), "SOC_NOT_ACCEPTED");
            String repairedId = app.planning().execute(app.repository().claim(accepted.jobId(), 60000));
            double repair = elapsed(t);
            var repaired = app.queries().plan(tenant, repairedId);
            require(app.queries().validate(tenant, repairedId).valid(), "REPAIR_INVALID");

            // Charger failure at the start of a later session on c00, so no session is cut mid-way.
            long failAt = repaired.plan().actions().stream().filter(a -> "c00".equals(a.chargerId()) && a.start() >= drift.at()).mapToLong(
                Action::start).min().orElse(drift.at());
            t = System.nanoTime();
            accepted = app.disruptions().ingest(tenant, new Disruption("outage", 1, failAt, DisruptionKind.CHARGER_FAILURE, "c00", null, null, 60, Map.of()));
            require("ACCEPTED".equals(accepted.status()), "FAILURE_NOT_ACCEPTED");
            String failedId = app.planning().execute(app.repository().claim(accepted.jobId(), 60000));
            double failure = elapsed(t);
            var failed = app.queries().plan(tenant, failedId);
            require(app.queries().validate(tenant, failedId).valid(), "FAILURE_INVALID");

            var sample = new LinkedHashMap<String, Object>();
            sample.put("seed", 6 + i);
            sample.put("warmup", i == 0);
            sample.put("initialS", initial);
            sample.put("socRepairS", repair);
            sample.put("chargerFailureS", failure);
            sample.put("initialBackups", p.totalBackupCount());
            sample.put("initialChargingSessions", p.actions().stream().filter(a -> a.kind() == ActionType.CHARGE).count());
            sample.put("initialCostMicros", p.timelineCostMicros());
            sample.put("cheapestWindowShare", cheapestShare(d, p));
            sample.put("lowerBound", p.lowerBound());
            sample.put("backupGap", p.backupCount() - p.lowerBound());
            sample.put("repairReassigned", reassigned(p, repaired.plan()));
            sample.put("repairBackups", repaired.plan().totalBackupCount());
            sample.put("failureReassigned", reassigned(repaired.plan(), failed.plan()));
            sample.put("failureBackups", failed.plan().totalBackupCount());
            sample.put("initialValidation", report);
            sample.put("repairValidation", repaired.plan().validation());
            sample.put("failureValidation", failed.plan().validation());
            raw.add(sample);
            if (i > 0) {
                initialTimes.add(initial);
                repairTimes.add(repair);
                failureTimes.add(failure);
            }
            if (i == runs) {
                write("examples/scale-dataset.json", d);
                write("evidence/scale-plan.json", p);
                write("evidence/scale-repaired-plan.json", repaired.plan());
            }
            System.out.println(JSON.writeValueAsString(Map.of("seed", 6 + i, "initialS", initial, "repairS", repair, "failureS", failure, "backups",
                p.totalBackupCount(), "repairReassigned", reassigned(p, repaired.plan()))));
        }

        var rushHour = new ArrayList<Map<String, Object>>();

        for (int seed = 1; seed <= 3; seed++) {
            var d = Fixtures.rushHour(seed);
            long t = System.nanoTime();
            var p = Planner.standard().solve(d, 25, null);
            double seconds = elapsed(t);
            var row = new LinkedHashMap<String, Object>();
            row.put("seed", seed);
            row.put("seconds", seconds);
            row.put("backups", p.backupCount());
            row.put("lowerBound", p.lowerBound());
            row.put("backupReasons", reasonCodes(p));
            row.put("costMicros", p.mutableCostMicros());
            row.put("cheapestWindowShare", cheapestShare(d, p));
            row.put("valid", p.validation().valid());
            rushHour.add(row);
            System.out.println(JSON.writeValueAsString(row));
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("language", "Java 17 / Spring Boot 3.5.0");
        result.put("machine", JSON.readValue(Path.of("evidence/machine.json").toFile(), Map.class));
        result.put("jvm", System.getProperty("java.runtime.version"));
        result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("workload", Map.of("vehicles", 300, "chargers", 40, "trips", 1500, "warmups", 1, "measuredRuns", runs, "seeds", "6.." + (6 + runs),
            "transport", "Direct application services + real PostgreSQL; includes enqueue, claim, preparation, independent checks, JSON and commit"));
        result.put("initial", stats(initialTimes));
        result.put("socRepair", stats(repairTimes));
        result.put("chargerFailure", stats(failureTimes));
        result.put("initialTargetUnder30S", Collections.max(initialTimes) < 30);
        result.put("repairTargetAtMost2S", Math.max(Collections.max(repairTimes), Collections.max(failureTimes)) <= 2);
        result.put("sequentialPlanThroughputPerSecond", runs / initialTimes.stream().mapToDouble(x -> x).sum());
        result.put("rushHourPlannerOnly", rushHour);
        result.put("rawSamples", raw);
        result.put("quantileNote", "Nearest-rank p99 over ten samples is the observed maximum; not a production SLA.");
        write("evidence/benchmark.json", result);
        ScalingMain.measure();
        write("examples/demo-dataset.json", Fixtures.demo());
        System.out.println("JAVA_BENCHMARK_COMPLETE");
    }

    static long peakHeapBytes() {
        return ManagementFactory.getMemoryPoolMXBeans().stream().filter(x -> x.getType() == java.lang.management.MemoryType.HEAP).mapToLong(
            x -> x.getPeakUsage().getUsed()).sum();
    }
}
