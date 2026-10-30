package com.moveinsync.evfleet.planner;

/** Road distance and time between two named locations; a live map provider can replace the offline one. */
public interface TravelProvider {

    /** Distance in metres and drive time in seconds. */
    record Route(long metres, long seconds) {

        static final Route STATIONARY = new Route(0, 0);
    }

    /** Throws {@link com.moveinsync.evfleet.domain.DomainException.RouteBlocked} when the route is blocked. */
    Route route(String origin, String destination);

    boolean isBlocked(String origin, String destination);
}
