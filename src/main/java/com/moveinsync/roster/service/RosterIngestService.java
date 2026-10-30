package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.ApplyResult;
import com.moveinsync.roster.domain.Decision;
import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.domain.RosterEventApplier;
import com.moveinsync.roster.repo.EmployeeRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Ingest pipeline: re-reads current state, runs the pure applier, and persists under
 * optimistic concurrency.  On a lost CAS (another writer committed first) the loop
 * re-reads and re-runs the applier from scratch — it does <em>not</em> apply the event
 * to the newer row it just read.  Correctness lives in the applier, not the retry count.
 *
 * <p>EmployeeWriter is a separate Spring bean so persistence crosses its transaction
 * proxy; self-invocation would bypass Spring's proxy-based transaction handling.
 */
@Service
public class RosterIngestService {

    private static final int MAX_RETRIES = 100;

    private final EmployeeRepository repo;
    private final RosterEventApplier applier;
    private final EmployeeWriter writer;
    private final IngestMetrics metrics;

    public RosterIngestService(EmployeeRepository repo, RosterEventApplier applier,
                               EmployeeWriter writer, IngestMetrics metrics) {
        this.repo = repo;
        this.applier = applier;
        this.writer = writer;
        this.metrics = metrics;
    }

    // ─────────────────────────────────────────────────────────────────────

    /**
     * Processes one inbound event.
     *
     * @return the decision made for this event.
     * @throws MaxRetriesExceededException if write contention is too high.
     */
    public Decision ingest(RosterEvent event) {
        metrics.received(event);
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            Optional<Employee> current = repo.findById(event.tenantId(), event.employeeId());
            ApplyResult result = applier.apply(current.orElse(null), event);

            if (result.decision() != Decision.APPLY) {
                metrics.decision(result.decision(), event);
                return result.decision();
            }

            try {
                writer.persist(current.orElse(null), result.newState(), event);
                metrics.decision(Decision.APPLY, event);
                metrics.strippedFields(result.strippedFields(), event);
                return Decision.APPLY;
            } catch (CasRetryException e) {
                // CAS conflict: re-read on the next iteration.
                metrics.contention(event);
            } catch (RuntimeException failure) {
                metrics.error(event);
                throw failure;
            }
        }
        metrics.error(event);
        throw new MaxRetriesExceededException(event.tenantId(), event.employeeId());
    }

}
