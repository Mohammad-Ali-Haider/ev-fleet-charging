package com.moveinsync.evfleet.domain;

import java.util.Map;

/**
 * A live event. {@code energyWh} and {@code location} apply to {@link DisruptionKind#SOC}; {@code observedEnergy} carries confirmed charge levels for cabs
 * interrupted by a {@link DisruptionKind#CHARGER_FAILURE}.
 */
public record Disruption(String eventId, long sequence, long at, DisruptionKind kind, String assetId, Long energyWh, String location, Integer freezeS,
    Map<String, Long> observedEnergy) {

    public Disruption {
        freezeS = freezeS == null ? 60 : freezeS;
        observedEnergy = Map.copyOf(observedEnergy == null ? Map.of() : observedEnergy);
    }
}
