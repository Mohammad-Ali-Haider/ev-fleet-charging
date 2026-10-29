package com.moveinsync.evfleet.domain;

/** A road that cannot be driven, from one location to another. */
public record RouteBlock(String origin, String destination) {
}
