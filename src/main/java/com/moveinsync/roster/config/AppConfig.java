package com.moveinsync.roster.config;

import com.moveinsync.roster.domain.RosterEventApplier;
import com.moveinsync.roster.validation.RecordValidator;
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

    @Bean
    public RecordValidator recordValidator() {
        return new RecordValidator();
    }
}
