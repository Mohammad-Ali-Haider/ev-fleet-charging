package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.InputRules;
import com.moveinsync.evfleet.domain.Plan;
import com.moveinsync.evfleet.domain.Reason;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Greedy construction plus local search, run as phases on one {@link PlanningRun}:
 *
 * <ol>
 * <li>{@link BatchConstruction}: assign trips batch by batch, with just-in-time and idle charging.</li>
 * <li>{@link BackupRescue}: try to cover each backup trip by moving a few trips between nearby cabs.</li>
 * <li>{@link ChargingPasses#shiftToCheaperWindows()}: move each cab's charging into the cheapest window its timetable allows.</li>
 * <li>{@link BackupExplainer}: say why each remaining backup trip could not be served.</li>
 * </ol>
 *
 * <p>
 * Every phase checks the deadline and leaves a complete, safe schedule behind.
 */
public final class GreedyPlanner implements Planner {

    // Explanations get their own short allowance so a budget-limited plan still says why.
    private static final long EXPLAIN_ALLOWANCE_NANOS = 300_000_000L;

    private final boolean localSearch;

    private final EnergyModel energy = new EnergyModel();

    /** Planner with local search switched on. */
    public GreedyPlanner() {
        this(true);
    }

    /**
     * @param localSearch
     *            false runs construction only, for measuring what local search adds
     */
    public GreedyPlanner(boolean localSearch) {
        this.localSearch = localSearch;
    }

    /** Plans the dataset: construct, rescue backups, shift charging, explain backups. */
    @Override
    public Plan solve(Dataset dataset, double budgetSeconds, Plan previous) {
        long started = System.nanoTime();
        long deadline = started + (long) (Math.max(0, budgetSeconds) * 1e9);
        InputRules.validate(dataset);

        for (var vehicle : dataset.vehicles()) {
            if (vehicle.energyWh() < energy.floor(vehicle)) {
                throw new DomainException.SafetyIncident("INITIAL_FLOOR");
            }
        }

        var run = new PlanningRun(dataset, previous == null ? Map.of() : previous.allAssignments(), deadline, energy);
        var charging = new ChargingPasses(run);
        new BatchConstruction(run, charging).construct();
        if (localSearch) {
            new BackupRescue(run).rescueBackups();
            charging.shiftToCheaperWindows();
        }

        return toPlan(run, (System.nanoTime() - started) / 1e9);
    }

    /** Collects assignments, backup reasons and the change count into a Plan. */
    private Plan toPlan(PlanningRun run, double elapsedSeconds) {
        var assignments = new LinkedHashMap<String, String>();
        var reasons = new LinkedHashMap<String, Reason>();
        var explainer = new BackupExplainer(run.dataset, run.legs, run.stops);
        long explainBy = Math.max(run.deadline, System.nanoTime() + EXPLAIN_ALLOWANCE_NANOS);

        for (var trip : run.trips) {
            String vehicle = run.schedule.assignee(trip.id());
            assignments.put(trip.id(), vehicle);
            if (vehicle == null) {
                reasons.put(trip.id(), run.notTried(trip) ? explainer.searchBudget() : explainer.explain(trip, run.schedule, explainBy));
            }
        }

        int changes = 0;

        for (var entry : assignments.entrySet()) {
            if (run.preferred.containsKey(entry.getKey()) && !Objects.equals(run.preferred.get(entry.getKey()), entry.getValue())) {
                changes++;
            }
        }

        var dataset = run.dataset;

        return new Plan(run.schedule.allActions(), assignments, reasons, dataset.history(), dataset.historyAssignments(), new LowerBound().calculate(dataset),
            changes, run.exhausted(), null, elapsedSeconds, dataset.asOf());
    }
}
