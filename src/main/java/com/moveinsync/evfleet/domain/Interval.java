package com.moveinsync.evfleet.domain;

/** A time window [start, end) in seconds. */
public record Interval(long start, long end) {
}
