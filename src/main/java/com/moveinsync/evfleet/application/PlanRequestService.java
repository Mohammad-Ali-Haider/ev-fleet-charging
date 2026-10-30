package com.moveinsync.evfleet.application;

import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.InputRules;
import com.moveinsync.evfleet.domain.JobKind;
import com.moveinsync.evfleet.persistence.PlanRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Imports shift data and accepts idempotent requests for a full plan. */
@Service
public class PlanRequestService {

    private static final double MAX_BUDGET_SECONDS = 25;

    private final PlanRepository repository;

    private final JobQueue queue;

    private final PayloadHasher hasher;

    private final TransactionTemplate transactions;

    /** Receives its collaborators from Spring. */
    public PlanRequestService(PlanRepository repository, JobQueue queue, PayloadHasher hasher, TransactionTemplate transactions) {
        this.repository = repository;
        this.queue = queue;
        this.hasher = hasher;
        this.transactions = transactions;
    }

    /** Validates and stores a new input revision; the current plan becomes stale until re-planned. */
    public long importDataset(String tenant, Dataset dataset) {
        InputRules.validate(dataset);
        if (!dataset.history().isEmpty() || !dataset.historyAssignments().isEmpty() || dataset.prefixEvidence() != null || dataset.asOf() != null) {
            throw new DomainException("IMPORT_CANNOT_SET_INTERNAL_HISTORY");
        }

        return transactions.execute(status -> repository.appendDataset(tenant, dataset));
    }

    /**
     * Queues a full plan of the latest revision. Repeating a key with the same budget returns the original job; reusing it with a different budget is a
     * conflict.
     */
    public String requestPlan(String tenant, String idempotencyKey, double budgetSeconds) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100 || budgetSeconds < 0 || budgetSeconds > MAX_BUDGET_SECONDS) {
            throw new DomainException("INVALID_PLAN_REQUEST");
        }

        String payloadHash = hasher.hash(Map.of("kind", JobKind.INITIAL.value(), "budget", budgetSeconds));

        return transactions.execute(status -> {
            var state = repository.lockState(tenant);
            var existing = repository.findRequest(tenant, idempotencyKey);

            if (existing.isPresent()) {
                if (!payloadHash.equals(existing.get().hash())) {
                    throw new DomainException("IDEMPOTENCY_CONFLICT", 409);
                }

                return existing.get().job();
            }

            String job = queue.enqueue(tenant, state.revision(), state.active(), JobKind.INITIAL, budgetSeconds, System.currentTimeMillis());
            repository.insertRequest(tenant, idempotencyKey, payloadHash, job);

            return job;
        });
    }
}
