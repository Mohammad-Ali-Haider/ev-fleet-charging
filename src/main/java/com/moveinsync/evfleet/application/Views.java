package com.moveinsync.evfleet.application;

import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.Plan;
import com.moveinsync.evfleet.persistence.model.StoredPlan;
import java.util.List;

/** Response shapes returned by the services; the controller serialises them unchanged. */
public final class Views {

    /** No instances; only holds the response records. */
    private Views() {
    }

    /** A stored plan with the headline numbers an operator needs. */
    public record PlanView(String id, long inputRevision, long currentRevision, String status, boolean dispatchable, Plan plan, int backupCount,
        int totalBackupCount, int totalTripCount, long mutableCostMicros, long timelineCostMicros, int backupGap) {

        /** Builds the view from a stored plan. */
        public static PlanView of(StoredPlan stored) {
            var plan = stored.plan();

            return new PlanView(stored.id(), stored.inputRevision(), stored.currentRevision(), stored.status(), stored.dispatchable(), plan, plan.backupCount(),
                plan.totalBackupCount(), plan.allAssignments().size(), plan.mutableCostMicros(), plan.timelineCostMicros(),
                plan.backupCount() - plan.lowerBound());
        }
    }

    /** Per-cab trips, empty drives, waits and charging sessions with the charge level before and after each. */
    public record TimelineView(boolean dispatchable, List<Action> actions, Long asOf, int retainedActionCount) {
    }

    /** An employee's own upcoming trips; no other employee's data is included. */
    public record EmployeeTrips(boolean dispatchable, List<TripSummary> trips) {

        /** One upcoming trip of an employee and its assigned cab. */
        public record TripSummary(String tripId, long start, String vehicle) {
        }
    }

    /** Result of ingesting a live event: ACCEPTED (with a repair job), IGNORED_OLD or INCIDENT. */
    public record DisruptionOutcome(String status, String jobId) {
    }
}
