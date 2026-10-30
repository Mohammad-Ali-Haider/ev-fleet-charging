package com.moveinsync.evfleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.evfleet.application.DisruptionService;
import com.moveinsync.evfleet.application.JobQueue;
import com.moveinsync.evfleet.application.PayloadHasher;
import com.moveinsync.evfleet.application.PlanCache;
import com.moveinsync.evfleet.application.PlanQueryService;
import com.moveinsync.evfleet.application.PlanRequestService;
import com.moveinsync.evfleet.application.PlanningService;
import com.moveinsync.evfleet.persistence.PlanRepository;
import com.moveinsync.evfleet.planner.Planner;
import com.moveinsync.evfleet.planner.RepairPreparer;
import com.moveinsync.evfleet.validator.IndependentValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Builds the real service graph on a real PostgreSQL database, without starting Spring. */
final class DatabaseSupport {

    record Services(PlanRepository repository, PlanRequestService requests, DisruptionService disruptions, PlanQueryService queries, PlanningService planning,
        SimpleMeterRegistry metrics) {
    }

    private static DataSource dataSource() {
        var source = new PGSimpleDataSource();
        source.setURL(System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://127.0.0.1:5546/evfleet_java"));
        source.setUser(System.getenv().getOrDefault("DB_USER", "evfleet"));
        source.setPassword(System.getenv().getOrDefault("DB_PASSWORD", ""));
        // Same migrations and settings the application applies at start-up through Spring Boot.
        Flyway.configure().dataSource(source).baselineOnMigrate(true).baselineVersion("1").load().migrate();

        return source;
    }

    static PlanRepository repository() {
        return new PlanRepository(dataSource(), new ObjectMapper());
    }

    static Services services() {
        var source = dataSource();
        var mapper = new ObjectMapper();
        var metrics = new SimpleMeterRegistry();
        var repository = new PlanRepository(source, mapper);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var cache = new PlanCache(repository, metrics);
        var queue = new JobQueue(repository, metrics);
        var hasher = new PayloadHasher(mapper);

        return new Services(repository, new PlanRequestService(repository, queue, hasher, transactions),
            new DisruptionService(repository, cache, queue, new RepairPreparer(), hasher, transactions, metrics),
            new PlanQueryService(repository, cache, new IndependentValidator()),
            new PlanningService(repository, cache, Planner.standard(), queue, transactions, metrics), metrics);
    }
}
