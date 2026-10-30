package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.Plan;
import com.moveinsync.evfleet.validator.IndependentValidator;

/**
 * Decorator that replays every plan through the independent validator and attaches the report. An invalid plan is never returned.
 */
public final class ValidatingPlanner implements Planner {

    private final Planner delegate;

    private final IndependentValidator validator = new IndependentValidator();

    /** Wraps another planner. */
    public ValidatingPlanner(Planner delegate) {
        this.delegate = delegate;
    }

    /** Plans with the wrapped planner, then validates; throws if invalid. */
    @Override
    public Plan solve(Dataset dataset, double budgetSeconds, Plan previous) {
        long started = System.nanoTime();
        var plan = delegate.solve(dataset, budgetSeconds, previous);
        var report = validator.validate(dataset, plan);

        if (!report.valid()) {
            throw new DomainException("INTERNAL_VALIDATION_FAILED", 500);
        }

        return plan.withValidation(report, (System.nanoTime() - started) / 1e9);
    }
}
