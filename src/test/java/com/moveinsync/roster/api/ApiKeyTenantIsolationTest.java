package com.moveinsync.roster.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.service.RosterIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiKeyTenantIsolationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RosterIngestService ingest;

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM reconciliation_candidate");
        jdbc.update("DELETE FROM reconciliation_run");
        jdbc.update("DELETE FROM booking");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM employee");
    }

    @Test
    void tenantASeesNotFoundForTenantBEmployeeAndContextIsCleared() throws Exception {
        ingest.ingest(new RosterEvent("tenant-b", "shared-id", FieldOwner.HR, 1,
                EventType.UPSERT, Map.of("name", "Tenant B", "email", "b@example.test", "officeCode", "BLR")));

        mvc.perform(get("/api/v1/employees/shared-id").header("X-API-Key", "tenant-a-admin"))
                .andExpect(status().isNotFound());

        assertThat(TenantContext.current()).isNull();
    }

    @Test
    void invalidApiKeyIsUnauthorizedAndCrossTenantIngestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/employees/any-id").header("X-API-Key", "unknown-key"))
                .andExpect(status().isUnauthorized());

        IngestBatchRequest request = new IngestBatchRequest(List.of(
                new RosterEventRequest("tenant-b", "foreign-employee", FieldOwner.HR, 1,
                        EventType.UPSERT, Map.of("name", "Foreign", "email", "f@example.test", "officeCode", "BLR"))));
        mvc.perform(post("/api/v1/roster/events")
                        .header("X-API-Key", "tenant-a-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("REJECTED"))
                .andExpect(jsonPath("$.results[0].reason").value("TENANT_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE tenant_id = 'tenant-b' AND employee_id = 'foreign-employee'", Integer.class)).isZero();
    }
}
