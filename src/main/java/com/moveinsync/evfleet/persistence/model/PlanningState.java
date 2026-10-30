package com.moveinsync.evfleet.persistence.model;

/** A tenant's latest input revision, active plan and status. */
public record PlanningState(String tenant, long revision, String active, String status) {
}
