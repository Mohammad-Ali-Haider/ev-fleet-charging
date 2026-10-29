package com.moveinsync.evfleet.domain;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Complete immutable planning input, including any retained work from an earlier plan.
 */
public record Dataset(long start, long end, List<Location> locations, List<Vehicle> vehicles, List<Trip> trips, List<Charger> chargers, List<Tariff> tariffs,
    int trafficBp, int speedMps, int efficiencyBp, int slowBp, String modelVersion, List<RouteBlock> blockedRoutes, List<Action> history,
    Map<String, String> historyAssignments, PrefixEvidence prefixEvidence, Long asOf) {

    public Dataset {
        locations = List.copyOf(locations);
        vehicles = List.copyOf(vehicles);
        trips = List.copyOf(trips);
        chargers = List.copyOf(chargers);
        tariffs = List.copyOf(tariffs);
        blockedRoutes = List.copyOf(blockedRoutes == null ? List.of() : blockedRoutes);
        history = List.copyOf(history == null ? List.of() : history);
        historyAssignments = copyKeepingNulls(historyAssignments == null ? Map.of() : historyAssignments);
    }

    /** Copy of this dataset with a new schedule state (used by live re-planning). */
    public Dataset withScheduleState(List<Vehicle> vehicles, List<Trip> trips, List<Charger> chargers, List<Action> history,
        Map<String, String> historyAssignments, PrefixEvidence prefixEvidence, Long asOf) {
        return new Dataset(start, end, locations, vehicles, trips, chargers, tariffs, trafficBp, speedMps, efficiencyBp, slowBp, modelVersion, blockedRoutes,
            history, historyAssignments, prefixEvidence, asOf);
    }

    /** Map.copyOf rejects nulls, and a null value marks a backup trip, so assignment maps are copied this way. */
    private static <K, V> Map<K, V> copyKeepingNulls(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
