package com.moveinsync.evfleet.persistence.model;

import com.moveinsync.evfleet.domain.Plan;

/** An immutable published plan and the dataset revision it was computed from. */
public record PlanRow(String id, long revision, Plan plan) {
}
