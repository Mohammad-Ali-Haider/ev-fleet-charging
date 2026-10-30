package com.moveinsync.evfleet.application;

import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.JobKind;
import com.moveinsync.evfleet.persistence.PlanRepository;
import com.moveinsync.evfleet.persistence.model.PlanningJob;
import com.moveinsync.evfleet.planner.Planner;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one claimed planning job: plan outside any transaction, then publish atomically. A failed repair queues a single fallback re-plan of the same revision
 * so the tenant does not stay stale.
 */
@Service
public class PlanningService {

    private static final Logger LOG = LoggerFactory.getLogger(PlanningService.class);

    static final double FALLBACK_BUDGET_SECONDS = 10;

    private final PlanRepository repository;

    private final PlanCache cache;

    private final Planner planner;

    private final JobQueue queue;

    private final TransactionTemplate transactions;

    private final MeterRegistry metrics;

    /** Receives its collaborators from Spring. */
    public PlanningService(PlanRepository repository, PlanCache cache, Planner planner, JobQueue queue, TransactionTemplate transactions,
        MeterRegistry metrics) {
        this.repository = repository;
        this.cache = cache;
        this.planner = planner;
        this.queue = queue;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    /** Runs a job with no test failpoint. */
    public String execute(PlanningJob job) {
        return execute(job, null);
    }

    /**
     * @param failpoint
     *            test hook invoked at named points inside publication
     */
    public String execute(PlanningJob job, Consumer<String> failpoint) {
        long started = System.nanoTime();
        String kind = job.kind().value();

        try {
            var dataset = cache.dataset(job.tenant(), job.revision());
            var parent = job.kind() == JobKind.REPAIR && job.parent() != null ? cache.plan(job.tenant(), job.parent()).plan() : null;
            double remaining = Math.max(0, job.budgetS() - (System.currentTimeMillis() - job.created()) / 1000.0);

            long planning = System.nanoTime();
            // Plan outside any transaction; the planner checks its own result with the validator.
            var plan = planner.solve(dataset, remaining, parent);
            metrics.timer("evfleet.planner.seconds", "kind", kind).record(System.nanoTime() - planning, TimeUnit.NANOSECONDS);

            // Publish atomically; refused if the input moved on while we were planning.
            String id = repository.publish(job, plan, failpoint);
            long total = System.currentTimeMillis() - job.created();
            metrics.counter("evfleet.jobs", "kind", kind, "outcome", "SUCCEEDED").increment();
            metrics.timer("evfleet.end.to.end", "kind", kind).record(total, TimeUnit.MILLISECONDS);
            if (total > job.kind().targetMillis()) {
                metrics.counter("evfleet.deadline.misses", "kind", kind).increment();
            }
            LOG.atInfo().addKeyValue("event", "PLAN_PUBLISHED").addKeyValue("tenant", job.tenant()).addKeyValue("jobId", job.id()).addKeyValue("kind",
                kind).addKeyValue("planId", id).addKeyValue("endToEndMs", total).addKeyValue("backups", plan.backupCount()).addKeyValue("changedAssignments",
                    plan.changedAssignments()).log("Plan published");

            return id;
        } catch (RuntimeException e) {
            String code = e instanceof DomainException ? e.getMessage() : "WORKER_ERROR";

            if (code.equals("STALE_PUBLICATION")) {
                metrics.counter("evfleet.publication.conflicts").increment();
            }
            repository.fail(job, code);
            metrics.counter("evfleet.jobs", "kind", kind, "outcome", "FAILED").increment();
            LOG.atWarn().addKeyValue("event", "JOB_FAILED").addKeyValue("tenant", job.tenant()).addKeyValue("jobId", job.id()).addKeyValue("kind",
                kind).addKeyValue("error", code).log("Planning job failed");
            if (job.kind() == JobKind.REPAIR && !code.equals("STALE_PUBLICATION")) {
                queueFallback(job);
            }

            throw e;
        } finally {
            metrics.timer("evfleet.job.seconds", "kind", kind).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /** Re-plans the repair's revision from scratch, only while that revision is still the pending one. */
    private void queueFallback(PlanningJob failed) {
        try {
            transactions.executeWithoutResult(status -> {
                var state = repository.lockState(failed.tenant());

                if (state.revision() == failed.revision() && Objects.equals(state.active(), failed.parent()) && "STALE".equals(state.status())) {
                    queue.enqueue(failed.tenant(), failed.revision(), failed.parent(), JobKind.INITIAL, FALLBACK_BUDGET_SECONDS, System.currentTimeMillis());
                    metrics.counter("evfleet.repair.fallbacks").increment();
                }
            });
        } catch (RuntimeException e) {
            // The original failure is what the caller must see; the lease still lets an operator retry.
            LOG.atWarn().addKeyValue("event", "FALLBACK_NOT_QUEUED").addKeyValue("tenant", failed.tenant()).addKeyValue("jobId", failed.id()).log(
                "Fallback re-plan could not be queued");
        }
    }
}
