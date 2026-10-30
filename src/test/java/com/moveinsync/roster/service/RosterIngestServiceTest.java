package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.Decision;
import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.repo.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RosterIngestServiceTest {

    @Autowired private RosterIngestService ingest;
    @Autowired private EmployeeRepository employees;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM employee");
    }

    @Test
    void appliesAndPersistsEmployee() {
        assertThat(ingest.ingest(hrEvent(1, "Asha"))).isEqualTo(Decision.APPLY);
        Employee stored = employees.findById("tenant-1", "employee-1").orElseThrow();
        assertThat(stored.name()).isEqualTo("Asha");
        assertThat(stored.hrVersion()).isEqualTo(1);
    }

    @Test
    void replayIsNoOp() {
        RosterEvent event = hrEvent(1, "Asha");
        assertThat(ingest.ingest(event)).isEqualTo(Decision.APPLY);
        assertThat(ingest.ingest(event)).isEqualTo(Decision.DUPLICATE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void staleEventLeavesStoredStateUnchanged() {
        ingest.ingest(hrEvent(2, "Latest"));
        assertThat(ingest.ingest(hrEvent(1, "Old"))).isEqualTo(Decision.STALE);
        assertThat(employees.findById("tenant-1", "employee-1").orElseThrow().name()).isEqualTo("Latest");
    }

    @Test
    void appliedChangeWritesExactlyOneOutboxRow() {
        assertThat(ingest.ingest(hrEvent(1, "Asha"))).isEqualTo(Decision.APPLY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(1);
    }

    private RosterEvent hrEvent(int version, String name) {
        return new RosterEvent("tenant-1", "employee-1", FieldOwner.HR, version,
                EventType.UPSERT, Map.of("name", name, "email", "asha@example.test"));
    }
}
