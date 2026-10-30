package com.moveinsync.evfleet;

import static org.junit.jupiter.api.Assertions.*;

import com.moveinsync.evfleet.application.PlanningService;
import com.moveinsync.evfleet.domain.Disruption;
import com.moveinsync.evfleet.domain.DisruptionKind;
import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.Fixtures;
import com.moveinsync.evfleet.domain.JobKind;
import com.moveinsync.evfleet.domain.Plan;
import com.moveinsync.evfleet.persistence.PlanRepository;
import com.moveinsync.evfleet.persistence.model.PlanningJob;
import com.moveinsync.evfleet.planner.Planner;
import com.moveinsync.evfleet.validator.IndependentValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Storage, job queue, concurrency and crash tests against a real PostgreSQL database. */
class PersistenceTests {

    DatabaseSupport.Services app;

    PlanRepository repo;

    /** A fresh tenant per test, so tests never see each other's data. */
    String tenant;

    @BeforeEach
    void setup() {
        app = DatabaseSupport.services();
        repo = app.repository();
        tenant = "java-test-" + UUID.randomUUID();
    }

    PlanningService planning() {
        return app.planning();
    }

    /** Imports the 2-cab demo day, plans it and publishes the plan; returns the plan id. */
    String publishDemoPlan() {
        app.requests().importDataset(tenant, Fixtures.demo());
        String job = app.requests().requestPlan(tenant, "initial", 3);

        return planning().execute(repo.claim(job, 60000));
    }

    /** A plan for the demo day computed directly, without the queue. */
    static Plan demoPlan() {
        return Planner.standard().solve(Fixtures.demo(), 1, null);
    }

    /** A battery report from cab ev-b at the office. */
    Disruption batteryReport(String eventId, long sequence, long chargeWh) {
        return new Disruption(eventId, sequence, 0, DisruptionKind.SOC, "ev-b", chargeWh, "office", 60, Map.of());
    }

    /** Repeating an idempotency key returns the same job; reusing it with another payload is a conflict; other tenants see nothing. */
    @Test
    void duplicateRequestAndTenantIsolation() {
        var planId = publishDemoPlan();
        var job = app.requests().requestPlan(tenant, "next", 3);

        assertEquals(job, app.requests().requestPlan(tenant, "next", 3));

        var conflict = assertThrows(DomainException.class, () -> app.requests().requestPlan(tenant, "next", 1));

        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getMessage());
        assertThrows(DomainException.class, () -> app.queries().plan("other", planId));

        var stored = app.queries().plan(tenant, planId);

