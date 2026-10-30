package com.moveinsync.roster.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class IngestControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearEmployees() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM employee");
    }

    @Test
    void batchReturnsIndividualResultsAndPersistsValidRecords() throws Exception {
        IngestBatchRequest batch = new IngestBatchRequest(List.of(
                event("employee-1", Map.of("name", "One", "email", "one@example.test", "officeCode", "BLR")),
                event("employee-2", Map.of("name", "Two", "email", "two@example.test", "officeCode", "DEL")),
                event("employee-3", Map.of("name", "Three", "email", "three@example.test", "officeCode", "HYD")),
                event("employee-4", Map.of("name", "Bad Email", "email", "invalid", "officeCode", "BLR")),
                event("employee-5", Map.of("name", "Bad Office", "email", "five@example.test", "officeCode", "XXX"))));

        mvc.perform(post("/api/v1/roster/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(5))
                .andExpect(jsonPath("$.results[0].outcome").value("APPLY"))
                .andExpect(jsonPath("$.results[3].outcome").value("REJECTED"))
                .andExpect(jsonPath("$.results[3].reason").value("INVALID_EMAIL"))
                .andExpect(jsonPath("$.results[4].reason").value("UNKNOWN_OFFICE_CODE"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee", Integer.class)).isEqualTo(3);
    }

    private RosterEventRequest event(String employeeId, Map<String, Object> payload) {
        return new RosterEventRequest("tenant-api", employeeId, FieldOwner.HR,
                1, EventType.UPSERT, payload);
    }
}
