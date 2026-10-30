package com.moveinsync.evfleet.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.persistence.PlanRepository;
import com.moveinsync.evfleet.persistence.model.PlanRow;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Read-through cache of decoded plans and datasets, the hot data behind every plan read, timeline, employee lookup and live re-plan.
 *
 * <p>
 * Staleness: none. Plan and dataset rows are never updated after insert; a new revision or plan gets a new key. Which plan is active, and whether it is
 * dispatchable, is always read from the database and never cached. Keys include the tenant, and misses query with the tenant filter, so one tenant can never be
 * served another tenant's entry.
 *
 * <p>
 * Eviction: weighted LRU (Caffeine's W-TinyLFU) bounded by roughly the number of trips and actions held, plus expiry 30 minutes after last access so memory
 * returns after a shift.
 */
@Component
public class PlanCache {

    /** Cache key: tenant plus plan id or revision. */
    private record Key(String tenant, String id) {
    }

    // About 150 bytes per weighted element, so this bounds decoded entries to roughly 75 MB.
    private static final long MAX_WEIGHT = 500_000;

    private final PlanRepository repository;

    private final Cache<Key, PlanRow> plans;

    private final Cache<Key, Dataset> datasets;

    /** Builds the two bounded caches and publishes their hit/miss metrics. */
    public PlanCache(PlanRepository repository, MeterRegistry metrics) {
        this.repository = repository;
        this.plans = Caffeine.newBuilder().maximumWeight(MAX_WEIGHT).weigher((Key key, PlanRow row) -> weight(row)).expireAfterAccess(
            Duration.ofMinutes(30)).recordStats().build();
        this.datasets = Caffeine.newBuilder().maximumWeight(MAX_WEIGHT).weigher((Key key, Dataset dataset) -> weight(dataset)).expireAfterAccess(
            Duration.ofMinutes(30)).recordStats().build();
        CaffeineCacheMetrics.monitor(metrics, plans, "evfleet.plans");
        CaffeineCacheMetrics.monitor(metrics, datasets, "evfleet.datasets");
    }

    /** A plan by id, from the cache or the database. */
    public PlanRow plan(String tenant, String id) {
        return plans.get(new Key(tenant, id), key -> repository.plan(tenant, id));
    }

    /** A dataset revision, from the cache or the database. */
    public Dataset dataset(String tenant, long revision) {
        return datasets.get(new Key(tenant, Long.toString(revision)), key -> repository.dataset(tenant, revision));
    }

    /** Approximate size of a cached plan, used for eviction. */
    private static int weight(PlanRow row) {
        var plan = row.plan();

        return 1 + plan.actions().size() + plan.history().size() + plan.assignments().size() + plan.historyAssignments().size();
    }

    /** Approximate size of a cached dataset, used for eviction. */
    private static int weight(Dataset dataset) {
        int prefix = dataset.prefixEvidence() == null ? 0 : dataset.prefixEvidence().actions().size() + dataset.prefixEvidence().trips().size();

        return 1 + dataset.trips().size() + dataset.vehicles().size() + dataset.history().size() + prefix;
    }
}
