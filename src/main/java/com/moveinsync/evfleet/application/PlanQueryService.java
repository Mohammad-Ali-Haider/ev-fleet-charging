package com.moveinsync.evfleet.application;

import com.moveinsync.evfleet.application.Views.EmployeeTrips;
import com.moveinsync.evfleet.application.Views.PlanView;
import com.moveinsync.evfleet.application.Views.TimelineView;
import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.Reason;
import com.moveinsync.evfleet.domain.Report;
import com.moveinsync.evfleet.domain.Trip;
import com.moveinsync.evfleet.persistence.PlanRepository;
import com.moveinsync.evfleet.persistence.model.PlanningJob;
import com.moveinsync.evfleet.persistence.model.StoredPlan;
import com.moveinsync.evfleet.validator.IndependentValidator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Read side: plans, timelines, backup reasons, validation reports and an employee's own trips. */
@Service
public class PlanQueryService {

    private final PlanRepository repository;

    private final PlanCache cache;

    private final IndependentValidator validator;

    /** Receives its collaborators from Spring. */
    public PlanQueryService(PlanRepository repository, PlanCache cache, IndependentValidator validator) {
        this.repository = repository;
        this.cache = cache;
        this.validator = validator;
    }

    /**
     * A plan by id, or the active plan when {@code planId} is null. The active pointer and status are read from the database on every call; only the immutable
     * plan body comes from the cache.
     */
    public StoredPlan plan(String tenant, String planId) {
        var state = repository.state(tenant);
        String id = planId == null ? state.active() : planId;

        if (id == null) {
            throw new DomainException("PLAN_NOT_FOUND", 404);
        }

        var row = cache.plan(tenant, id);
        boolean dispatchable = "CURRENT".equals(state.status()) && id.equals(state.active()) && row.revision() == state.revision();

        return new StoredPlan(id, row.revision(), state.revision(), state.status(), dispatchable, row.plan());
    }

    /** A plan plus headline numbers for the API. */
    public PlanView summary(String tenant, String planId) {
        return PlanView.of(plan(tenant, planId));
    }

    /** A planning job, scoped to the tenant. */
    public PlanningJob job(String tenant, String jobId) {
        return repository.getJob(tenant, jobId);
    }

    /** Actions of one cab (or all cabs), retained history first, sorted by time. */
    public TimelineView timeline(String tenant, String planId, String vehicleId) {
        var stored = plan(tenant, planId);
        var plan = stored.plan();
        var actions = new ArrayList<>(plan.history());
        actions.addAll(plan.actions());

        var selected = actions.stream().filter(action -> vehicleId == null || action.vehicleId().equals(vehicleId)).sorted(
            Comparator.comparing(Action::vehicleId).thenComparingLong(Action::start)).toList();

        return new TimelineView(stored.dispatchable(), selected, plan.asOf(), plan.history().size());
    }

    /** Backup reasons of a plan. */
    public Map<String, Reason> backups(String tenant, String planId) {
        return plan(tenant, planId).plan().backups();
    }

    /** Re-runs the independent validator on a stored plan against the exact input it was built from. */
    public Report validate(String tenant, String planId) {
        var stored = plan(tenant, planId);

        return validator.validate(cache.dataset(tenant, stored.inputRevision()), stored.plan());
    }

    /** Upcoming trips (including retained ones) whose employee subject is the caller's. */
    public EmployeeTrips employeeTrips(String tenant, String subject) {
        var stored = plan(tenant, null);
        var dataset = cache.dataset(tenant, stored.inputRevision());
        var trips = new LinkedHashMap<String, Trip>();

        for (var trip : dataset.trips()) {
            trips.put(trip.id(), trip);
        }
        if (dataset.prefixEvidence() != null) {
            for (var trip : dataset.prefixEvidence().trips()) {
                trips.put(trip.id(), trip);
            }
        }

        var assignments = stored.plan().allAssignments();
        long now = dataset.asOf() == null ? dataset.start() : dataset.asOf();
        var result = new ArrayList<EmployeeTrips.TripSummary>();

        for (var trip : trips.values()) {
            if (trip.employeeSubject().equals(subject) && trip.start() >= now) {
                result.add(new EmployeeTrips.TripSummary(trip.id(), trip.start(), assignments.get(trip.id())));
            }
        }

        return new EmployeeTrips(stored.dispatchable(), result);
    }
}
