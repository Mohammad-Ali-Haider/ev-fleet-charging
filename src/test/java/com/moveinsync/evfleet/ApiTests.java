package com.moveinsync.evfleet;

import static org.junit.jupiter.api.Assertions.*;

import com.moveinsync.evfleet.application.DisruptionService;
import com.moveinsync.evfleet.application.PlanQueryService;
import com.moveinsync.evfleet.application.PlanRequestService;
import com.moveinsync.evfleet.application.PlanningService;
import com.moveinsync.evfleet.domain.Charger;
import com.moveinsync.evfleet.domain.Disruption;
import com.moveinsync.evfleet.domain.DisruptionKind;
import com.moveinsync.evfleet.domain.Fixtures;
import com.moveinsync.evfleet.domain.Trip;
import com.moveinsync.evfleet.domain.Vehicle;
import com.moveinsync.evfleet.persistence.PlanRepository;
import com.moveinsync.evfleet.security.TokenIssuer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Full HTTP tests: the real app on a random port, with the background worker off so tests run jobs themselves. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"ev.worker.enabled=false"})
class ApiTests {

    @Autowired
    TestRestTemplate http;

    @Autowired
    PlanRepository repo;

    @Autowired
    PlanningService service;

    @Autowired
    PlanRequestService requests;

    @Autowired
    DisruptionService disruptions;

    @Autowired
    PlanQueryService queries;

    /** A signed token for a tenant, role and user, valid for an hour. */
    String token(String tenant, String role, String subject) {
        return TokenIssuer.issue(System.getenv("EV_JWT_SECRET"), tenant, role, subject, 3600);
    }

    /** Sends a JSON request, with the token if given. */
    ResponseEntity<Map> call(HttpMethod method, String path, Object body, String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "api");
        if (token != null) {
            headers.setBearerAuth(token);
        }

        return http.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    /** HTTP status of a request. */
    int status(HttpMethod method, String path, Object body, String token) {
        return call(method, path, body, token).getStatusCode().value();
    }

    /** JSON body of a request. */
    Map json(HttpMethod method, String path, Object body, String token) {
        return call(method, path, body, token).getBody();
    }

    /** Login, roles, tenant isolation, safe errors and the employee view, all through real HTTP. */
    @Test
    void authenticationRolesSafeErrorsAndIsolation() {
        for (String asset : List.of("/index.html", "/timeline.css", "/timeline.js")) {
            assertEquals(200, http.getForEntity(asset, String.class).getStatusCode().value(), asset);
        }

        String tenant = "api-" + UUID.randomUUID();
        String admin = token(tenant, "admin", "admin");
        String employee = token(tenant, "employee", "demo-employee");
        String otherTenantAdmin = token("other", "admin", "admin");

        assertEquals(401, status(HttpMethod.GET, "/api/v1/plans/current", null, null));
        assertEquals(403, status(HttpMethod.POST, "/api/v1/datasets", Fixtures.demo(), employee));

        var bad = call(HttpMethod.POST, "/api/v1/datasets", Map.of("phone", "private-phone"), admin);

        assertEquals(422, bad.getStatusCode().value());
        assertFalse(bad.toString().contains("private-phone"), "error responses must not echo personal data");

        assertEquals(201, status(HttpMethod.POST, "/api/v1/datasets", Fixtures.demo(), admin));

        var accepted = call(HttpMethod.POST, "/api/v1/plans", null, admin);

        assertEquals(202, accepted.getStatusCode().value());

        String planId = service.execute(repo.claim((String) accepted.getBody().get("jobId"), 60000));

        assertEquals(403, status(HttpMethod.GET, "/api/v1/plans/current", null, employee));
        assertEquals(404, status(HttpMethod.GET, "/api/v1/plans/" + planId, null, otherTenantAdmin));
        assertEquals(3, ((List) json(HttpMethod.GET, "/api/v1/me/trips", null, employee).get("trips")).size());
        assertEquals(true, json(HttpMethod.POST, "/api/v1/plans/" + planId + "/validate", null, admin).get("valid"));

        String expired = TokenIssuer.issue(System.getenv("EV_JWT_SECRET"), tenant, "admin", "admin", -100);

        assertEquals(401, status(HttpMethod.GET, "/api/v1/plans/current", null, expired));
    }

    /** After a live repair, retained trips stay visible to the employee and their charging cost stays in the day's total. */
    @Test
    void retainedFutureEmployeeVisibilityAndCostAccounting() {
        String tenant = "retained-" + UUID.randomUUID();
        String admin = token(tenant, "admin", "admin");
        String employee = token(tenant, "employee", "demo-employee");

        var cabs = new ArrayList<Vehicle>();
        var plugs = new ArrayList<Charger>();
        for (int i = 0; i < 6; i++) {
            cabs.add(CoreTests.cab("v%03d".formatted(i), 16000 + i * 100, "a"));
            plugs.add(CoreTests.plug("c" + i, "a"));
        }

        // 30 short trips in five waves of six, all booked by the same employee.
        var trips = new ArrayList<Trip>();
        for (int i = 0; i < 30; i++) {
            long start = 1000 + (i / 6) * 1500;
            trips.add(new Trip("t" + i, "a", "a", start, 100, 10000, start + 100, "demo-employee"));
        }

        var dataset = CoreTests.with(CoreTests.fixture(16000, true), cabs, trips, plugs);

        requests.importDataset(tenant, dataset);
        service.execute(repo.claim(requests.requestPlan(tenant, "initial", 4), 60000));

        var drift = new Disruption("drift", 1, 0, DisruptionKind.SOC, "v000", 9000L, "a", 60, Map.of());
        service.execute(repo.claim(disruptions.ingest(tenant, drift).jobId(), 60000));

        var current = json(HttpMethod.GET, "/api/v1/plans/current", null, admin);
        var stored = queries.plan(tenant, null);

        assertEquals(30, ((Number) current.get("totalTripCount")).intValue());
        assertEquals(30, ((List) json(HttpMethod.GET, "/api/v1/me/trips", null, employee).get("trips")).size());
        assertFalse(stored.plan().historyAssignments().isEmpty());
        assertEquals(stored.plan().timelineCostMicros(), ((Number) current.get("timelineCostMicros")).longValue());
        assertTrue(stored.plan().timelineCostMicros() > stored.plan().mutableCostMicros());
        assertEquals(422, status(HttpMethod.POST, "/api/v1/datasets", repo.dataset(tenant, stored.inputRevision()), admin), "clients cannot import history");
    }
}
