package com.moveinsync.evfleet.domain;

/** A named point (pickup, drop-off or hub) in metres on a flat map. */
public record Location(String id, long xM, long yM) {
}
