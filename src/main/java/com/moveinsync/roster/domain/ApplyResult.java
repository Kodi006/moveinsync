package com.moveinsync.roster.domain;

/**
 * The outcome of one call to {@link RosterEventApplier#apply}.
 *
 * <p>Java record: immutable value type; compiler generates constructor, accessors,
 * {@code equals}, {@code hashCode}, and {@code toString}.  In C++ this is a struct.
 *
 * @param decision       What the applier decided.
 * @param newState       The resulting {@link Employee} after the event was applied
 *                       (if {@code decision == APPLY}); the unchanged original otherwise.
 *                       Never {@code null}: DUPLICATE and STALE return the {@code current}
 *                       reference passed in.
 * @param strippedFields Number of payload keys discarded because the sender did not
 *                       own them.  Zero for DUPLICATE / STALE.
 */
public record ApplyResult(Decision decision, Employee newState, int strippedFields) {}
