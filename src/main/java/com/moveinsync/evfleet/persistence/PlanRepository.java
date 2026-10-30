package com.moveinsync.evfleet.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.JobKind;
import com.moveinsync.evfleet.domain.Plan;
import com.moveinsync.evfleet.persistence.model.PlanRow;
import com.moveinsync.evfleet.persistence.model.PlanningJob;
import com.moveinsync.evfleet.persistence.model.PlanningState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant-scoped storage. Every query filters by tenant; dataset and plan rows are immutable once written, and the database owns publication ordering through
 * the locked tenant state row.
 *
 * <p>
 * Methods that change more than one row must run inside the caller's transaction; the queue methods ({@link #claim}, {@link #publish}, {@link #fail}) manage
 * their own.
 */
@Repository
public class PlanRepository {

    /** A stored live event: its payload hash for idempotency and its outcome. */
    public record EventRecord(String id, long sequence, long at, String hash, String status, String job) {
    }

    /** A stored plan request keyed by the client's idempotency key. */
    public record RequestRecord(String key, String hash, String job) {
    }

    private final JdbcTemplate jdbc;

    private final TransactionTemplate transactions;

    private final ObjectMapper mapper;

    /** Sets up JDBC and transactions over the given database. */
    public PlanRepository(DataSource source, ObjectMapper mapper) {
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.mapper = mapper;
    }

    /** Object to JSON text for storage. */
    private String json(Object object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (Exception e) {
            throw new DomainException("SERIALIZATION_FAILED", 500);
        }
    }

    /** Stored JSON text back to an object. */
    private <T> T decode(String body, Class<T> type) {
        try {
            return mapper.readValue(body, type);
        } catch (Exception e) {
            throw new DomainException("STORED_DATA_INVALID", 500);
        }
    }

    // ---- Tenant state ----------------------------------------------------------------------------

    /** Reads a tenant's state row, optionally locking it. */
    private PlanningState state(String tenant, boolean lock) {
        var rows = jdbc.query("SELECT * FROM ev6_state WHERE tenant=?" + (lock ? " FOR UPDATE" : ""),
            (r, n) -> new PlanningState(r.getString("tenant"), r.getLong("revision"), r.getString("active"), r.getString("status")), tenant);

        if (rows.isEmpty()) {
            throw new DomainException("DATASET_NOT_FOUND", 404);
        }

        return rows.get(0);
    }

    /** Current revision, active plan and status, read without locking. */
    public PlanningState state(String tenant) {
        return state(tenant, false);
    }

    /** Locks the tenant's state row until the surrounding transaction ends; serialises writers per tenant. */
    public PlanningState lockState(String tenant) {
        return state(tenant, true);
    }

    /** Marks the tenant unsafe; no plan is dispatchable until a re-import. */
    public void markIncident(String tenant) {
        jdbc.update("UPDATE ev6_state SET status='INCIDENT' WHERE tenant=?", tenant);
    }

    // ---- Datasets -----------------------------------------------------------------------------------

    /** Stores a new input revision (creating the tenant on first import) and marks the plan stale. */
    public long appendDataset(String tenant, Dataset dataset) {
        jdbc.update("INSERT INTO ev6_state VALUES(?,0,NULL,'STALE') ON CONFLICT DO NOTHING", tenant);

        long next = lockState(tenant).revision() + 1;
        jdbc.update("INSERT INTO ev6_datasets VALUES(?,?,CAST(? AS JSONB))", tenant, next, json(dataset));
        jdbc.update("UPDATE ev6_state SET revision=?,status='STALE' WHERE tenant=?", next, tenant);

        return next;
    }

    /** Loads one input revision. */
    public Dataset dataset(String tenant, long revision) {
        var rows = jdbc.queryForList("SELECT body::text AS body FROM ev6_datasets WHERE tenant=? AND revision=?", tenant, revision);

        if (rows.isEmpty()) {
            throw new DomainException("DATASET_NOT_FOUND", 404);
        }

        return decode((String) rows.get(0).get("body"), Dataset.class);
    }

    // ---- Plans --------------------------------------------------------------------------------------

    /** Loads one published plan. */
    public PlanRow plan(String tenant, String id) {
        var rows = jdbc.queryForList("SELECT revision,body::text AS body FROM ev6_plans WHERE tenant=? AND id=?", tenant, id);

        if (rows.isEmpty()) {
            throw new DomainException("PLAN_NOT_FOUND", 404);
        }

        var row = rows.get(0);

        return new PlanRow(id, ((Number) row.get("revision")).longValue(), decode((String) row.get("body"), Plan.class));
    }

