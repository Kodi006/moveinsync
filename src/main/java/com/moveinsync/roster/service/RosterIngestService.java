package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.ApplyResult;
import com.moveinsync.roster.domain.Decision;
import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.domain.RosterEventApplier;
import com.moveinsync.roster.repo.EmployeeRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Ingest pipeline: re-reads current state, runs the pure applier, and persists under
 * optimistic concurrency. On a lost CAS (another writer committed first) the loop
 * re-reads and re-runs the applier from scratch.
 */
@Service
public class RosterIngestService {

    private static final int MAX_RETRIES = 5;

    private final EmployeeRepository repo;
    private final RosterEventApplier applier = new RosterEventApplier();

    public RosterIngestService(EmployeeRepository repo) {
        this.repo = repo;
    }

    public Decision ingest(RosterEvent event) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            Optional<Employee> current = repo.findById(event.tenantId(), event.employeeId());
            ApplyResult result = applier.apply(current.orElse(null), event);
            if (result.decision() != Decision.APPLY) {
                return result.decision();
            }
            try {
                persist(current.orElse(null), result.newState(), event);
                return Decision.APPLY;
            } catch (CasRetryException e) {
                // Re-read and recompute on the next iteration.
            }
        }
        throw new MaxRetriesExceededException(event.tenantId(), event.employeeId());
    }

    /**
     * TODO Commit 2: this protected same-class call bypasses Spring's transaction proxy.
     * Extract to EmployeeWriter so employee and outbox writes share a transaction.
     */
    @Transactional
    protected void persist(Employee current, Employee newState, RosterEvent event) {
        if (current == null) {
            try {
                repo.insert(newState);
            } catch (DuplicateKeyException e) {
                throw new CasRetryException();
            }
        } else if (!repo.casUpdate(current, newState)) {
            throw new CasRetryException();
        }
    }
}
