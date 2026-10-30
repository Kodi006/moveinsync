package com.moveinsync.roster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * <p>{@code @SpringBootApplication} is shorthand for {@code @Configuration},
 * {@code @EnableAutoConfiguration}, and {@code @ComponentScan}.  It scans every
 * sub-package of {@code com.moveinsync.roster} for Spring-managed beans.
 *
 * <p>Note: {@code @EnableScheduling} is added in Commit 3 when the outbox poller
 * is introduced.  Adding it here prematurely would have no effect but would
 * generate a noisy log line on every startup.
 */
@SpringBootApplication
public class RosterApplication {

    public static void main(String[] args) {
        SpringApplication.run(RosterApplication.class, args);
    }
}
