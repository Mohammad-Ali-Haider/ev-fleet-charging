package com.moveinsync.evfleet.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable schedule, its retained history, and the evidence used to assess its quality.
 */
public record Plan(List<Action> actions, Map<String, String> assignments, Map<String, Reason> backups, List<Action> history,
    Map<String, String> historyAssignments, int lowerBound, int changedAssignments, boolean searchExhausted, Report validation, double elapsedS, Long asOf) {

    public Plan {
        actions = List.copyOf(actions);
        assignments = copyKeepingNulls(assignments);
        backups = Map.copyOf(backups);
        history = List.copyOf(history);
        historyAssignments = copyKeepingNulls(historyAssignments);
    }

    /** Retained and new assignments together, trip to cab (null means backup). */
    public Map<String, String> allAssignments() {
        var all = new LinkedHashMap<>(historyAssignments);
        all.putAll(assignments);

        return copyKeepingNulls(all);
    }

    /** Backup trips in the part of the plan still being decided. */
    public int backupCount() {
        return (int) assignments.values().stream().filter(Objects::isNull).count();
    }

    /** Backup trips over the whole day, including retained history. */
    public int totalBackupCount() {
        return (int) allAssignments().values().stream().filter(Objects::isNull).count();
    }

    /** Charging cost of the part of the plan still being decided. */
    public long mutableCostMicros() {
        return actions.stream().mapToLong(Action::costMicros).sum();
    }

    /** Charging cost of the whole day, including retained history. */
    public long timelineCostMicros() {
        return mutableCostMicros() + history.stream().mapToLong(Action::costMicros).sum();
    }

    /** Copy of this plan with its validation report attached. */
    public Plan withValidation(Report report, double elapsedSeconds) {
        return new Plan(actions, assignments, backups, history, historyAssignments, lowerBound, changedAssignments, searchExhausted, report, elapsedSeconds,
            asOf);
    }

    /** Map.copyOf rejects nulls, and a null value marks a backup trip, so assignment maps are copied this way. */
    private static <K, V> Map<K, V> copyKeepingNulls(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
