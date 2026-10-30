package com.moveinsync.evfleet.api;

import com.moveinsync.evfleet.application.PlanQueryService;
import com.moveinsync.evfleet.application.PlanRequestService;
import com.moveinsync.evfleet.application.Views.EmployeeTrips;
import com.moveinsync.evfleet.application.Views.PlanView;
import com.moveinsync.evfleet.application.Views.TimelineView;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Reason;
import com.moveinsync.evfleet.domain.Report;
import com.moveinsync.evfleet.persistence.model.PlanningJob;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter only: reads the tenant from the verified token and delegates to the services. */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('admin','operator')")
public class FleetController {

    /** Response to a dataset import: the new input revision number. */
    public record DatasetImported(long inputRevision) {
    }

    /** Response to a plan request: the job id and where to poll it. */
    public record JobAccepted(String jobId, String url) {
    }

    private final PlanRequestService requests;

    private final PlanQueryService queries;

    /** Receives the two services it delegates to. */
    public FleetController(PlanRequestService requests, PlanQueryService queries) {
        this.requests = requests;
        this.queries = queries;
    }

    /** Reads the tenant (client company) from the signed token. */
    private static String tenant(Jwt jwt) {
        return jwt.getClaimAsString("tenant");
    }

    /** POST /datasets: import a shift (admin only). */
    @PostMapping("/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('admin')")
    public DatasetImported dataset(@AuthenticationPrincipal Jwt jwt, @RequestBody Dataset dataset) {
        return new DatasetImported(requests.importDataset(tenant(jwt), dataset));
    }

    /** POST /plans: queue a planning job; the same Idempotency-Key returns the same job. */
    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobAccepted plan(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, @RequestParam(defaultValue = "10") double budgetS) {
        String id = requests.requestPlan(tenant(jwt), key, budgetS);

        return new JobAccepted(id, "/api/v1/jobs/" + id);
    }

    /** GET /jobs/{id}: status of a planning job. */
    @GetMapping("/jobs/{id}")
    public PlanningJob job(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return queries.job(tenant(jwt), id);
    }

    /** GET /plans/current: the active plan with headline numbers. */
    @GetMapping("/plans/current")
    public PlanView current(@AuthenticationPrincipal Jwt jwt) {
        return queries.summary(tenant(jwt), null);
    }

    /** GET /plans/{id}: one plan by id. */
    @GetMapping("/plans/{id}")
    public PlanView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return queries.summary(tenant(jwt), id);
    }

    /** GET /plans/{id}/timelines: per-cab actions with charge before and after each. */
    @GetMapping("/plans/{id}/timelines")
    public TimelineView timeline(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestParam(required = false) String vehicleId) {
        return queries.timeline(tenant(jwt), id, vehicleId);
    }

    /** GET /plans/{id}/backups: why each backup trip could not be served. */
    @GetMapping("/plans/{id}/backups")
    public Map<String, Reason> backups(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return queries.backups(tenant(jwt), id);
    }

    /** POST /plans/{id}/validate: re-run the independent validator on a plan. */
    @PostMapping("/plans/{id}/validate")
    public Report validate(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return queries.validate(tenant(jwt), id);
    }

    /** GET /me/trips: the signed-in employee's own upcoming trips. */
    @GetMapping("/me/trips")
    @PreAuthorize("isAuthenticated()")
    public EmployeeTrips employee(@AuthenticationPrincipal Jwt jwt) {
        return queries.employeeTrips(tenant(jwt), jwt.getSubject());
    }
}
