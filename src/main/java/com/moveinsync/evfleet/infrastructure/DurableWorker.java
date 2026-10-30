package com.moveinsync.evfleet.infrastructure;

import com.moveinsync.evfleet.application.PlanningService;
import com.moveinsync.evfleet.persistence.PlanRepository;
import com.moveinsync.evfleet.persistence.model.PlanningJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Claims durable jobs from PostgreSQL and runs them on a small pool, so a long initial plan for one tenant cannot hold up another tenant's 2-second repair.
 * Jobs survive crashes: an unfinished job's lease expires and the next poll claims it again under a new generation.
 */
@Component
@ConditionalOnProperty(name = "ev.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DurableWorker implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(DurableWorker.class);

    private static final long LEASE_MILLIS = 60_000;

    private final PlanRepository repository;

    private final PlanningService service;

    private final MeterRegistry metrics;

    private final int threads;

    private final ExecutorService pool;

    private final AtomicInteger busy = new AtomicInteger();

    private final AtomicInteger depth = new AtomicInteger();

    private final AtomicReference<Double> oldestSeconds = new AtomicReference<>(0.0);

    /** Creates the thread pool and registers the queue gauges. */
    public DurableWorker(PlanRepository repository, PlanningService service, MeterRegistry metrics, @Value("${ev.worker.threads:2}") int threads) {
        this.repository = repository;
        this.service = service;
        this.metrics = metrics;
        this.threads = Math.max(1, threads);
        this.pool = Executors.newFixedThreadPool(this.threads);
        metrics.gauge("evfleet.queue.depth", depth);
        metrics.gauge("evfleet.queue.oldest.seconds", oldestSeconds, AtomicReference::get);
        metrics.gauge("evfleet.worker.busy", busy);
    }

    /** One indexed query per poll while a thread is free; nothing runs on the scheduler thread itself. */
    @Scheduled(fixedDelay = 100)
    public void poll() {
        while (busy.get() < threads) {
            PlanningJob job;

            try {
                job = repository.claim(null, LEASE_MILLIS);
            } catch (Exception e) {
                metrics.counter("evfleet.worker.errors").increment();
                LOG.atWarn().addKeyValue("event", "CLAIM_FAILED").log("Could not claim a planning job");

                return;
            }
            if (job == null) {
                return;
            }
            if (job.generation() > 1) {
                // A previous holder crashed or timed out before finishing.
                metrics.counter("evfleet.worker.retries").increment();
            }
            busy.incrementAndGet();
            pool.execute(() -> run(job));
        }
    }

    /** Runs one job on a pool thread and frees the slot afterwards. */
    private void run(PlanningJob job) {
        try {
            service.execute(job);
        } catch (Exception e) {
            // PlanningService already recorded the failure; the job row says why.
            metrics.counter("evfleet.worker.errors").increment();
        } finally {
            busy.decrementAndGet();
        }
    }

    /** Updates queue depth and oldest-job age once a second. */
    @Scheduled(fixedDelay = 1000)
    public void refreshQueueGauges() {
        try {
            depth.set(repository.queueDepth());
            oldestSeconds.set(repository.oldestAge());
        } catch (Exception e) {
            LOG.atWarn().addKeyValue("event", "QUEUE_GAUGE_FAILED").log("Could not refresh queue gauges");
        }
    }

    /** Lets running jobs finish when the app shuts down. */
    @Override
    public void destroy() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}
