package com.moveinsync.evfleet;

import static org.junit.jupiter.api.Assertions.*;

import com.moveinsync.evfleet.domain.Action;
import com.moveinsync.evfleet.domain.ActionType;
import com.moveinsync.evfleet.domain.Charger;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Disruption;
import com.moveinsync.evfleet.domain.DisruptionKind;
import com.moveinsync.evfleet.domain.DomainException;
import com.moveinsync.evfleet.domain.Fixtures;
import com.moveinsync.evfleet.domain.InputRules;
import com.moveinsync.evfleet.domain.Interval;
import com.moveinsync.evfleet.domain.Location;
import com.moveinsync.evfleet.domain.Plan;
import com.moveinsync.evfleet.domain.PrefixEvidence;
import com.moveinsync.evfleet.domain.RouteBlock;
import com.moveinsync.evfleet.domain.Tariff;
import com.moveinsync.evfleet.domain.Trip;
import com.moveinsync.evfleet.domain.Vehicle;
import com.moveinsync.evfleet.planner.ChargerCalendar;
import com.moveinsync.evfleet.planner.EnergyModel;
import com.moveinsync.evfleet.planner.GreedyPlanner;
import com.moveinsync.evfleet.planner.LowerBound;
import com.moveinsync.evfleet.planner.OfflineTravel;
import com.moveinsync.evfleet.planner.Planner;
import com.moveinsync.evfleet.planner.RepairPreparer;
import com.moveinsync.evfleet.planner.ValidatingPlanner;
import com.moveinsync.evfleet.validator.IndependentValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Planner, energy model and validator tests; no web server or database needed. */
class CoreTests {

    // ---- Small builders: every test cab has a 60 kWh battery (floor 9,000 Wh), uses 200 Wh/km and a 60 kW CCS plug ----

    /** A cab with the given charge at a location, free from time 0. */
    static Vehicle cab(String id, long chargeWh, String location) {
        return new Vehicle(id, 60000, chargeWh, 200, location, 0, 60000, "CCS", 10000);
    }

    /** A trip whose latest allowed finish is exactly its planned end. */
    static Trip trip(String id, String from, String to, long start, int seconds, long metres) {
        return new Trip(id, from, to, start, seconds, metres, start + seconds, "employee");
    }

    /** A 60 kW CCS plug with no outages. */
    static Charger plug(String id, String location) {
        return new Charger(id, "h", location, 60000, "CCS", List.of());
    }

    /** A day from 0 to 10,000 s with one flat tariff, no traffic or AC uplift, 10 m/s speed, 90% charger efficiency. */
    static Dataset day(List<Location> locations, List<Vehicle> cabs, List<Trip> trips, List<Charger> plugs) {
        return new Dataset(0, 10000, locations, cabs, trips, plugs, List.of(new Tariff(0, 10000, 8000000)), 10000, 10, 9000, 3500, "offline-euclidean-v1",
            List.of(), List.of(), Map.of(), null, null);
    }

    /** The same dataset with different cabs, trips and plugs. */
    static Dataset with(Dataset dataset, List<Vehicle> cabs, List<Trip> trips, List<Charger> plugs) {
        return dataset.withScheduleState(cabs, trips, plugs, List.of(), Map.of(), null, null);
    }

    /** One cab at "a", one 10 km trip from "a" to "b" at 1000 s, and optionally one plug at "a". */
    static Dataset fixture(long chargeWh, boolean withPlug) {
        var places = List.of(new Location("a", 0, 0), new Location("b", 5000, 0));

        return day(places, List.of(cab("v", chargeWh, "a")), List.of(trip("t", "a", "b", 1000, 100, 10000)), withPlug ? List.of(plug("c", "a")) : List.of());
    }

    static Plan solve(Dataset dataset) {
        return Planner.standard().solve(dataset, 2, null);
    }

    /** Construction only, without the local-search phases. */
    static Plan constructOnly(Dataset dataset) {
        return new ValidatingPlanner(new GreedyPlanner(false)).solve(dataset, 2, null);
    }

