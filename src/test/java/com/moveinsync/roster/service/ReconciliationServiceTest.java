package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReconciliationServiceTest {

    @Autowired private ReconciliationService reconciliation;
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
    void healthyExportCreatesAndUpdatesEmployees(@TempDir Path tempDir) throws Exception {
        ingest.ingest(hrEvent("emp-1", 1, "Old Name", "old@example.test"));
        ingest.ingest(hrEvent("emp-2", 1, "Same Name", "same@example.test"));
        Path export = tempDir.resolve("healthy.csv");
        Files.writeString(export, "employeeId,name,email,managerId,officeCode\n"
                + "emp-1,Updated Name,updated@example.test,,BLR\n"
                + "emp-2,Same Name,same@example.test,,BLR\n"
                + "emp-3,New Name,new@example.test,,DEL\n", StandardCharsets.UTF_8);

        ReconciliationResult result = reconciliation.reconcile("tenant-recon", export);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.newCount()).isEqualTo(1);
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(result.unchangedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE tenant_id = 'tenant-recon' AND status = 'ACTIVE'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT name FROM employee WHERE tenant_id = 'tenant-recon' AND employee_id = 'emp-1'", String.class)).isEqualTo("Updated Name");
    }

    @Test
    void truncatedExportBlocksAndDeactivatesNobody(@TempDir Path tempDir) throws Exception {
        seedActiveEmployees(200_000);
        Path export = tempDir.resolve("truncated.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(export, StandardCharsets.UTF_8)) {
            writer.write("employeeId,name,email,managerId,officeCode\n");
            for (int i = 0; i < 120_000; i++) {
                writer.write("emp-%06d,Employee %d,employee%d@example.test,,BLR%n".formatted(i, i, i));
            }
        }

        ReconciliationResult result = reconciliation.reconcile("tenant-large", export);

        assertThat(result.status()).isEqualTo("BLOCKED_SAFETY_BRAKE");
        assertThat(result.totalRows()).isEqualTo(120_000);
        assertThat(result.activeInStore()).isEqualTo(200_000);
        assertThat(result.missingCount()).isEqualTo(80_000);
        assertThat(result.missingPercentage()).isEqualTo(40.0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE tenant_id = 'tenant-large' AND status = 'INACTIVE'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT missing_count FROM reconciliation_run WHERE id = ?", Integer.class, result.runId())).isEqualTo(80_000);
    }

    private void seedActiveEmployees(int count) {
        for (int start = 0; start < count; start += 1_000) {
            int end = Math.min(start + 1_000, count);
            Object[][] batch = new Object[end - start][9];
            for (int i = start; i < end; i++) {
                batch[i - start] = new Object[]{"tenant-large", "emp-%06d".formatted(i), 1, 0,
                        "Employee " + i, "employee" + i + "@example.test", "BLR", "ACTIVE", "stored-hash"};
            }
            jdbc.batchUpdate("""
                    INSERT INTO employee (tenant_id, employee_id, hr_version, transport_version,
                                          name, email, office_code, status, hr_content_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, java.util.Arrays.asList(batch));
        }
    }

    private RosterEvent hrEvent(String employeeId, int version, String name, String email) {
        return new RosterEvent("tenant-recon", employeeId, FieldOwner.HR, version,
                EventType.UPSERT, Map.of("name", name, "email", email, "officeCode", "BLR"));
    }
}
