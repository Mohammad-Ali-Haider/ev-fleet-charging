package com.moveinsync.evfleet.domain;

import java.util.Map;

/** Why a trip went to a backup cab: a code, counts as evidence, and a sentence. */
public record Reason(String code, Map<String, Integer> evidence, String explanation) {

    public Reason {
        evidence = Map.copyOf(evidence);
    }
}
