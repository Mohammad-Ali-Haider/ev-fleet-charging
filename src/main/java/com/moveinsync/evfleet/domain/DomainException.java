package com.moveinsync.evfleet.domain;

/** Only allowlisted non-personal codes cross the API/log boundary. */
public class DomainException extends RuntimeException {

    private final int status;

    /** A business error with HTTP status 422. */
    public DomainException(String code) {
        this(code, 422);
    }

    /** A business error with a chosen HTTP status. */
    public DomainException(String code, int status) {
        super(code);
        this.status = status;
    }

    /** HTTP status to return. */
    public int status() {
        return status;
    }

    /** A reading or commitment that makes the current plan unsafe; the tenant's plan stops being dispatchable until a re-plan. */
    public static final class SafetyIncident extends DomainException {

        /** An incident with HTTP status 409; the code always starts with SAFETY_INCIDENT_. */
        public SafetyIncident(String reason) {
            super("SAFETY_INCIDENT_" + reason, 409);
        }
    }

    /** A road between two locations is closed, so the planner must route around it or leave the trip as a backup. */
    public static final class RouteBlocked extends DomainException {

        /** A blocked route with HTTP status 422. */
        public RouteBlocked() {
            super("TRAVEL_UNAVAILABLE");
        }
    }
}
