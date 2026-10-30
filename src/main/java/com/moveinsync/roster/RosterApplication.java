package com.moveinsync.roster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * <p>{@code @SpringBootApplication} is shorthand for {@code @Configuration},
 * {@code @EnableAutoConfiguration}, and {@code @ComponentScan}.  It scans every
 * sub-package of {@code com.moveinsync.roster} for Spring-managed beans.
 *
 * <p>{@code @EnableScheduling} activates the outbox poller introduced in Commit 3.
 */
@SpringBootApplication
@EnableScheduling
public class RosterApplication {

    public static void main(String[] args) {
        SpringApplication.run(RosterApplication.class, args);
    }
}
