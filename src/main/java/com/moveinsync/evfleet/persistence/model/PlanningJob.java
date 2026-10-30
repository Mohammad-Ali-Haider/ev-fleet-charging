package com.moveinsync.evfleet.persistence.model;

import com.moveinsync.evfleet.domain.JobKind;

/** One planning job row: what to plan, its status and its lease. */
public record PlanningJob(String id, String tenant, long revision, String parent, JobKind kind, String status, long generation, long leaseUntil, long created,
    double budgetS, String result, String error) {
}
