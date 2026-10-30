package com.moveinsync.roster.domain;

/**
 * What the applier decided to do with one event.
 *
 * <p>APPLY     – incoming version > stored version; the update was or will be persisted.
 * DUPLICATE – incoming version == stored version; safe to skip (idempotent).
 * STALE     – incoming version < stored version; a later event already won; ignore.
 * REJECTED  – the event failed validation before reaching the applier (see Commit 4).
 */
public enum Decision {
    APPLY,
    DUPLICATE,
    STALE,
    REJECTED
}
