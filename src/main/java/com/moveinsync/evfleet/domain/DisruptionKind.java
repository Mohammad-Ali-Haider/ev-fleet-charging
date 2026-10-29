package com.moveinsync.evfleet.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Live events that can invalidate part of the plan. JSON values match the public API. */
public enum DisruptionKind {

    /** A cab reports its real charge and location, for example after traffic drained it faster than planned. */
    SOC("soc") {

        /** A battery report affects only the reporting cab. */
        @Override
        public Set<String> directlyImpacted(Disruption event, List<Action> plannedActions) {
            return Set.of(event.assetId());
        }
    },

    /** A charger stops working from {@code at} until the end of the day. */
    CHARGER_FAILURE("charger_failure") {

        /** A charger failure affects every cab booked on it after the failure. */
        @Override
        public Set<String> directlyImpacted(Disruption event, List<Action> plannedActions) {
            var impacted = new LinkedHashSet<String>();

            for (var action : plannedActions) {
                if (event.assetId().equals(action.chargerId()) && action.end() > event.at()) {
                    impacted.add(action.vehicleId());
                }
            }

            return impacted;
        }
    };

    private final String value;

    /** Stores the JSON spelling of the kind. */
    DisruptionKind(String value) {
        this.value = value;
    }

    /** Cabs whose remaining plan this event breaks. */
    public abstract Set<String> directlyImpacted(Disruption event, List<Action> plannedActions);

    /** The JSON spelling, for example "soc". */
    @JsonValue
    public String value() {
        return value;
    }

    /** Reads the JSON spelling back into the enum. */
    @JsonCreator
    public static DisruptionKind fromValue(String value) {
        for (var kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }

        throw new IllegalArgumentException("unknown disruption kind");
    }
}
