package com.moveinsync.roster.outbox;

import com.moveinsync.roster.domain.Decision;
import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.service.RosterIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OutboxPollerTest {

    @Autowired private OutboxPoller poller;
    @Autowired private ChangeEventPublisher publisher;
    @Autowired private RosterIngestService ingest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM booking");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM employee");
    }

    @Test
    void publishesOutboxRowOnceAndSkipsItOnNextPoll() {
        AtomicInteger delivered = new AtomicInteger();
        publisher.addListener(event -> delivered.incrementAndGet());
        insertOutbox("tenant-poll", "employee-poll", "UPSERT");

        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(poller.pollOnce()).isZero();
        assertThat(delivered).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT published FROM outbox_event", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void deactivationCancelsOnlyFutureBookings() {
        assertThat(ingest.ingest(new RosterEvent("tenant-booking", "employee-booking", FieldOwner.HR,
                1, EventType.UPSERT, Map.of("name", "Test Employee")))).isEqualTo(Decision.APPLY);
        insertBooking("seat-past", "CURRENT_DATE - 1");
        insertBooking("seat-today", "CURRENT_DATE");
        insertBooking("seat-future", "CURRENT_DATE + 1");

        assertThat(ingest.ingest(new RosterEvent("tenant-booking", "employee-booking", FieldOwner.HR,
                2, EventType.DEACTIVATE, Map.of()))).isEqualTo(Decision.APPLY);
        poller.pollOnce();

        assertThat(statusFor("seat-past")).isEqualTo("BOOKED");
        assertThat(statusFor("seat-today")).isEqualTo("BOOKED");
        assertThat(statusFor("seat-future")).isEqualTo("CANCELLED");
    }

    private void insertOutbox(String tenant, String employee, String eventType) {
        jdbc.update("""
                INSERT INTO outbox_event (tenant_id, employee_id, event_type, payload)
                VALUES (?, ?, ?, '{}')
                """, tenant, employee, eventType);
    }

    private void insertBooking(String seatId, String dateExpression) {
        jdbc.update("""
                INSERT INTO booking (tenant_id, employee_id, travel_date, seat_id, status)
                VALUES ('tenant-booking', 'employee-booking', %s, ?, 'BOOKED')
                """.formatted(dateExpression), seatId);
    }

    private String statusFor(String seatId) {
        return jdbc.queryForObject("SELECT status FROM booking WHERE seat_id = ?", String.class, seatId);
    }
}
