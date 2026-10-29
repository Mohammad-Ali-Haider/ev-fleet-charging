package com.moveinsync.evfleet.domain;

import java.util.List;

/** Result of the independent validator: valid or not, and which rules failed. */
public record Report(boolean valid, List<String> violations, int actionsChecked, Double minimumSoc, long costMicros, String scope) {

    public Report {
        violations = List.copyOf(violations);
    }
}
