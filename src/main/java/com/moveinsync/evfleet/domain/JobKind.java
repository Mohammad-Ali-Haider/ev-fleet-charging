package com.moveinsync.evfleet.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** Planning job types; repairs are claimed before initial plans because they carry a 2-second target. */
public enum JobKind {

    INITIAL("initial", 30_000), REPAIR("repair", 2_000);

    private final String value;

    private final long targetMillis;

    /** Stores the JSON spelling and latency target. */
    JobKind(String value, long targetMillis) {
        this.value = value;
        this.targetMillis = targetMillis;
    }

    /** The JSON and database spelling, for example "repair". */
    @JsonValue
    public String value() {
        return value;
    }

    /** End-to-end latency target from request receipt to published plan. */
    public long targetMillis() {
        return targetMillis;
    }

    /** Reads the database spelling back into the enum. */
    public static JobKind fromValue(String value) {
        for (var kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }

        throw new IllegalArgumentException("unknown job kind");
    }
}
