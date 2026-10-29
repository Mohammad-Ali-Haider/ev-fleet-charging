package com.moveinsync.evfleet.domain;

import java.util.List;

/** Work kept from the previous plan during a live re-plan, so the validator can re-check it. */
public record PrefixEvidence(List<Vehicle> vehicles, List<Action> actions, List<Trip> trips, List<Charger> chargers) {

    public PrefixEvidence {
        vehicles = List.copyOf(vehicles);
        actions = List.copyOf(actions);
        trips = List.copyOf(trips);
        chargers = List.copyOf(chargers);
    }
}
