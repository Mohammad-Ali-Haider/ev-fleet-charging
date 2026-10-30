package com.moveinsync.evfleet.application;

import com.moveinsync.evfleet.application.Views.DisruptionOutcome;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Disruption;
import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.InputRules;
import com.moveinsync.evfleet.domain.JobKind;
import com.moveinsync.evfleet.persistence.PlanRepository;
import com.moveinsync.evfleet.planner.RepairPreparer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts live events (battery drift, charger failure) and turns each into a durable repair job.
 *
 * <ul>
 * <li>Duplicates: the same event id with the same payload returns the first outcome; a different payload is a conflict.</li>
 * <li>Out of order: an event older (by sequence or time) than the last applied one for the same asset is stored as {@code IGNORED_OLD} and changes
 * nothing.</li>
 * <li>Safety: an observation the plan cannot safely absorb marks the tenant {@code INCIDENT} so no plan is offered as dispatchable until an operator
 * re-imports.</li>
 * </ul>
 */
@Service
public class DisruptionService {

    // The repair search gets 1.5 s of the 2 s target; the rest covers queueing, checks and commit.
    static final double REPAIR_BUDGET_SECONDS = 1.5;

    private final PlanRepository repository;

    private final PlanCache cache;

    private final JobQueue queue;

    private final RepairPreparer preparer;

    private final PayloadHasher hasher;

    private final TransactionTemplate transactions;

    private final MeterRegistry metrics;

    /** Receives its collaborators from Spring. */
    public DisruptionService(PlanRepository repository, PlanCache cache, JobQueue queue, RepairPreparer preparer, PayloadHasher hasher,
        TransactionTemplate transactions, MeterRegistry metrics) {
        this.repository = repository;
        this.cache = cache;
        this.queue = queue;
        this.preparer = preparer;
        this.hasher = hasher;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    /** Handles one live event in a single transaction and counts the outcome. */
    public DisruptionOutcome ingest(String tenant, Disruption event) {
        long received = System.currentTimeMillis();
        String payloadHash = hasher.hash(event);
        var outcome = transactions.execute(status -> apply(tenant, event, payloadHash, received));
        metrics.counter("evfleet.events", "status", outcome.status()).increment();

        return outcome;
    }

    /** Dedupes, orders, prepares the repair input and queues a repair job (or records an incident). */
    private DisruptionOutcome apply(String tenant, Disruption event, String payloadHash, long received) {
        var state = repository.lockState(tenant);
        // Same event id seen before: return the first outcome, or 409 if the payload differs.
        var duplicate = repository.findEvent(tenant, event.eventId());

        if (duplicate.isPresent()) {
            if (!payloadHash.equals(duplicate.get().hash())) {
                throw new DomainException("EVENT_ID_CONFLICT", 409);
            }

            return new DisruptionOutcome(duplicate.get().status(), duplicate.get().job());
        }

        Dataset dataset = cache.dataset(tenant, state.revision());
        InputRules.event(event, dataset);

        String asset = event.kind().value() + ":" + event.assetId();
        // An event older than the last one applied for this asset changes nothing.
        var previous = repository.latestAppliedEvent(tenant, asset);
        String status;
        String job = null;
        String error = null;

        if (previous.isPresent() && (event.sequence() <= previous.get().sequence() || event.at() < previous.get().at())) {
            status = "IGNORED_OLD";
        } else {
            if (state.active() == null) {
                throw new DomainException("NO_ACTIVE_PLAN", 409);
            }
            if (!"CURRENT".equals(state.status())) {
                metrics.counter("evfleet.rejections", "reason", "REPAIR_PENDING_OR_INCIDENT").increment();

                throw new DomainException("REPAIR_PENDING_OR_INCIDENT", 409);
            }

            // Build the reduced re-plan input; an unsafe reading becomes an incident instead.
            Dataset replacement = null;

            try {
                replacement = preparer.prepare(dataset, cache.plan(tenant, state.active()).plan(), event);
            } catch (DomainException.SafetyIncident e) {
                error = e.getMessage();
            }
            if (error != null) {
                repository.markIncident(tenant);
                status = "INCIDENT";
            } else {
                // Store the new input and queue a repair job with a 1.5 s planning budget.
                long next = repository.appendDataset(tenant, replacement);
                job = queue.enqueue(tenant, next, state.active(), JobKind.REPAIR, REPAIR_BUDGET_SECONDS, received);
                status = "ACCEPTED";
            }
        }
        repository.insertEvent(tenant, event.eventId(), asset, event.sequence(), event.at(), payloadHash, status, job, event, error);

        return new DisruptionOutcome(status, job);
    }
}
