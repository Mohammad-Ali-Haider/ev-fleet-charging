package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.Location;
import com.moveinsync.evfleet.domain.RouteBlock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Euclidean travel for one immutable dataset snapshot.
 *
 * <p>
 * Routes are memoised for the lifetime of this object, which is one planning run. The memo can never be stale because the snapshot (locations, traffic, speed,
 * blocked routes) cannot change underneath it, and it is never shared across tenants or revisions.
 */
public final class OfflineTravel implements TravelProvider {

    /** Memo key: from and to locations. */
    private record Key(String origin, String destination) {
    }

    private final Dataset dataset;

    private final Map<String, Location> locations;

    private final Set<RouteBlock> blocked;

    private final Map<Key, Route> memo = new HashMap<>();

    /** Indexes locations and blocked roads for this dataset. */
    public OfflineTravel(Dataset dataset) {
        this.dataset = dataset;
        locations = dataset.locations().stream().collect(Collectors.toMap(Location::id, x -> x));
        blocked = Set.copyOf(dataset.blockedRoutes());
    }

    /** Straight-line distance and drive time between two locations. */
    @Override
    public Route route(String origin, String destination) {
        if (!blocked.isEmpty() && blocked.contains(new RouteBlock(origin, destination))) {
            throw new DomainException.RouteBlocked();
        }
        if (origin.equals(destination)) {
            return Route.STATIONARY;
        }

        return memo.computeIfAbsent(new Key(origin, destination), key -> {
            var a = locations.get(origin);
            var b = locations.get(destination);
            long metres = (long) Math.ceil(Math.hypot(a.xM() - b.xM(), a.yM() - b.yM()));

            return new Route(metres, (metres * dataset.trafficBp() + dataset.speedMps() * 10000L - 1) / (dataset.speedMps() * 10000L));
        });
    }

    /** True if the road between the two locations is blocked. */
    @Override
    public boolean isBlocked(String origin, String destination) {
        return !blocked.isEmpty() && blocked.contains(new RouteBlock(origin, destination));
    }

    /** Number of memoised routes (used by tests). */
    public long cacheSize() {
        return memo.size();
    }
}
