package com.moveinsync.evfleet.domain;

/** Electricity price for one time window of the day. */
public record Tariff(long start, long end, long microsPerKwh) {
}
