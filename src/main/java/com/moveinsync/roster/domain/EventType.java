package com.moveinsync.roster.domain;

/**
 * The semantic intent of an inbound event.
 *
 * <p>UPSERT: create or update the employee record.
 * DEACTIVATE: set the employee status to INACTIVE.  Only HR may issue this.
 */
public enum EventType {
    UPSERT,
    DEACTIVATE
}
