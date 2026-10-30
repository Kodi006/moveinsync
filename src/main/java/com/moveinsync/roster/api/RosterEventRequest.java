package com.moveinsync.roster.api;

import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;

import java.util.Map;

/** Nullable Integer lets the boundary distinguish a missing version from version zero. */
public record RosterEventRequest(String tenantId, String employeeId, FieldOwner owner,
                                 Integer version, EventType eventType, Map<String, Object> payload) {

    public RosterEvent toDomainEvent() {
        return new RosterEvent(tenantId, employeeId, owner, version, eventType, payload);
    }
}
