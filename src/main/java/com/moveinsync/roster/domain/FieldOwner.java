package com.moveinsync.roster.domain;

import java.util.Set;

/**
 * Which organisational unit owns which employee fields.
 *
 * <p>Field ownership is <em>masking</em>, not rejection: if an event sender includes a
 * field they do not own, the applier strips that field silently and counts it, but the
 * event still applies.  This means:
 * <ul>
 *   <li>An HR sender cannot clear a transport field by omitting it or setting it null.
 *   <li>A transport sender cannot alter an employee's name, email, or employment status.
 * </ul>
 *
 * <p>Java enums can have fields and methods — unlike C++ enums, which are just integers.
 * The {@code ownedFields()} method returns the appropriate constant set without a switch
 * in the caller.
 */
public enum FieldOwner {

    HR,
    TRANSPORT;

    /** Fields that the HR system is allowed to write. */
    public static final Set<String> HR_FIELDS =
            Set.of("name", "email", "managerId", "officeCode", "status");

    /** Fields that the transport administration system is allowed to write. */
    public static final Set<String> TRANSPORT_FIELDS =
            Set.of("pickupPoint", "specialAssistance");

    /** Returns the set of payload keys this owner is permitted to write. */
    public Set<String> ownedFields() {
        // Java 14+ switch expression — returns a value, no fall-through.
        // In C++ you'd write a switch with return statements or a lookup table.
        return switch (this) {
            case HR        -> HR_FIELDS;
            case TRANSPORT -> TRANSPORT_FIELDS;
        };
    }
}
