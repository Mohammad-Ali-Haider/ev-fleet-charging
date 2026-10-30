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

    /** Starts the app, or with --issue-token prints a local test token and exits. */
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--issue-token")) {
            com.moveinsync.evfleet.security.TokenIssuer.main(java.util.Arrays.copyOfRange(args, 1, args.length));

            return;
        }
        SpringApplication.run(EvFleetApplication.class, args);
    }
}
