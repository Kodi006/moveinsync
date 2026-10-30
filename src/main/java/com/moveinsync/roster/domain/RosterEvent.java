package com.moveinsync.roster.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable value object representing one inbound roster change.
 *
 * <p><strong>Java record</strong>: the compiler generates the canonical constructor,
 * accessor methods (named after the field, no "get" prefix), {@code equals},
 * {@code hashCode}, and {@code toString}.  In C++ this would be a struct.  The
 * <em>compact constructor</em> (no parameter list) runs before the auto-generated
 * field assignments, so we can normalise {@code payload} there.
 *
 * <p><strong>Why {@code HashMap} and not {@code Map.copyOf}?</strong><br>
 * {@code Map.copyOf} throws {@code NullPointerException} if any value is {@code null}.
 * We deliberately allow null values in the payload: a <em>present key with a null
 * value</em> means "clear this field", which must stay distinguishable from an
 * <em>absent key</em> ("don't touch this field").  Using {@code HashMap} preserves
 * that distinction while still defensive-copying so callers cannot mutate our state.
 */
public record RosterEvent(
        String tenantId,
        String employeeId,
        FieldOwner owner,
        int version,
        EventType eventType,
        Map<String, Object> payload
) {
    /** Compact constructor — normalises {@code payload} before field assignment. */
    public RosterEvent {
        payload = (payload == null) ? new HashMap<>() : new HashMap<>(payload);
    }
}
