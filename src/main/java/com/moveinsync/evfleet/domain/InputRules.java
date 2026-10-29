package com.moveinsync.evfleet.domain;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

/** Input checks run before any planning, by the services and by the planner itself. */
public final class InputRules {

    /** No instances; only static checks. */
    private InputRules() {
    }

    /** Throws INVALID_DATASET when a rule fails. */
    private static void require(boolean ok) {
        if (!ok) {
            throw new DomainException("INVALID_DATASET");
        }
    }

    /** Rejects missing, over-long or duplicate ids. */
    private static <T> void unique(List<T> rows, Function<T, String> id) {
        var seen = new HashSet<String>();

        for (T t : rows) {
            String s = id.apply(t);
            require(s != null && !s.isBlank() && s.length() <= 100 && seen.add(s));
        }
    }

    /** Checks a whole dataset before planning. */
    public static void validate(Dataset dataset) {
        require(dataset.start() >= 0 && dataset.end() > dataset.start() && dataset.end() - dataset.start() <= 7 * 86400);
        require(dataset.vehicles().size() <= 2000 && dataset.trips().size() <= 10000 && dataset.chargers().size() <= 500 && dataset.locations().size() <= 5000);
        require(dataset.trafficBp() >= 10000 && dataset.trafficBp() <= 30000 && dataset.speedMps() > 0 && dataset.speedMps() <= 100);
        require(dataset.efficiencyBp() > 0 && dataset.efficiencyBp() <= 10000 && dataset.slowBp() > 0 && dataset.slowBp() <= 10000);
        require("offline-euclidean-v1".equals(dataset.modelVersion()));
        unique(dataset.vehicles(), Vehicle::id);
        unique(dataset.trips(), Trip::id);
        unique(dataset.locations(), Location::id);
        unique(dataset.chargers(), Charger::id);

        var locations = new HashSet<String>();

        for (var location : dataset.locations()) {
            locations.add(location.id());
            require(Math.abs(location.xM()) <= 100000 && Math.abs(location.yM()) <= 100000);
        }

        for (var vehicle : dataset.vehicles()) {
            require(vehicle.capacityWh() > 0 && vehicle.capacityWh() <= 1000000 && vehicle.energyWh() >= 0 && vehicle.energyWh() <= vehicle.capacityWh()
                && vehicle.whPerKm() > 0 && vehicle.whPerKm() <= 5000 && vehicle.maxPowerW() > 0 && vehicle.maxPowerW() <= 1000000 && vehicle.acBp() >= 10000
                && vehicle.acBp() <= 30000 && locations.contains(vehicle.location()) && vehicle.availableAt() >= dataset.start()
                && vehicle.availableAt() <= dataset.end() && vehicle.connector() != null);
        }

        for (var t : dataset.trips()) {
            require(locations.contains(t.origin()) && locations.contains(t.destination()) && t.start() >= dataset.start() && t.start() < dataset.end()
                && t.durationS() > 0 && t.durationS() <= 86400 && t.distanceM() >= 0 && t.distanceM() <= 1000000 && t.employeeSubject() != null);
        }

        for (var charger : dataset.chargers()) {
            require(locations.contains(charger.location()) && charger.powerW() > 0 && charger.powerW() <= 1000000 && charger.connector() != null);

            for (var outage : charger.outages()) {
                require(outage.end() > outage.start());
            }
        }

        var tariffs = dataset.tariffs().stream().sorted(Comparator.comparingLong(Tariff::start)).toList();
        long cursor = dataset.start();

        for (var t : tariffs) {
            require(t.start() == cursor && t.end() > t.start() && t.microsPerKwh() >= 0 && t.microsPerKwh() <= 1000000000);
            cursor = t.end();
        }
        require(cursor == dataset.end() && tariffs.size() <= 100);

        for (var t : dataset.trips()) {
            require(!dataset.historyAssignments().containsKey(t.id()));
        }
    }

    /** Checks a live event before re-planning. */
    public static void event(Disruption event, Dataset dataset) {
        if (event.eventId() == null || event.eventId().isBlank() || event.eventId().length() > 100 || event.sequence() < 1 || event.at() < dataset.start()
            || event.at() >= dataset.end() || event.kind() == null || event.assetId() == null || event.freezeS() < 0 || event.freezeS() > 600) {
            throw new DomainException("INVALID_DISRUPTION");
        }
    }
}
