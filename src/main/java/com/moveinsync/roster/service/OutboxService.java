package com.moveinsync.roster.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.RosterEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OutboxService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Called inside EmployeeWriter's transaction so state and notification intent commit together. */
    public void enqueue(Employee before, Employee after, RosterEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("owner", event.owner().name());
        payload.put("version", event.version());
        payload.put("eventType", event.eventType().name());
        payload.put("before", before == null ? null : Map.of(
                "hrVersion", before.hrVersion(), "transportVersion", before.transportVersion(),
                "status", before.status().name()));
        payload.put("after", Map.of(
                "hrVersion", after.hrVersion(), "transportVersion", after.transportVersion(),
                "status", after.status().name()));
        try {
            jdbc.update("INSERT INTO outbox_event (tenant_id, employee_id, event_type, payload) VALUES (?, ?, ?, ?)",
                    event.tenantId(), event.employeeId(), event.eventType().name(),
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode outbox event", e);
        }
    }
}
