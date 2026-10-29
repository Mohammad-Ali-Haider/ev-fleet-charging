package com.moveinsync.evfleet.domain;

/**
 * Immutable vehicle specification and the state from which planning may resume.
 */
public record Vehicle(String id, long capacityWh, long energyWh, int whPerKm, String location, long availableAt, int maxPowerW, String connector, int acBp) {

    /** Copy of this cab with a new free time, location and charge. */
    public Vehicle withState(long availableAt, String location, long energyWh) {
        return new Vehicle(id, capacityWh, energyWh, whPerKm, location, availableAt, maxPowerW, connector, acBp);
    }
}