        assertTrue(new IndependentValidator().validate(repo.dataset(tenant, stored.inputRevision()), stored.plan()).valid());
    }

    /** Two jobs publishing at the same instant: exactly one wins, the other is refused. */
    @Test
    void concurrentPublishersHaveExactlyOneWinner() throws Exception {
        app.requests().importDataset(tenant, Fixtures.demo());
        var first = repo.claim(app.requests().requestPlan(tenant, "a", 3), 60000);
        var second = repo.claim(app.requests().requestPlan(tenant, "b", 3), 60000);
        var plan = demoPlan();
        var startTogether = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);

        try {
            var outcomes = new ArrayList<Future<String>>();

            for (var job : List.of(first, second)) {
                outcomes.add(pool.submit(() -> {
                    startTogether.await();
                    try {
                        return repo.publish(job, plan, null);
                    } catch (DomainException e) {
                        return e.getMessage();
                    }
                }));
            }

            var results = List.of(outcomes.get(0).get(10, TimeUnit.SECONDS), outcomes.get(1).get(10, TimeUnit.SECONDS));

            assertEquals(1, results.stream().filter("STALE_PUBLICATION"::equals).count());
            assertTrue(app.queries().plan(tenant, null).dispatchable());
        } finally {
            pool.shutdownNow();
        }
    }

    /** A plan computed before a live event can no longer be published after it. */
    @Test
    void concurrentTelemetryCannotActivateAnOldCandidate() {
        publishDemoPlan();
        var olderJob = repo.claim(app.requests().requestPlan(tenant, "race", 3), 60000);
        var plan = demoPlan();
        var accepted = app.disruptions().ingest(tenant, batteryReport("soc", 1, 9000));

        var refused = assertThrows(DomainException.class, () -> repo.publish(olderJob, plan, null));

        assertEquals("STALE_PUBLICATION", refused.getMessage());
        assertFalse(app.queries().plan(tenant, null).dispatchable());

        planning().execute(repo.claim(accepted.jobId(), 60000));

        assertTrue(app.queries().plan(tenant, null).dispatchable());
    }

    /** The same event sent twice at once gives one outcome; an older event is ignored; a reused id with new content is rejected. */
    @Test
    void duplicateAndOutOfOrderEventsAreDurable() throws Exception {
        publishDemoPlan();
        var event = batteryReport("same", 2, 9000);
        var pool = Executors.newFixedThreadPool(2);

        try {
            var first = pool.submit(() -> app.disruptions().ingest(tenant, event));
            var second = pool.submit(() -> app.disruptions().ingest(tenant, event));

            assertEquals(first.get(), second.get());

            planning().execute(repo.claim(first.get().jobId(), 60000));
        } finally {
            pool.shutdownNow();
        }

        assertEquals("IGNORED_OLD", app.disruptions().ingest(tenant, batteryReport("older", 1, 9000)).status());
        assertThrows(DomainException.class, () -> app.disruptions().ingest(tenant, batteryReport("same", 2, 9500)));
    }

    /** A worker whose lease expired cannot publish, before or after another worker re-claims the job. */
    @Test
    void expiredLeaseIsFencedEvenBeforeReclaim() {
        app.requests().importDataset(tenant, Fixtures.demo());
        String jobId = app.requests().requestPlan(tenant, "lease", 3);
        var expired = repo.claim(jobId, -1);
        var plan = demoPlan();

        assertThrows(DomainException.class, () -> repo.publish(expired, plan, null));

        var reclaimed = repo.claim(jobId, 60000);

        assertTrue(reclaimed.generation() > expired.generation());
        assertThrows(DomainException.class, () -> repo.publish(expired, plan, null));

        repo.publish(reclaimed, plan, null);
    }

    /** A failure inside publication rolls everything back; a failure after commit leaves the plan published. */
    @Test
    void rollbackAndLostCommitResponseAreDistinct() {
        app.requests().importDataset(tenant, Fixtures.demo());
        String jobId = app.requests().requestPlan(tenant, "fault", 3);
        var job = repo.claim(jobId, 60000);
        var plan = demoPlan();

        assertThrows(IllegalStateException.class, () -> repo.publish(job, plan, stage -> {
            if (stage.equals("after_plan_insert")) {
                throw new IllegalStateException("injected");
            }
        }));
        assertEquals(0, repo.planCount(tenant));

        assertThrows(IllegalStateException.class, () -> repo.publish(job, plan, stage -> {
            if (stage.equals("after_commit")) {
                throw new IllegalStateException("lost response");
            }
        }));
        assertEquals("SUCCEEDED", repo.getJob(tenant, jobId).status());
        assertTrue(app.queries().plan(tenant, null).dispatchable());
        assertEquals(jobId, app.requests().requestPlan(tenant, "fault", 3));
    }

    /** A below-floor reading marks the tenant unsafe, and no older job can publish over it. */
    @Test
    void incidentIsDurableAndCannotBeOverwritten() {
        publishDemoPlan();
        var olderJob = repo.claim(app.requests().requestPlan(tenant, "old", 3), 60000);

        assertEquals("INCIDENT", app.disruptions().ingest(tenant, batteryReport("bad", 1, 100)).status());
        assertFalse(app.queries().plan(tenant, null).dispatchable());
        assertThrows(DomainException.class, () -> repo.publish(olderJob, demoPlan(), null));
    }

    /** A second event while a repair is pending is refused, and a tenant cannot queue more than 100 jobs. */
    @Test
    void pendingInputsAndPerTenantQueueAdmission() {
        publishDemoPlan();
        app.disruptions().ingest(tenant, batteryReport("accepted", 1, 9000));

        assertThrows(DomainException.class, () -> app.disruptions().ingest(tenant, batteryReport("pending", 2, 9500)));

        for (int i = 0; i < 99; i++) {
            app.requests().requestPlan(tenant, "q" + i, 3);
        }
        var full = assertThrows(DomainException.class, () -> app.requests().requestPlan(tenant, "overflow", 3));

        assertEquals("QUEUE_FULL", full.getMessage());
    }

    /** Failure injection: a repair that fails queues exactly one fallback re-plan, which makes the plan dispatchable again. */
    @Test
    void failedRepairQueuesOneFallbackThatRecoversDispatchability() {
        publishDemoPlan();
        var accepted = app.disruptions().ingest(tenant, batteryReport("drift", 1, 9000));
        var repair = repo.claim(accepted.jobId(), 60000);

        assertThrows(DomainException.class, () -> planning().execute(repair, stage -> {
            if (stage.equals("after_plan_insert")) {
                throw new DomainException("INJECTED_FAILURE", 500);
            }
        }));
        assertEquals("FAILED", repo.getJob(tenant, repair.id()).status());
        assertFalse(app.queries().plan(tenant, null).dispatchable());

        var fallback = repo.jobs(tenant).stream().filter(job -> job.status().equals("QUEUED")).toList();

        assertEquals(1, fallback.size());
        assertEquals(JobKind.INITIAL, fallback.get(0).kind());
        assertEquals(repair.revision(), fallback.get(0).revision());
        assertEquals(1.0, app.metrics().counter("evfleet.repair.fallbacks").count());

        planning().execute(repo.claim(fallback.get(0).id(), 60000));

        assertTrue(app.queries().plan(tenant, null).dispatchable());
    }

    /** A separate JVM is killed in the middle of publishing: nothing is saved, and the job is recovered after its lease expires. */
    @Test
    void killedProcessRollsBackThenLeaseRecoveryPublishes() throws Exception {
        app.requests().importDataset(tenant, Fixtures.demo());
        String jobId = app.requests().requestPlan(tenant, "kill", 3);
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = System.getProperty("java.home") + "/bin/java";

        var child = new ProcessBuilder(java, "-Duser.timezone=UTC", "-cp", classPath, CrashChild.class.getName(), jobId).redirectErrorStream(true).start();

        assertTrue(child.waitFor(20, TimeUnit.SECONDS));
        assertEquals(77, child.exitValue(), new String(child.getInputStream().readAllBytes()));
        assertEquals(0, repo.planCount(tenant));

        PlanningJob recovered = null;
        for (int i = 0; i < 70 && recovered == null; i++) {
            Thread.sleep(100);
            recovered = repo.claim(jobId, 60000);
        }

        assertNotNull(recovered);

        planning().execute(recovered);

        assertTrue(app.queries().plan(tenant, null).dispatchable());
    }
}
