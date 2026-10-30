package com.moveinsync.evfleet.api;

import com.moveinsync.evfleet.persistence.PlanRepository;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness check and the demo page; no login needed. */
@RestController
public class HealthController {

    private final PlanRepository repository;

    /** Receives the repository used to ping the database. */
    HealthController(PlanRepository repository) {
        this.repository = repository;
    }

    /** GET /health: answers ok only if the database responds. */
    @GetMapping("/health")
    Object health() {
        repository.health();

        return Map.of("status", "ok");
    }

    /** GET /demo: serves the static timeline page. */
    @GetMapping("/demo")
    org.springframework.web.servlet.ModelAndView demo() {
        return new org.springframework.web.servlet.ModelAndView("forward:/index.html");
    }
}
