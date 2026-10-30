package com.moveinsync.roster.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.moveinsync.roster.metrics.OutboxMetrics;

import java.util.List;

@Component
public class OutboxPoller {

    private final JdbcTemplate jdbc;
    private final ChangeEventPublisher publisher;
    private final int batchSize;
    private final OutboxMetrics metrics;

    public OutboxPoller(JdbcTemplate jdbc, ChangeEventPublisher publisher,
                        @Value("${roster.outbox.batch-size:100}") int batchSize,
                        OutboxMetrics metrics) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${roster.outbox.poll-interval-ms:5000}")
    public void poll() {
        pollOnce();
    }

    /** Polls one bounded batch. Exposed so integration tests can run a poll deterministically. */
    public int pollOnce() {
        // This is deliberately a single-instance poller. Multiple instances can scale with
        // SELECT ... FOR UPDATE SKIP LOCKED on PostgreSQL; that dialect-specific locking is
        // excluded here so the H2 test database and SQL stay simple.
        List<ChangeEvent> events = jdbc.query("""
                        SELECT id, tenant_id, employee_id, event_type, payload
                          FROM outbox_event
                         WHERE published = FALSE
                         ORDER BY created_at ASC, id ASC
                         LIMIT ?
                        """,
                (rs, rowNum) -> new ChangeEvent(rs.getLong("id"), rs.getString("tenant_id"),
                        rs.getString("employee_id"), rs.getString("event_type"), rs.getString("payload")),
                batchSize);

        int publishedCount = 0;
        for (ChangeEvent event : events) {
            try {
                publisher.publish(event);
                // A crash after publication but before this update can redeliver the row;
                // consumers must therefore make their event handling idempotent.
                jdbc.update("""
                        UPDATE outbox_event
                           SET published = TRUE, published_at = CURRENT_TIMESTAMP,
                               attempts = attempts + 1, last_error = NULL
                         WHERE id = ? AND published = FALSE
                        """, event.id());
                publishedCount++;
            } catch (RuntimeException failure) {
                metrics.publishError();
                // Keep exception details out of the database: downstream exception text may contain PII.
                jdbc.update("""
                        UPDATE outbox_event
                           SET attempts = attempts + 1, last_error = ?
                         WHERE id = ? AND published = FALSE
                        """, failure.getClass().getSimpleName(), event.id());
            }
        }
        return publishedCount;
    }
}
