package com.moveinsync.evfleet.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Synthetic offline fixtures; no passenger or provider data. */
public final class Fixtures {

    public static Dataset demo() {
        return new Dataset(0, 20000, List.of(new Location("hub", 0, 0), new Location("office", 3000, 0), new Location("home", 8000, 0)),
            List.of(new Vehicle("ev-a", 60000, 13000, 200, "hub", 0, 60000, "CCS", 10000),
                new Vehicle("ev-b", 60000, 28000, 220, "office", 0, 60000, "CCS", 10000)),
            List.of(new Trip("trip-1", "office", "home", 1800, 900, 22000, 2800, "demo-employee"),
                new Trip("trip-2", "home", "office", 4000, 900, 20000, 5000, "demo-employee"),
                new Trip("trip-3", "office", "hub", 7000, 1200, 35000, 8500, "demo-employee")),
            List.of(new Charger("plug-1", "hub", "hub", 60000, "CCS", List.of())), List.of(new Tariff(0, 3000, 8000000), new Tariff(3000, 20000, 14000000)),
            10000, 10, 9000, 3500, "offline-euclidean-v1", List.of(), List.of(), Map.of(), null, null);
    }

    /**
     * A tight evening peak: 300 cabs starting at 17-40% charge, 40 plugs at four hubs, 1,500 trips of 14-32 km spread over five hours, traffic +30% and air
     * conditioning +15%. Plugs must run most of the shift, so some trips need backup cabs; this exercises backup reasons and charger congestion.
     */
    public static Dataset rushHour(int seed) {
        var random = new Random(seed);
        var locations = new ArrayList<Location>();

        for (int i = 0; i < 12; i++) {
            locations.add(new Location("p" + i, random.nextInt(20000), random.nextInt(20000)));
        }

        var vehicles = new ArrayList<Vehicle>();

        for (int i = 0; i < 300; i++) {
            vehicles.add(
                new Vehicle("v%03d".formatted(i), 60000, 10000 + random.nextInt(14000), 180 + random.nextInt(60), "p" + (i % 12), 0, 60000, "CCS", 11500));
        }

        var chargers = new ArrayList<Charger>();

        for (int i = 0; i < 40; i++) {
            chargers.add(new Charger("c%02d".formatted(i), "h" + (i % 4), "p" + (i % 4) * 3, 60000, "CCS", List.of()));
        }

        var trips = new ArrayList<Trip>();

        for (int i = 0; i < 1500; i++) {
            long start = 3600 + random.nextInt(18000);
            int duration = 900 + random.nextInt(900);
            int origin = random.nextInt(12);
            int destination = (origin + 1 + random.nextInt(11)) % 12;
            trips.add(new Trip("t%04d".formatted(i), "p" + origin, "p" + destination, start, duration, 14000 + random.nextInt(18000), start + duration + 300,
                "demo-employee"));
        }

        return new Dataset(0, 32400, locations, vehicles, trips, chargers,
            List.of(new Tariff(0, 7200, 6000000), new Tariff(7200, 21600, 12000000), new Tariff(21600, 32400, 6000000)), 13000, 10, 9000, 3500,
            "offline-euclidean-v1", List.of(), List.of(), Map.of(), null, null);
    }

    public static Dataset fleet(int count, int plugs, int trips, int seed) {
        var random = new Random(seed);
        var locations = new ArrayList<Location>();

        for (int i = 0; i < 10; i++) {
            locations.add(new Location("p" + i, i * 1000, (i % 3) * 1000));
        }

        var vehicles = new ArrayList<Vehicle>();

        for (int i = 0; i < count; i++) {
            vehicles.add(new Vehicle("v%03d".formatted(i), 60000, 16000 + random.nextInt(1500), 200, "p" + (i % 10), 0, 60000, "CCS", 10000));
        }

        var chargers = new ArrayList<Charger>();

        for (int i = 0; i < plugs; i++) {
            chargers.add(new Charger("c%02d".formatted(i), "h" + (i % 10), "p" + (i % 10), 60000, "CCS", List.of()));
        }

        var requests = new ArrayList<Trip>();

        for (int i = 0; i < trips; i++) {
            long start = 3600 + (i / count) * 4000;
            requests.add(
                new Trip("t%04d".formatted(i), "p" + (i % 10), "p" + ((i + 1) % 10), start, 1000, 8000 + random.nextInt(2000), start + 1100, "demo-employee"));
        }

        return new Dataset(0, 30000, locations, vehicles, requests, chargers, List.of(new Tariff(0, 12000, 6000000), new Tariff(12000, 30000, 12000000)), 10000,
            10, 9000, 3500, "offline-euclidean-v1", List.of(), List.of(), Map.of(), null, null);
    }
}
