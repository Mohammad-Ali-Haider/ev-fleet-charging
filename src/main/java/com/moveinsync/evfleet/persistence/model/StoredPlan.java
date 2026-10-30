package com.moveinsync.evfleet.persistence.model;

import com.moveinsync.evfleet.domain.Plan;

/** A plan read back from storage, with whether it is safe to dispatch now. */
public record StoredPlan(String id, long inputRevision, long currentRevision, String status, boolean dispatchable, Plan plan) {
}