    /** A copy of a plan with tampered actions, assignments or history, for validator tests. */
    static Plan tampered(Plan plan, List<Action> actions, Map<String, String> assignments, List<Action> history) {
        return new Plan(actions, assignments, plan.backups(), history, plan.historyAssignments(), plan.lowerBound(), plan.changedAssignments(),
            plan.searchExhausted(), plan.validation(), plan.elapsedS(), plan.asOf());
    }

    // ---- Battery floor and charging ----

    /** The 10 km trip needs 2,000 Wh: 11,000 Wh finishes exactly at the 9,000 Wh floor, 1 Wh less cannot go. */
    @ParameterizedTest
    @CsvSource({"11000,0", "10999,1", "9000,1"})
    void floorBoundary(long chargeWh, int expectedBackups) {
        var plan = solve(fixture(chargeWh, false));

        assertEquals(expectedBackups, plan.backupCount());
        assertTrue(plan.validation().valid());
    }

    /** A cab too low for its first trip charges first instead of giving the trip away. */
    @Test
    void chargingBeforeFirstPickupIsNotPruned() {
        var plan = solve(fixture(10000, true));

        assertEquals(0, plan.backupCount());
        assertTrue(plan.actions().stream().anyMatch(a -> a.kind() == ActionType.CHARGE));
    }

    /** Empty driving to a charger or a pickup also counts against the floor. */
    @Test
    void approachAndEmptyMovementMustRemainAboveFloor() {
        var base = fixture(10000, true);
        var farPlug = new Charger("c", "h", "b", 60000, "CCS", List.of());

        assertEquals(1, solve(with(base, base.vehicles(), base.trips(), List.of(farPlug))).backupCount());

        var noPlug = fixture(11000, false);
        var cabAwayFromPickup = noPlug.vehicles().get(0).withState(0, "b", 11000);

        assertEquals(1, solve(with(noPlug, List.of(cabAwayFromPickup), noPlug.trips(), List.of())).backupCount());
    }

    /**
     * Cross-batch trap: construction gives trip x to cab A (no empty driving), so the later long trip b, which only A can power, falls to a backup. Backup
     * rescue moves x to cab Z and gives b to A.
     */
    @Test
    void greedyTrapIsFixedByLocalNeighbourhood() {
        var places = List.of(new Location("a", 0, 0), new Location("b", 5000, 0), new Location("c", 900, 0));
        var cabs = List.of(cab("A", 60000, "a"), cab("W", 10000, "b"), cab("Z", 10000, "c"));
        var trips = List.of(trip("x", "a", "a", 100, 200, 1000), trip("y", "b", "b", 100, 50, 1000), trip("b", "a", "a", 200, 100, 100000));
        var dataset = day(places, cabs, trips, List.of());

        assertEquals(1, constructOnly(dataset).backupCount());

        var rescued = solve(dataset);

        assertEquals(0, rescued.backupCount());
        assertEquals("A", rescued.assignments().get("b"));
        assertEquals("Z", rescued.assignments().get("x"));
    }

    /** Planner and validator compute every charging session identically, across tariff and 80% curve boundaries. */
    @Test
    void chargingCurveTariffsAndIndependentNumerics() {
        var base = fixture(12000, true);
        var threeTariffs = List.of(new Tariff(0, 60, 2000000), new Tariff(60, 120, 9000000), new Tariff(120, 10000, 4000000));
        var dataset = new Dataset(base.start(), base.end(), base.locations(), base.vehicles(), base.trips(), base.chargers(), threeTariffs, base.trafficBp(),
            base.speedMps(), base.efficiencyBp(), base.slowBp(), base.modelVersion(), List.of(), List.of(), Map.of(), null, null);
        var model = new EnergyModel();
        var validator = new IndependentValidator();
        var vehicle = dataset.vehicles().get(0);
        var charger = dataset.chargers().get(0);

        assertEquals(1143, model.duration(vehicle, 48000, 54000, 60000, dataset));

        var random = new Random(6);

        for (int i = 0; i < 200; i++) {
            long chargeWh = i == 0 ? 47100 : 9000 + random.nextInt(51001);
            long start = i == 0 ? 0 : random.nextInt(100);
            long seconds = i == 0 ? 180 : 1 + random.nextInt(3000);

            var planner = model.charge(vehicle, chargeWh, start, seconds, charger.powerW(), dataset);
            var check = validator.charge(dataset, vehicle, charger, chargeWh, start, start + seconds);

            assertEquals(planner.energyWh(), check.energyWh());
            assertEquals(planner.gridWh(), check.gridWh());
            assertEquals(planner.costMicros(), check.costMicros());
        }
    }

