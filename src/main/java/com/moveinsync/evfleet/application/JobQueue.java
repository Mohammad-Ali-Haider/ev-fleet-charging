package com.moveinsync.evfleet.application;

import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.JobKind;
import com.moveinsync.evfleet.persistence.PlanRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Admission control for planning jobs: a tenant may hold at most 100 queued or running jobs. */
@Component
public class JobQueue {

    static final int MAX_ACTIVE_JOBS_PER_TENANT = 100;

    private final PlanRepository repository;

    private final MeterRegistry metrics;

    /** Receives the repository and the metrics registry. */
    public JobQueue(PlanRepository repository, MeterRegistry metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    /** Must run inside the caller's transaction, after the tenant state row is locked. */
    public String enqueue(String tenant, long revision, String parent, JobKind kind, double budgetSeconds, long receivedMillis) {
        if (repository.activeJobs(tenant) >= MAX_ACTIVE_JOBS_PER_TENANT) {
            metrics.counter("evfleet.rejections", "reason", "QUEUE_FULL").increment();

            throw new DomainException("QUEUE_FULL", 429);
        }

        return repository.insertJob(tenant, revision, parent, kind, budgetSeconds, receivedMillis);
    }
}
