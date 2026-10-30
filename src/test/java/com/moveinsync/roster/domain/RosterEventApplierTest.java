package com.moveinsync.roster.domain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive unit tests for {@link RosterEventApplier}.
 *
 * <p>No Spring context, no database, no I/O.  {@code RosterEventApplier} is a pure
 * function, so each test follows: build inputs → call apply → assert outputs.
 * In C++ terms this is like testing a standalone algorithm with no global state.
 *
 * <p>These tests collectively verify:
 * <ul>
 *   <li>Version ordering (APPLY / DUPLICATE / STALE) per owner
 *   <li>Counter independence (HR and transport version counters never interfere)
 *   <li>Field ownership masking (unowned fields stripped, event still applies)
 *   <li>Null-value semantics (present-with-null clears; absent leaves unchanged)
 *   <li>Field preservation across owner boundaries
 *   <li>DEACTIVATE lifecycle operation
 *   <li>HR content hash stability and transport-write invariance
 * </ul>
 */
class RosterEventApplierTest {

    private final RosterEventApplier applier = new RosterEventApplier();

    // ── helpers ──────────────────────────────────────────────────────────

    private static RosterEvent hrUpsert(int version, Map<String, Object> payload) {
        return new RosterEvent("T1", "E1", FieldOwner.HR, version, EventType.UPSERT, payload);
    }

    private static RosterEvent transportUpsert(int version, Map<String, Object> payload) {
        return new RosterEvent("T1", "E1", FieldOwner.TRANSPORT, version, EventType.UPSERT, payload);
    }

    private static RosterEvent hrDeactivate(int version) {
        return new RosterEvent("T1", "E1", FieldOwner.HR, version, EventType.DEACTIVATE, new HashMap<>());
    }

    /** Creates a minimal active employee with the given version counters. */
    private static Employee emp(int hrV, int transV) {
        return new Employee.Builder("T1", "E1")
                .hrVersion(hrV)
                .transportVersion(transV)
                .status(EmployeeStatus.ACTIVE)
                .build();
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    void firstHrEvent_onNewEmployee_appliesAndSetsVersions() {
        var event = hrUpsert(1, Map.of("name", "Alice", "email", "alice@corp.com", "officeCode", "BLR"));

        var result = applier.apply(null, event);

        assertThat(result.decision()).isEqualTo(Decision.APPLY);              // 1
        assertThat(result.newState()).isNotNull();                            // 2
        assertThat(result.newState().hrVersion()).isEqualTo(1);              // 3
        assertThat(result.newState().transportVersion()).isEqualTo(0);       // 4 — transport untouched
        assertThat(result.newState().name()).isEqualTo("Alice");             // 5
    }

    @Test
    void replayHrEvent_sameVersion_isDuplicate() {
        var current = emp(1, 0);
        var event   = hrUpsert(1, Map.of("name", "Alice"));

        var result = applier.apply(current, event);

        assertThat(result.decision()).isEqualTo(Decision.DUPLICATE);         // 6
        // newState must be the identical reference — no object created on DUPLICATE
        assertThat(result.newState()).isSameAs(current);                      // 7
        assertThat(result.strippedFields()).isZero();                          // 8
    }

    @Test
    void olderHrVersion_isStale() {
        var current = emp(3, 0);
        var event   = hrUpsert(2, Map.of("name", "Old"));

        var result = applier.apply(current, event);

        assertThat(result.decision()).isEqualTo(Decision.STALE);             // 9
        assertThat(result.newState()).isSameAs(current);                     // 10
        assertThat(result.strippedFields()).isZero();                         // 11
    }

    @Test
    void versionGap_appliesNormally() {
        // Out-of-order delivery may leave gaps (e.g. version 2 is still in flight when
        // version 5 arrives).  "incoming > applied" covers all such gaps — the gap is not
        // treated as stale.
        var current = emp(1, 0);

        var result = applier.apply(current, hrUpsert(5, Map.of("name", "Bob")));

        assertThat(result.decision()).isEqualTo(Decision.APPLY);             // 12
        assertThat(result.newState().hrVersion()).isEqualTo(5);             // 13
    }

    @Test
    void hrAndTransportVersionCounters_areStrictlyIndependent() {
        // Scenario: hrVersion = 2, transportVersion = 3.
        var current = emp(2, 3);

        // Transport duplicate (version 3 == stored transport 3) must not affect HR.
        var r1 = applier.apply(current, transportUpsert(3, Map.of("pickupPoint", "Gate A")));
        assertThat(r1.decision()).isEqualTo(Decision.DUPLICATE);             // 14
        assertThat(r1.newState().hrVersion()).isEqualTo(2);                 // 15 — HR unchanged

        // HR event at version 3 (> stored HR 2) applies and must not touch transport.
        var r2 = applier.apply(current, hrUpsert(3, Map.of("name", "Bob")));
        assertThat(r2.decision()).isEqualTo(Decision.APPLY);                // 16
        assertThat(r2.newState().hrVersion()).isEqualTo(3);                 // 17
        assertThat(r2.newState().transportVersion()).isEqualTo(3);          // 18 — transport preserved
    }

    @Test
    void hrEvent_stripsTransportFields_eventStillApplies() {
        // Field ownership is masking: unowned fields are stripped and counted, but the
        // event is not rejected.  The HR sender should not be able to override transport
        // data even if they include those fields in the payload.
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "Carol");
        payload.put("pickupPoint", "Gate B");        // not HR-owned
        payload.put("specialAssistance", "Wheelchair"); // not HR-owned

        var result = applier.apply(null, hrUpsert(1, payload));

        assertThat(result.decision()).isEqualTo(Decision.APPLY);            // 19
        assertThat(result.strippedFields()).isEqualTo(2);                   // 20
        assertThat(result.newState().name()).isEqualTo("Carol");            // 21 — owned: applied
        assertThat(result.newState().pickupPoint()).isNull();               // 22 — stripped
        assertThat(result.newState().specialAssistance()).isNull();         // 23 — stripped
    }

