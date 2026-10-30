package com.moveinsync.roster.service;

import com.moveinsync.roster.outbox.ChangeEvent;
import com.moveinsync.roster.outbox.ChangeEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DeactivationHandler {

    private final JdbcTemplate jdbc;

    public DeactivationHandler(JdbcTemplate jdbc, ChangeEventPublisher publisher) {
        this.jdbc = jdbc;
        // Booking cleanup is driven by the committed event so roster ingest stays focused
        // on employee state and does not couple its transaction to booking workflows.
        publisher.addListener(this::onChange);
    }

    private void onChange(ChangeEvent event) {
        if (!"DEACTIVATE".equals(event.eventType())) {
            return;
        }
        // A CANCELLED booking releases its seat; past and same-day travel are untouched.
        jdbc.update("""
                UPDATE booking
                   SET status = 'CANCELLED'
                 WHERE tenant_id = ?
                   AND employee_id = ?
                   AND travel_date > CURRENT_DATE
                   AND status = 'BOOKED'
                """, event.tenantId(), event.employeeId());
    }
}
