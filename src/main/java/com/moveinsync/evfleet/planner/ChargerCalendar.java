package com.moveinsync.evfleet.planner;

import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.ActionType;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Interval;
import com.moveinsync.evfleet.domain.Tariff;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Per-plug busy intervals (outages plus reserved sessions), kept sorted by start so lookups never copy or re-sort.
 */
public final class ChargerCalendar {

    private static final Comparator<Interval> BY_START = Comparator.comparingLong(Interval::start).thenComparingLong(Interval::end);

    private final Map<String, List<Interval>> busy = new HashMap<>();

    /** Starts each plug's calendar with its known outages. */
    public ChargerCalendar(Dataset dataset) {
        for (var charger : dataset.chargers()) {
            var intervals = new ArrayList<>(charger.outages());
            intervals.sort(BY_START);
            busy.put(charger.id(), intervals);
        }
    }

    /** A calendar holding only the dataset's outages, used to tell congestion from lack of time. */
    public static ChargerCalendar outagesOnly(Dataset dataset) {
        return new ChargerCalendar(dataset);
    }

    /**
     * Earliest feasible start at or after {@code earliest}, and at or after each tariff boundary in the window, for a session of {@code duration} seconds that
     * must finish by {@code latest}.
     */
    public List<Long> starts(String plug, long earliest, long latest, long duration, List<Tariff> tariffs) {
        var intervals = busy.get(plug);
        var requested = new TreeSet<Long>();
        requested.add(earliest);

        for (var tariff : tariffs) {
            if (tariff.start() >= earliest && tariff.start() <= latest) {
                requested.add(tariff.start());
            }
        }

        var result = new LinkedHashSet<Long>();

        for (long from : requested) {
            long start = from;

            for (var interval : intervals) {
                if (interval.end() <= start) {
                    continue;
                }
                if (start + duration <= interval.start()) {
                    break;
                }
                start = Math.max(start, interval.end());
            }
            if (start + duration <= latest) {
                result.add(start);
            }
        }

        return new ArrayList<>(result);
    }

    /** Books the charging sessions in these actions. */
    public void reserve(List<Action> actions) {
        for (var action : actions) {
            if (action.kind() == ActionType.CHARGE) {
                var intervals = busy.get(action.chargerId());
                var session = new Interval(action.start(), action.end());
                int index = Collections.binarySearch(intervals, session, BY_START);
                intervals.add(index < 0 ? -index - 1 : index, session);
            }
        }
    }

    /** Frees the charging sessions in these actions. */
    public void release(List<Action> actions) {
        for (var action : actions) {
            if (action.kind() == ActionType.CHARGE) {
                var intervals = busy.get(action.chargerId());
                int index = Collections.binarySearch(intervals, new Interval(action.start(), action.end()), BY_START);
                if (index >= 0) {
                    intervals.remove(index);
                }
            }
        }
    }
}
