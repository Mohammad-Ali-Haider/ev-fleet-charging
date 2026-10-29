package com.moveinsync.evfleet.domain;

import java.util.ArrayList;
import java.util.List;

/** One charging plug: where it is, its power, connector and known outages. */
public record Charger(String id, String hubId, String location, int powerW, String connector, List<Interval> outages) {

    public Charger {
        outages = List.copyOf(outages == null ? List.of() : outages);
    }

    /** Copy of this charger with one more outage window. */
    public Charger outage(Interval i) {
        var all = new ArrayList<>(outages);
        all.add(i);

        return new Charger(id, hubId, location, powerW, connector, all);
    }
}
