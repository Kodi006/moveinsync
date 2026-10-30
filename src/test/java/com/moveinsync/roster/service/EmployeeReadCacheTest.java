package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EmployeeReadCacheTest {

    @Autowired private EmployeeReadService reads;
    @Autowired private RosterIngestService ingest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM reconciliation_candidate");
        jdbc.update("DELETE FROM reconciliation_run");
        jdbc.update("DELETE FROM booking");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM employee");
    }

    @Test
    void sameInstanceWriteEvictsCachedReadAfterCommit() {
        ingest.ingest(event(1, "Old Name"));
        assertThat(reads.findById("tenant-cache", "employee-cache").orElseThrow().name()).isEqualTo("Old Name");

        ingest.ingest(event(2, "New Name"));

        assertThat(reads.findById("tenant-cache", "employee-cache").orElseThrow().name()).isEqualTo("New Name");
    }

    private RosterEvent event(int version, String name) {
        return new RosterEvent("tenant-cache", "employee-cache", FieldOwner.HR, version,
                EventType.UPSERT, Map.of("name", name, "email", "cache@example.test", "officeCode", "BLR"));
    }
}
