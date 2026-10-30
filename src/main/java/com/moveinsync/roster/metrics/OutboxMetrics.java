package com.moveinsync.roster.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

@Component
public class OutboxMetrics {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Counter publishErrors;

    public OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.publishErrors = registry.counter("roster.outbox.publish.errors");
        Gauge.builder("roster.outbox.depth", this, OutboxMetrics::outboxDepth).register(registry);
        Gauge.builder("roster.outbox.event.lag.seconds", this, OutboxMetrics::oldestEventLagSeconds)
                .register(registry);
        Gauge.builder("roster.reconciliation.missing.percentage", this, OutboxMetrics::latestMissingPercentage)
                .register(registry);
    }

    public void publishError() {
        publishErrors.increment();
    }

    private double outboxDepth() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE published = FALSE", Long.class);
    }

    private double oldestEventLagSeconds() {
        Timestamp oldest = jdbc.queryForObject(
                "SELECT MIN(created_at) FROM outbox_event WHERE published = FALSE", Timestamp.class);
        return oldest == null ? 0.0 : Math.max(0.0, Duration.between(oldest.toInstant(), clock.instant()).toMillis() / 1000.0);
    }

    private double latestMissingPercentage() {
        try {
            Number percentage = jdbc.queryForObject("""
                    SELECT missing_pct FROM reconciliation_run
                     WHERE completed_at IS NOT NULL ORDER BY id DESC LIMIT 1
                    """, Number.class);
            return percentage == null ? 0.0 : percentage.doubleValue();
        } catch (org.springframework.dao.EmptyResultDataAccessException noRun) {
            return 0.0;
        }
    }
}
