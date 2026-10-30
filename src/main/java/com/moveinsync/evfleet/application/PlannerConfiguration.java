package com.moveinsync.evfleet.application;

import com.moveinsync.evfleet.planner.Planner;
import com.moveinsync.evfleet.planner.RepairPreparer;
import com.moveinsync.evfleet.validator.IndependentValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the plain planning classes, which know nothing about Spring, into the application. */
@Configuration
public class PlannerConfiguration {

    /** The planner used by the app: greedy planner wrapped by the validator. */
    @Bean
    Planner planner() {
        return Planner.standard();
    }

    /** Builds repair inputs for live events. */
    @Bean
    RepairPreparer repairPreparer() {
        return new RepairPreparer();
    }

    /** The independent plan checker. */
    @Bean
    IndependentValidator independentValidator() {
        return new IndependentValidator();
    }
}
