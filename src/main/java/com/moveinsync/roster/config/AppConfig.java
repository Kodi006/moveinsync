package com.moveinsync.roster.config;

import com.moveinsync.roster.domain.RosterEventApplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RosterEventApplier rosterEventApplier() {
        return new RosterEventApplier();
    }
}