    /** Sessions are booked around outages, and a plug with the wrong connector is never used. */
    @Test
    void calendarEndpointCompatibilityAndOutage() {
        var base = fixture(10000, true);
        var plugWithOutages = base.chargers().get(0).outage(new Interval(0, 100)).outage(new Interval(200, 300));
        var calendar = new ChargerCalendar(with(base, base.vehicles(), base.trips(), List.of(plugWithOutages)));

        assertEquals(List.of(100L), calendar.starts("c", 100, 200, 100, base.tariffs()));

        var wrongConnector = new Charger("c", "h", "a", 60000, "OTHER", List.of());

        assertEquals(1, solve(with(base, base.vehicles(), base.trips(), List.of(wrongConnector))).backupCount());
    }

    /** Out-of-range values and broken cross-field rules are rejected before planning. */
    @Test
    void inputRulesRejectOutOfRangeValues() {
        var base = fixture(12000, true);
        var zeroCapacity = new Vehicle("v", 0, 0, 200, "a", 0, 60000, "CCS", 10000);
        var airConditioningBelowOne = new Vehicle("v", 60000, 12000, 200, "a", 0, 60000, "CCS", 9_999);
        var chargedAboveCapacity = new Vehicle("v", 60000, 60001, 200, "a", 0, 60000, "CCS", 10000);

        for (var bad : List.of(zeroCapacity, airConditioningBelowOne, chargedAboveCapacity)) {
            var dataset = with(base, List.of(bad), base.trips(), base.chargers());

            assertEquals("INVALID_DATASET", assertThrows(DomainException.class, () -> InputRules.validate(dataset)).getMessage());
        }

        var freezeTooLong = new Disruption("e", 1, 0, DisruptionKind.SOC, "v", 10000L, "a", 601, Map.of());
        var noSequence = new Disruption("e", 0, 0, DisruptionKind.SOC, "v", 10000L, "a", 60, Map.of());

        for (var bad : List.of(freezeTooLong, noSequence)) {
            assertEquals("INVALID_DISRUPTION", assertThrows(DomainException.class, () -> InputRules.event(bad, base)).getMessage());
        }
    }

    // ---- Independent validator ----