    /** Number of plans stored for a tenant (used by tests). */
    public long planCount(String tenant) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM ev6_plans WHERE tenant=?", Long.class, tenant));
    }

    // ---- Plan requests ---------------------------------------------------------------------------

    /** Looks up an earlier plan request by idempotency key. */
    public Optional<RequestRecord> findRequest(String tenant, String key) {
        return jdbc.query("SELECT * FROM ev6_requests WHERE tenant=? AND key=?",
            (r, n) -> new RequestRecord(r.getString("key"), r.getString("hash"), r.getString("job")), tenant, key).stream().findFirst();
    }

    /** Records a plan request's key and payload hash. */
    public void insertRequest(String tenant, String key, String hash, String job) {
        jdbc.update("INSERT INTO ev6_requests VALUES(?,?,?,?)", tenant, key, hash, job);
    }

    // ---- Events -------------------------------------------------------------------------------------

    /** Maps one events row to a record. */
    private static EventRecord event(java.sql.ResultSet r) throws java.sql.SQLException {
        return new EventRecord(r.getString("id"), r.getLong("sequence"), r.getLong("at"), r.getString("hash"), r.getString("status"), r.getString("job"));
    }

    /** Looks up an earlier event by id. */
    public Optional<EventRecord> findEvent(String tenant, String id) {
        return jdbc.query("SELECT * FROM ev6_events WHERE tenant=? AND id=?", (r, n) -> event(r), tenant, id).stream().findFirst();
    }

    /** The newest event for one asset that was applied (accepted or turned into an incident). */
    public Optional<EventRecord> latestAppliedEvent(String tenant, String asset) {
        return jdbc.query("SELECT * FROM ev6_events WHERE tenant=? AND asset=? AND status IN ('ACCEPTED','INCIDENT') ORDER BY sequence DESC LIMIT 1",
            (r, n) -> event(r), tenant, asset).stream().findFirst();
    }

    /** Stores a live event and its outcome. */
    public void insertEvent(String tenant, String id, String asset, long sequence, long at, String hash, String status, String job, Object body, String error) {
        jdbc.update("INSERT INTO ev6_events VALUES(?,?,?,?,?,?,?,?,CAST(? AS JSONB),?)", tenant, id, asset, sequence, at, hash, status, job, json(body), error);
    }

    // ---- Job queue ----------------------------------------------------------------------------------

    /** Queued plus running jobs of a tenant. */
    public int activeJobs(String tenant) {
        return Objects.requireNonNull(
            jdbc.queryForObject("SELECT COUNT(*) FROM ev6_jobs WHERE tenant=? AND status IN ('QUEUED','RUNNING')", Integer.class, tenant));
    }

    /** Adds a queued job and returns its id. */
    public String insertJob(String tenant, long revision, String parent, JobKind kind, double budgetSeconds, long created) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ev6_jobs(id,tenant,revision,parent,kind,status,generation,lease_until,created,budget_s) VALUES(?,?,?,?,?,'QUEUED',0,0,?,?)",
            id, tenant, revision, parent, kind.value(), created, budgetSeconds);

        return id;
    }

    /** Maps one jobs row to a record. */
    private PlanningJob job(Map<String, Object> row) {
        return new PlanningJob((String) row.get("id"), (String) row.get("tenant"), ((Number) row.get("revision")).longValue(), (String) row.get("parent"),
            JobKind.fromValue((String) row.get("kind")), (String) row.get("status"), ((Number) row.get("generation")).longValue(),
            ((Number) row.get("lease_until")).longValue(), ((Number) row.get("created")).longValue(), ((Number) row.get("budget_s")).doubleValue(),
            (String) row.get("result"), (String) row.get("error"));
    }

    /**
     * Claims the oldest queued job (repairs first), or one whose lease expired, and fences it with a new generation so an older holder can no longer publish.
     */
    public PlanningJob claim(String id, long leaseMillis) {
        return transactions.execute(status -> {
            String sql = "SELECT * FROM ev6_jobs WHERE (status='QUEUED' OR (status='RUNNING' AND lease_until<?))" + (id == null ? "" : " AND id=?")
                + " ORDER BY CASE WHEN kind='repair' THEN 0 ELSE 1 END,created LIMIT 1 FOR UPDATE SKIP LOCKED";
            var rows = id == null ? jdbc.queryForList(sql, System.currentTimeMillis()) : jdbc.queryForList(sql, System.currentTimeMillis(), id);

            if (rows.isEmpty()) {
                return null;
            }

            // Take the job under a new generation, so an older holder can no longer publish it.
            var old = job(rows.get(0));
            long until = System.currentTimeMillis() + leaseMillis;
            jdbc.update("UPDATE ev6_jobs SET status='RUNNING',generation=?,lease_until=? WHERE id=?", old.generation() + 1, until, old.id());

            return new PlanningJob(old.id(), old.tenant(), old.revision(), old.parent(), old.kind(), "RUNNING", old.generation() + 1, until, old.created(),
                old.budgetS(), old.result(), old.error());
        });
    }

    /**
     * Atomically stores a validated plan and makes it active, only if the job still holds its lease and the tenant has not moved to a newer revision or parent
     * plan since the job was queued.
     */
    public String publish(PlanningJob job, Plan plan, Consumer<String> failpoint) {
        String id = transactions.execute(status -> {
            var state = lockState(job.tenant());
            var latest = job(jdbc.queryForMap("SELECT * FROM ev6_jobs WHERE id=? AND tenant=? FOR UPDATE", job.id(), job.tenant()));

            // Refuse if anything changed since the job was claimed: tenant input, active plan, lease or generation.
            if (state.status().equals("INCIDENT") || state.revision() != job.revision() || !Objects.equals(state.active(), job.parent())
                || latest.generation() != job.generation() || !latest.status().equals("RUNNING") || latest.leaseUntil() < System.currentTimeMillis()) {
                throw new DomainException("STALE_PUBLICATION", 409);
            }
            if (plan.validation() == null || !plan.validation().valid()) {
                throw new DomainException("UNVALIDATED_PUBLICATION", 500);
            }

            // Insert the plan, make it active and close the job, all in this one transaction.
            String planId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ev6_plans VALUES(?,?,?,?,CAST(? AS JSONB),?)", planId, job.tenant(), job.revision(), job.parent(), json(plan),
                System.currentTimeMillis());
            if (failpoint != null) {
                failpoint.accept("after_plan_insert");
            }
            jdbc.update("UPDATE ev6_state SET active=?,status='CURRENT' WHERE tenant=?", planId, job.tenant());
            jdbc.update("UPDATE ev6_jobs SET status='SUCCEEDED',result=? WHERE id=?", planId, job.id());

            return planId;
        });

        if (failpoint != null) {
            failpoint.accept("after_commit");
        }

        return id;
    }

    /** Marks a running job failed (or superseded) unless a newer claim already owns it. */
    public void fail(PlanningJob job, String code) {
        jdbc.update("UPDATE ev6_jobs SET status=?,error=? WHERE id=? AND generation=? AND status='RUNNING'",
            code.equals("STALE_PUBLICATION") ? "SUPERSEDED" : "FAILED", code, job.id(), job.generation());
    }

    /** One job by id, scoped to the tenant. */
    public PlanningJob getJob(String tenant, String id) {
        var rows = jdbc.queryForList("SELECT * FROM ev6_jobs WHERE tenant=? AND id=?", tenant, id);

        if (rows.isEmpty()) {
            throw new DomainException("JOB_NOT_FOUND", 404);
        }

        return job(rows.get(0));
    }

    /** A tenant's jobs, newest first. */
    public List<PlanningJob> jobs(String tenant) {
        return jdbc.queryForList("SELECT * FROM ev6_jobs WHERE tenant=? ORDER BY created DESC", tenant).stream().map(this::job).toList();
    }

    /** Queued plus running jobs across all tenants. */
    public int queueDepth() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM ev6_jobs WHERE status IN ('QUEUED','RUNNING')", Integer.class));
    }

    /** Age in seconds of the oldest unfinished job (queue lag). */
    public double oldestAge() {
        var oldest = jdbc.queryForObject("SELECT MIN(created) FROM ev6_jobs WHERE status IN ('QUEUED','RUNNING')", Long.class);

        return oldest == null ? 0 : Math.max(0, (System.currentTimeMillis() - oldest) / 1000.0);
    }

    /** Fails if the database does not answer. */
    public void health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }
}
