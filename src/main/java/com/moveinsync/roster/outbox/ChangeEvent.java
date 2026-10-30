package com.moveinsync.roster.outbox;

/** Event reconstructed from a durable outbox row. */
public record ChangeEvent(long id, String tenantId, String employeeId,
                          String eventType, String payload) {
}