    @Test
    void transportEvent_stripsHrFields_eventStillApplies() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("pickupPoint", "Gate C");
        payload.put("name", "Hacker");   // not transport-owned

        var result = applier.apply(null, transportUpsert(1, payload));

        assertThat(result.decision()).isEqualTo(Decision.APPLY);            // 24
        assertThat(result.strippedFields()).isEqualTo(1);                   // 25
        assertThat(result.newState().pickupPoint()).isEqualTo("Gate C");   // 26 — owned: applied
        assertThat(result.newState().name()).isNull();                      // 27 — stripped
    }

    @Test
    void hrEvent_preservesExistingTransportFields() {
        // An HR update must not clear transport fields that are not in its payload.
        var current = new Employee.Builder("T1", "E1")
                .hrVersion(1).transportVersion(2)
                .name("Alice").pickupPoint("Station X")
                .status(EmployeeStatus.ACTIVE)
                .build();

        var result = applier.apply(current, hrUpsert(2, Map.of("name", "Alice Updated")));

        assertThat(result.decision()).isEqualTo(Decision.APPLY);            // 28
        assertThat(result.newState().name()).isEqualTo("Alice Updated");   // 29
        assertThat(result.newState().pickupPoint()).isEqualTo("Station X");// 30 — preserved
    }

    @Test
    void transportEvent_preservesExistingHrFields() {
        var current = new Employee.Builder("T1", "E1")
                .hrVersion(2).transportVersion(1)
                .name("Bob").email("bob@corp.com")
                .status(EmployeeStatus.ACTIVE)
                .build();

        var result = applier.apply(current, transportUpsert(2, Map.of("pickupPoint", "Terminal 3")));

        assertThat(result.decision()).isEqualTo(Decision.APPLY);            // 31
        assertThat(result.newState().pickupPoint()).isEqualTo("Terminal 3");// 32
        assertThat(result.newState().name()).isEqualTo("Bob");             // 33 — preserved
        assertThat(result.newState().email()).isEqualTo("bob@corp.com");   // 34 — preserved
    }

    @Test
    void presentWithNullValue_clearsField() {
        // A present key with a null value means "clear this field".
        // This must stay distinguishable from an absent key ("don't touch this field").
        // RosterEvent uses HashMap (not Map.copyOf) precisely to preserve null values.
        var current = new Employee.Builder("T1", "E1")
                .hrVersion(1).name("Alice").email("alice@corp.com")
                .status(EmployeeStatus.ACTIVE)
                .build();

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", null);   // explicitly clear name
        // email is ABSENT — must not be touched

        var result = applier.apply(current, hrUpsert(2, payload));

        assertThat(result.decision()).isEqualTo(Decision.APPLY);            // 35
        assertThat(result.newState().name()).isNull();                      // 36 — cleared
        assertThat(result.newState().email()).isEqualTo("alice@corp.com"); // 37 — untouched (absent key)
    }

    @Test
    void absentKey_leavesFieldUntouched() {
        var current = new Employee.Builder("T1", "E1")
                .hrVersion(1).name("Dave").officeCode("DEL")
                .status(EmployeeStatus.ACTIVE)
                .build();

        // Empty payload: no HR fields should be changed.
        var result = applier.apply(current, hrUpsert(2, new HashMap<>()));

        assertThat(result.decision()).isEqualTo(Decision.APPLY);            // 38
        assertThat(result.newState().name()).isEqualTo("Dave");            // 39
        assertThat(result.newState().officeCode()).isEqualTo("DEL");       // 40
    }

    @Test
    void hrDeactivate_setsStatusInactive_preservesOtherFields() {
        var current = new Employee.Builder("T1", "E1")
                .hrVersion(1).name("Eve").email("eve@corp.com")
                .status(EmployeeStatus.ACTIVE)
                .build();

        var result = applier.apply(current, hrDeactivate(2));

        assertThat(result.decision()).isEqualTo(Decision.APPLY);                    // 41
        assertThat(result.newState().status()).isEqualTo(EmployeeStatus.INACTIVE); // 42
        assertThat(result.newState().name()).isEqualTo("Eve");                     // 43 — preserved
        assertThat(result.newState().hrVersion()).isEqualTo(2);                    // 44
    }

    @Test
    void transportCannotDeactivate_statusFieldStripped() {
        // Transport sends a payload that includes a status field — it should be stripped
        // (transport does not own status) and the employee must remain ACTIVE.
        var current = new Employee.Builder("T1", "E1")
                .hrVersion(1).transportVersion(1)
                .status(EmployeeStatus.ACTIVE)
                .build();

        Map<String, Object> payload = new HashMap<>();
        payload.put("pickupPoint", "Gate D");
        payload.put("status", "INACTIVE");  // transport does not own status

        var result = applier.apply(current, transportUpsert(2, payload));

        assertThat(result.decision()).isEqualTo(Decision.APPLY);                   // 45
        assertThat(result.newState().status()).isEqualTo(EmployeeStatus.ACTIVE);   // 46 — not cleared
        assertThat(result.strippedFields()).isEqualTo(1);                           // 47 — status stripped
    }

    @Test
    void hrContentHash_isComputedAfterApply_andIsStable() {
        var event  = hrUpsert(1, Map.of("name", "Alice", "email", "alice@corp.com"));
        var result = applier.apply(null, event);

        assertThat(result.newState().hrContentHash()).isNotBlank();                // 48
        // Recomputing from the result must give the same hash.
        assertThat(result.newState().hrContentHash())
                .isEqualTo(ContentHash.compute(result.newState()));               // 49
    }

    @Test
    void hrContentHash_isUnchangedByTransportUpdate() {
        // A transport-only update must not change the HR content fingerprint.
        // Reconciliation uses the hash to detect HR-field changes; transport updates
        // should be invisible to it.
        var hrResult    = applier.apply(null, hrUpsert(1, Map.of("name", "Alice")));
        String hashBefore = hrResult.newState().hrContentHash();

        var transResult = applier.apply(
                hrResult.newState(),
                transportUpsert(1, Map.of("pickupPoint", "Gate A")));

        assertThat(transResult.decision()).isEqualTo(Decision.APPLY);              // 50
        assertThat(transResult.newState().hrContentHash()).isEqualTo(hashBefore); // 51
    }
}
