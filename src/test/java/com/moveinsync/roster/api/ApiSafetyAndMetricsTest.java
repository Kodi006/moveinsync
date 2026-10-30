package com.moveinsync.roster.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.service.IngestMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiSafetyAndMetricsTest {

    private static final String NAME_SENTINEL = "NAME_SENTINEL_X91Q";
    private static final String EMAIL_SENTINEL = "EMAIL_SENTINEL_X91Q_INVALID";
    private static final String PICKUP_SENTINEL = "PICKUP_SENTINEL_X91Q";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IngestMetrics metrics;

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM reconciliation_candidate");
        jdbc.update("DELETE FROM reconciliation_run");
        jdbc.update("DELETE FROM booking");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM employee");
    }

    @Test
    void validationSentinelsAreAbsentFromResponseAndLogs(CapturedOutput output) throws Exception {
        RosterEventRequest invalid = new RosterEventRequest("tenant-api", "pii-test", FieldOwner.HR, 1,
                EventType.UPSERT, Map.of("name", NAME_SENTINEL, "email", EMAIL_SENTINEL,
                        "pickupPoint", PICKUP_SENTINEL, "officeCode", "BLR"));
        var response = mvc.perform(post("/api/v1/roster/events")
                        .header("X-API-Key", "api-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new IngestBatchRequest(List.of(invalid)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("INVALID_EMAIL");
        assertThat(response).doesNotContain(NAME_SENTINEL, EMAIL_SENTINEL, PICKUP_SENTINEL);
        assertThat(output.toString()).doesNotContain(NAME_SENTINEL, EMAIL_SENTINEL, PICKUP_SENTINEL);
    }

    @Test
    void prometheusExposesRosterOperationalMetrics() throws Exception {
        RosterEvent event = new RosterEvent("tenant-metrics", "metrics-id", FieldOwner.HR, 1,
                EventType.UPSERT, Map.of("name", "Metrics", "email", "metrics@example.test", "officeCode", "BLR"));
        metrics.error(event);
        metrics.contention(event);
        metrics.rejected("tenant-metrics", FieldOwner.HR);

        String exposition = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(exposition).contains("roster_outbox_depth", "roster_outbox_event_lag_seconds",
                "roster_ingest_errors_total", "roster_events_rejected_total",
                "roster_events_contention_total", "roster_reconciliation_duration_seconds_count",
                "roster_reconciliation_missing_percentage");
    }
}
