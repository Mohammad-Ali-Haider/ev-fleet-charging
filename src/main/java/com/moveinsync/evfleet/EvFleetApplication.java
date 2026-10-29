package com.moveinsync.evfleet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Application entry point; starts Spring Boot (the web server, database pool and worker). */
@SpringBootApplication
@EnableScheduling
public class EvFleetApplication {

    static {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    /** Starts the app. */
    public static void main(String[] args) {
        SpringApplication.run(EvFleetApplication.class, args);
    }
}
