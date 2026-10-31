package com.moveinsync.roster.domain;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Pure function: given the current persisted {@link Employee} (or {@code null} for a new
 * employee) and an inbound {@link RosterEvent}, computes what should happen next and
 * produces the resulting {@link Employee} state if the decision is APPLY.
 *
 * <p><strong>No Spring annotations.  No database.  No clock.  No logging.</strong><br>
 * This is the main demo artefact.  It can be instantiated with {@code new} and tested
 * exhaustively without any infrastructure.  In C++ terms it is a free function wrapped
 * in a class because Java has no free functions.
 *
 * <p><strong>Idempotency is version-based and durable:</strong>
 * <pre>
 *   incoming &gt; applied  → APPLY
 *   incoming == applied → DUPLICATE (safe no-op)
 *   incoming &lt; applied  → STALE (ignore)
 * </pre>
 * The idempotency key is {@code (tenantId, employeeId, owner, version)}.  There is no
 * in-memory cache of seen event IDs — a cache would empty on restart and is not shared
 * across instances, so duplicates would be reapplied after a crash.  Caching is a
 * read-path optimisation only; removing it must change <em>performance</em> and never
 * <em>correctness</em>.
 *
 * <p><strong>Optimistic concurrency:</strong> on a lost CAS the caller re-reads the DB
 * and calls this method again with the fresh state.  This class is therefore called at
 * least once per successful write and potentially more times on contention — it must be
 * cheap and side-effect-free.
 */
public class RosterEventApplier {

    /**
     * Applies {@code event} to {@code current}.
     *
     * @param current the current persisted employee, or {@code null} if no row exists yet.
     * @param event   the inbound event.
     * @return an {@link ApplyResult} with the decision and (when APPLY) the new state.
     */
    public ApplyResult apply(Employee current, RosterEvent event) {
        int appliedVersion  = resolveVersion(current, event.owner());
        int incomingVersion = event.version();

        if (incomingVersion < appliedVersion) {
            return new ApplyResult(Decision.STALE, current, 0);
        }
        if (incomingVersion == appliedVersion) {
            return new ApplyResult(Decision.DUPLICATE, current, 0);
        }
        // incomingVersion > appliedVersion → APPLY

        // Strip fields the sender does not own.  The event still applies; stripping is
        // masking, not rejection.  We count stripped fields for metrics (Commit 2).
        Map<String, Object> maskedPayload = new HashMap<>(event.payload());
        Set<String> allowed = event.owner().ownedFields();
        int stripped = 0;
        Iterator<String> it = maskedPayload.keySet().iterator();
        while (it.hasNext()) {
            if (!allowed.contains(it.next())) {
                it.remove();
                stripped++;
            }
        }

        Employee newState = buildNewState(current, event, maskedPayload);
        return new ApplyResult(Decision.APPLY, newState, stripped);
    }

    // ─────────────────────────────────────────────────────────────────────

    private int resolveVersion(Employee current, FieldOwner owner) {
        if (current == null) return 0;   // no row yet; treat stored version as 0
        // The two version counters are independent.  Each owner's counter advances
        // only when that owner writes — a transport write must never advance hrVersion.
        return (owner == FieldOwner.HR) ? current.hrVersion() : current.transportVersion();
    }

    private Employee buildNewState(Employee current, RosterEvent event,
                                   Map<String, Object> maskedPayload) {
        // Start from a blank builder (new employee) or a copy of the existing row.
        // Builder.from is a copy-constructor: all current fields are preserved;
        // the caller then overwrites only the fields affected by this event.
        Employee.Builder b = (current == null)
                ? new Employee.Builder(event.tenantId(), event.employeeId())
                : Employee.Builder.from(current);

        // Advance ONLY the counter owned by this sender.
        // Mixing the counters would silently drop in-flight updates from the other owner.
        if (event.owner() == FieldOwner.HR) {
            b.hrVersion(event.version());
        } else {
            b.transportVersion(event.version());
        }

        // DEACTIVATE is an HR-only lifecycle operation.
        // Transport cannot change employment status — the status field is stripped by
        // the masking step above, so this branch only fires for HR DEACTIVATE events.
        if (event.eventType() == EventType.DEACTIVATE && event.owner() == FieldOwner.HR) {
            b.status(EmployeeStatus.INACTIVE);
        }

        // Apply payload fields.  Only keys *present* in the masked payload are touched:
        //   absent key          → field left unchanged  ("don't touch this")
        //   present, non-null   → field overwritten     ("set to this value")
        //   present, null value → field cleared         ("clear this field")
        applyPayloadFields(b, maskedPayload);

        Employee draft = b.build();
        // Recompute the HR content hash after every write.  A transport-only update
        // leaves the HR fields unchanged, so the hash recomputes to the same value —
        // this is intentional: the hash must always reflect the current HR state.
        return Employee.Builder.from(draft)
                .hrContentHash(ContentHash.compute(draft))
                .build();
    }

    private void applyPayloadFields(Employee.Builder b, Map<String, Object> payload) {
        // containsKey distinguishes "key absent" from "key present with null value".
        // We cannot use getOrDefault here because default would hide the null case.
        if (payload.containsKey("name"))              b.name(asString(payload.get("name")));
        if (payload.containsKey("email"))             b.email(asString(payload.get("email")));
        if (payload.containsKey("managerId"))         b.managerId(asString(payload.get("managerId")));
        if (payload.containsKey("officeCode"))        b.officeCode(asString(payload.get("officeCode")));
        if (payload.containsKey("status")) {
            Object v = payload.get("status");
            // EmployeeStatus.valueOf parses the string name of the enum constant.
            // In C++ terms: like casting an int to an enum after checking the range.
            b.status(v == null ? null : EmployeeStatus.valueOf(v.toString()));
        }
        if (payload.containsKey("pickupPoint"))       b.pickupPoint(asString(payload.get("pickupPoint")));
        if (payload.containsKey("specialAssistance")) b.specialAssistance(asString(payload.get("specialAssistance")));
    }

    /**
     * Coerces a payload value to {@code String} rather than hard-casting.
     *
     * <p>JSON deserialisation produces {@code Map<String, Object>} where a field the
     * client sent as a boolean or number arrives as {@code Boolean} or {@code Number},
     * not {@code String}.  A hard cast would throw {@link ClassCastException} and turn
     * a valid request into a {@code PROCESSING_ERROR}.  {@code toString()} handles every
     * non-null type correctly.  Null is preserved: a present-with-null value means
     * "clear this field" and must stay distinguishable from an absent key.
     */
    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