    /** Any tampering with a valid plan is caught. */
    @ParameterizedTest
    @ValueSource(strings = {"energy", "time", "route", "missing", "duplicate", "assignment", "history", "cost", "capacity"})
    void validatorRejectsCorruption(String corruption) {
        var dataset = fixture(12000, true);
        var plan = solve(dataset);
        var actions = new ArrayList<>(plan.actions());
        var assignments = plan.assignments();
        var history = plan.history();

        int tripIndex = 0;
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).kind() == ActionType.TRIP) {
                tripIndex = i;
            }
        }
        var trip = actions.get(tripIndex);

        switch (corruption) {
            case "energy" -> actions.set(tripIndex, trip.withMeasurements(trip.end(), trip.beforeWh(), 0, 0, 0));
            case "capacity" -> actions.set(tripIndex, trip.withMeasurements(trip.end(), trip.beforeWh(), 70000, 0, 0));
            case "time" -> actions.set(tripIndex, trip.withMeasurements(1200, trip.beforeWh(), trip.afterWh(), 0, 0));
            case "route" -> actions.set(tripIndex, new Action(trip.kind(), trip.vehicleId(), trip.start(), trip.end(), "b", trip.destination(), trip.beforeWh(),
                trip.afterWh(), trip.tripId(), null, 0, 0));
            case "cost" -> actions.set(tripIndex, trip.withMeasurements(trip.end(), trip.beforeWh(), trip.afterWh(), 0, 999));
            case "missing" -> actions.remove(tripIndex);
            case "duplicate" -> actions.add(trip);
            case "assignment" -> assignments = Map.of();
            case "history" -> history = List.copyOf(actions);
        }

        assertFalse(new IndependentValidator().validate(dataset, tampered(plan, actions, assignments, history)).valid());
    }

    /** Two sessions on one plug at once, or a session during an outage, are rejected. */
    @Test
    void independentCapacityAndOutageSweep() {
        var dataset = fixture(10000, true);
        var plan = solve(dataset);
        var session = plan.actions().stream().filter(a -> a.kind() == ActionType.CHARGE).findFirst().orElseThrow();

        var doubleBooked = new ArrayList<>(plan.actions());
        doubleBooked.add(session);
        var report = new IndependentValidator().validate(dataset, tampered(plan, doubleBooked, plan.assignments(), plan.history()));

        assertTrue(report.violations().contains("CHARGER_CAPACITY"));

        var outageDuringSession = dataset.chargers().get(0).outage(new Interval(session.start(), session.end()));
        var withOutage = with(dataset, dataset.vehicles(), dataset.trips(), List.of(outageDuringSession));

        assertTrue(new IndependentValidator().validate(withOutage, plan).violations().contains("CHARGER_OUTAGE"));
    }

    // ---- Objective, time budget and lower bound ----

    /** With no time budget the plan is still valid; fewer backups always score better; the lower bound is reported. */
    @Test
    void budgetFallbackLowerBoundAndLexicographicScore() {
        var dataset = fixture(12000, false);
        var noBudget = Planner.standard().solve(dataset, 0, null);

        assertTrue(noBudget.searchExhausted());
        assertEquals(1, noBudget.backupCount());
        assertTrue(noBudget.validation().valid());
        assertTrue(Planner.score(false).compare(solve(dataset), noBudget) < 0);

        var fourAtOnce = new ArrayList<Trip>();
        for (int i = 0; i < 4; i++) {
            fourAtOnce.add(trip("t" + i, "a", "b", 1000, 100, 10000));
        }
        var oneCabFourTrips = with(dataset, dataset.vehicles(), fourAtOnce, List.of());

        assertEquals(3, new LowerBound().calculate(oneCabFourTrips));
        assertEquals(3, solve(oneCabFourTrips).backupCount());
    }

    /** A cab already below the 15% floor stops planning with a safety incident. */
    @Test
    void belowFloorIsAnIncident() {
        var error = assertThrows(DomainException.SafetyIncident.class, () -> solve(fixture(8999, true)));

        assertEquals("SAFETY_INCIDENT_INITIAL_FLOOR", error.getMessage());
        assertEquals(400, error.status());
    }

    // ---- Live re-planning ----

    /** A charger failure re-plans safely while other cabs are mid-trip, mid-drive or charging elsewhere. */
    @ParameterizedTest
    @ValueSource(strings = {"TRIP", "DRIVE", "CHARGE"})
    void unrelatedActiveActionsDoNotBlockFailure(String activeKind) {
        Dataset dataset;
        long failAt;
        String failedPlug = "c";

        if (activeKind.equals("CHARGE")) {
            var base = fixture(10000, true);
            var plugs = new ArrayList<>(base.chargers());
            plugs.add(new Charger("unrelated", "h", "a", 60000, "CCS", List.of()));
            dataset = with(base, base.vehicles(), base.trips(), plugs);
            failAt = 30;
            failedPlug = "unrelated";
        } else {
            dataset = fixture(60000, true);
            failAt = 1050;
            if (activeKind.equals("DRIVE")) {
                dataset = with(dataset, List.of(dataset.vehicles().get(0).withState(0, "b", 60000)), dataset.trips(), dataset.chargers());
                failAt = 750;
            }
        }

        var before = solve(dataset);
        var failure = new Disruption("failure", 1, failAt, DisruptionKind.CHARGER_FAILURE, failedPlug, null, null, 0, Map.of());
        var repairInput = new RepairPreparer().prepare(dataset, before, failure);
        var after = Planner.standard().solve(repairInput, 1, before);

        assertTrue(after.validation().valid());
        assertTrue(after.history().stream().anyMatch(a -> a.kind() == ActionType.valueOf(activeKind)));
    }

    /** A charge cut off mid-session needs the cab's measured charge; without it the system raises an incident. */
    @Test
    void interruptedChargeRequiresConfirmedEnergy() {
        var dataset = fixture(10000, true);
        var plan = solve(dataset);

        var unconfirmed = new Disruption("failure", 1, 30, DisruptionKind.CHARGER_FAILURE, "c", null, null, 0, Map.of());
        var error = assertThrows(DomainException.class, () -> new RepairPreparer().prepare(dataset, plan, unconfirmed));

        assertTrue(error.getMessage().contains("UNCONFIRMED"));

        var confirmed = new Disruption("f", 1, 30, DisruptionKind.CHARGER_FAILURE, "c", null, null, 0, Map.of("v", 10450L));
        var repaired = Planner.standard().solve(new RepairPreparer().prepare(dataset, plan, confirmed), 1, plan);

        assertTrue(repaired.validation().valid());
        assertEquals(1, repaired.backupCount());
    }

    /** Work kept from the old plan is re-checked too, and the route memo lives only for one solve. */
    @Test
    void prefixEvidenceIsIndependentlyReplayedAndCacheIsLocal() {
        var dataset = fixture(60000, true);
        var plan = solve(dataset);
        var failure = new Disruption("f", 1, 990, DisruptionKind.CHARGER_FAILURE, "c", null, null, 30, Map.of());
        var repairInput = new RepairPreparer().prepare(dataset, plan, failure);
        var repaired = Planner.standard().solve(repairInput, 1, plan);

        var kept = repairInput.prefixEvidence();
        var corruptedKept = kept.actions().stream().map(a -> a.kind() == ActionType.TRIP ? a.withMeasurements(a.end(), a.beforeWh(), 0, 0, 0) : a).toList();
        var corruptedInput = repairInput.withScheduleState(repairInput.vehicles(), repairInput.trips(), repairInput.chargers(), repairInput.history(),
            repairInput.historyAssignments(), new PrefixEvidence(kept.vehicles(), corruptedKept, kept.trips(), kept.chargers()), repairInput.asOf());

        assertFalse(new IndependentValidator().validate(corruptedInput, repaired).valid());

        var travel = new OfflineTravel(dataset);

        assertEquals(travel.route("a", "b"), travel.route("a", "b"));
        assertEquals(1, travel.cacheSize());
    }

    /** On the 300-cab day, a drift report reopens only a few trips and every trip is still accounted for. */
    @Test
    void incrementalScaleRepairBoundsChurnAndPreservesCompleteCoverage() {
        var dataset = Fixtures.fleet(300, 40, 1500, 6);
        var before = constructOnly(dataset);
        var drift = new Disruption("drift", 1, 0, DisruptionKind.SOC, "v000", 9000L, dataset.vehicles().get(0).location(), 60, Map.of());
        var repairInput = new RepairPreparer().prepare(dataset, before, drift);

        assertTrue(repairInput.trips().size() <= 15 + before.backupCount());

        var after = Planner.standard().solve(repairInput, 1.2, before);
        long changed = before.allAssignments().entrySet().stream().filter(e -> !Objects.equals(e.getValue(), after.allAssignments().get(e.getKey()))).count();

        assertTrue(after.validation().valid());
        assertEquals(1500, after.allAssignments().size());
        assertTrue(after.totalBackupCount() <= before.totalBackupCount() + 2);
        assertTrue(changed <= 15);
    }

    /**
     * The case study's live example: cab A finishes trip1 at 22% instead of the planned 46%. Its next trip (in 100 s) cannot be reached even after charging, so
     * it moves to another cab; A keeps its later trip by charging first; nothing else changes.
     */
    @Test
    void midShiftDriftMovesOnlyTheUnreachableTripAndChargesTheCab() {
        var cabs = List.of(cab("A", 30000, "a"), cab("B", 40000, "a"), cab("C", 50000, "a"));
        var trips = List.of(trip("trip1", "a", "a", 1000, 500, 10000), trip("trip2", "a", "a", 1600, 500, 30000), trip("trip3", "a", "a", 4000, 500, 30000));
        var dataset = day(List.of(new Location("a", 0, 0)), cabs, trips, List.of(plug("c", "a")));
        var initial = solve(dataset);

        assertEquals(Map.of("trip1", "A", "trip2", "A", "trip3", "A"), initial.assignments());

        var drift = new Disruption("drift", 1, 1500, DisruptionKind.SOC, "A", 13200L, "a", 60, Map.of());
        var repaired = Planner.standard().solve(new RepairPreparer().prepare(dataset, initial, drift), 1.5, initial);
        boolean aChargesBeforeTrip3 = repaired.actions().stream().anyMatch(
            a -> a.vehicleId().equals("A") && a.kind() == ActionType.CHARGE && a.start() >= 1500 && a.end() <= 4000);

        assertTrue(repaired.validation().valid());
        assertEquals(0, repaired.totalBackupCount());
        assertNotEquals("A", repaired.allAssignments().get("trip2"));
        assertEquals("A", repaired.allAssignments().get("trip3"));
        assertTrue(aChargesBeforeTrip3);
        assertEquals(1, repaired.changedAssignments());
    }

    // ---- Model details ----

    /** Traffic and air conditioning raise consumption, and a blocked road is reported as unavailable. */
    @Test
    void trafficAcAndMissingEvidenceAreExplicit() {
        var base = fixture(12000, false);
        var airConditioningOn = new Vehicle("v", 60000, 12000, 200, "a", 0, 60000, "CCS", 12000);
        var dataset = new Dataset(base.start(), base.end(), base.locations(), List.of(airConditioningOn), base.trips(), base.chargers(), base.tariffs(), 15000,
            base.speedMps(), base.efficiencyBp(), base.slowBp(), base.modelVersion(), List.of(new RouteBlock("a", "b")), List.of(), Map.of(), null, null);

        // 1 km x 200 Wh/km x 1.2 (AC) x 1.5 (traffic) = 360 Wh.
        assertEquals(360, new EnergyModel().consumption(airConditioningOn, 1000, dataset));
        assertThrows(DomainException.class, () -> new OfflineTravel(dataset).route("a", "b"));
    }

    /** A trip no battery could ever run counts in the lower bound; plans cannot be modified after creation. */
    @Test
    void impossiblePassengerEnergyAddsToSafeBoundAndFrozenValuesCannotMutate() {
        var base = fixture(60000, false);
        var thousandKm = new Trip("huge", "a", "b", 1000, 100, 1000000, 1100, "e");
        var dataset = with(base, base.vehicles(), List.of(thousandKm), List.of());

        assertEquals(1, new LowerBound().calculate(dataset));
        assertEquals(1, solve(dataset).backupCount());

        var plan = solve(fixture(12000, false));

        assertThrows(UnsupportedOperationException.class, () -> plan.assignments().put("new", "v"));
    }

    // ---- Planner quality ----

    /** Off-peak is [0, 12000) in the fleet fixture and every trip leaves room to charge in it. */
    @Test
    void chargingMovesIntoTheCheapestWindowWhenTheTimetableAllows() {
        var dataset = Fixtures.fleet(300, 40, 1500, 6);
        var full = solve(dataset);
        var construction = constructOnly(dataset);
        var sessions = full.actions().stream().filter(a -> a.kind() == ActionType.CHARGE).toList();

        assertEquals(0, full.backupCount());
        assertFalse(sessions.isEmpty());
        assertTrue(sessions.stream().allMatch(a -> a.end() <= 12000), "every charging session should be off-peak");
        assertTrue(full.mutableCostMicros() <= construction.mutableCostMicros());
    }

    /** One plug, two cabs that each need a 100 s charge before simultaneous pickups at 150 s. */
    @Test
    void backupReasonNamesChargerCongestion() {
        var cabs = List.of(cab("x", 9500, "a"), cab("y", 9500, "a"));
        var trips = List.of(trip("t1", "a", "a", 150, 100, 10000), trip("t2", "a", "a", 150, 100, 10000));
        var plan = solve(day(List.of(new Location("a", 0, 0)), cabs, trips, List.of(plug("c", "a"))));

        assertEquals(1, plan.backupCount());

        var reason = plan.backups().values().iterator().next();

        assertEquals("CHARGER_CONGESTION", reason.code());
        assertEquals(1, reason.evidence().get("cabsBlockedByChargerCongestion"));
        assertTrue(reason.explanation().contains("charger"));
    }
}
