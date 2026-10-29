package com.moveinsync.evfleet.domain;

/**
 * One timed vehicle action, with battery and electricity measurements in explicit units.
 */
public record Action(ActionType kind, String vehicleId, long start, long end, String origin, String destination, long beforeWh, long afterWh, String tripId,
    String chargerId, long gridWh, long costMicros) {

    /** Copy of this action with new end time, charge levels and cost. */
    public Action withMeasurements(long end, long beforeWh, long afterWh, long gridWh, long costMicros) {
        return new Action(kind, vehicleId, start, end, origin, destination, beforeWh, afterWh, tripId, chargerId, gridWh, costMicros);
    }
}
