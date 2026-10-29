package com.moveinsync.evfleet.domain;

/** One passenger trip: where, when, how far, and the latest allowed finish. */
public record Trip(String id, String origin, String destination, long start, int durationS, long distanceM, long latestFinish, String employeeSubject) {
}
