package com.moveinsync.roster.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.service.ReconciliationResult;
import com.moveinsync.roster.service.ReconciliationService;
import com.moveinsync.roster.service.RosterIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationApprovalControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RosterIngestService ingest;
    @Autowired private ReconciliationService reconciliation;

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM reconciliation_candidate");
        jdbc.update("DELETE FROM reconciliation_run");
        jdbc.update("DELETE FROM booking");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM employee");
    }

    @Test
    void blockedRunWaitsForApprovalAndThenDeactivatesItsCandidates(@TempDir Path tempDir) throws Exception {
        for (int i = 1; i <= 3; i++) {
            ingest.ingest(new RosterEvent("tenant-approval", "emp-" + i, FieldOwner.HR,
                    1, EventType.UPSERT, Map.of("name", "Employee " + i,
                    "email", "employee" + i + "@example.test", "officeCode", "BLR")));
        }
        Path export = tempDir.resolve("short.csv");
        Files.writeString(export, "employeeId,name,email,managerId,officeCode\n"
                + "emp-1,Employee 1,employee1@example.test,,BLR\n");
        ReconciliationResult run = reconciliation.reconcile("tenant-approval", export);
        assertThat(run.status()).isEqualTo("BLOCKED_SAFETY_BRAKE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE tenant_id = 'tenant-approval' AND status = 'INACTIVE'", Integer.class)).isZero();

        mvc.perform(get("/api/v1/reconciliation/runs").header("X-API-Key", "approval-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("BLOCKED_SAFETY_BRAKE"));

        mvc.perform(post("/api/v1/reconciliation/runs/{id}/approve", run.runId())
                        .header("X-API-Key", "approval-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApprovalRequest("admin-1"))))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM reconciliation_run WHERE id = ?", String.class, run.runId()))
                .isEqualTo("BLOCKED_SAFETY_BRAKE");

        mvc.perform(post("/api/v1/reconciliation/runs/{id}/approve", run.runId())
                        .header("X-API-Key", "approval-operator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApprovalRequest("operator-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deactivatedCount").value(2))
                .andExpect(jsonPath("$.skippedCount").value(0));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE tenant_id = 'tenant-approval' AND status = 'INACTIVE'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT approved_by FROM reconciliation_run WHERE tenant_id = 'tenant-approval' AND id = ?", String.class, run.runId())).isEqualTo("operator-1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_candidate WHERE tenant_id = 'tenant-approval' AND run_id = ? AND status = 'DEACTIVATED'", Integer.class, run.runId())).isEqualTo(2);
    }
}
