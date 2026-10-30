package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.Decision;
import com.moveinsync.roster.domain.EmployeeStatus;
import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.repo.EmployeeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ReconciliationApprovalService {

    private final JdbcTemplate jdbc;
    private final EmployeeRepository employees;
    private final RosterIngestService ingest;

    public ReconciliationApprovalService(JdbcTemplate jdbc, EmployeeRepository employees,
                                         RosterIngestService ingest) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.ingest = ingest;
    }

    public List<ReconciliationRunView> listRuns(String tenantId) {
        return jdbc.query("""
                        SELECT id, tenant_id, started_at, completed_at, status, total_rows,
                               new_count, changed_count, unchanged_count, invalid_count,
                               missing_count, active_in_store, missing_pct, approved_by
                          FROM reconciliation_run
                         WHERE tenant_id = ?
                         ORDER BY started_at DESC, id DESC
                         LIMIT 100
                        """,
                (rs, rowNum) -> new ReconciliationRunView(
                        rs.getLong("id"), rs.getString("tenant_id"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                        rs.getString("status"), rs.getObject("total_rows", Integer.class),
                        rs.getObject("new_count", Integer.class), rs.getObject("changed_count", Integer.class),
                        rs.getObject("unchanged_count", Integer.class), rs.getObject("invalid_count", Integer.class),
                        rs.getObject("missing_count", Integer.class), rs.getObject("active_in_store", Integer.class),
                        rs.getBigDecimal("missing_pct"), rs.getString("approved_by")),
                tenantId);
    }

    @Transactional
    public ApprovalResult approve(String tenantId, long runId, String operatorId) {
        if (tenantId == null || tenantId.isBlank() || operatorId == null || operatorId.isBlank()) {
            throw new RunNotApprovableException();
        }
        int claimed = jdbc.update("""
                UPDATE reconciliation_run
                   SET approved_by = ?, approved_at = CURRENT_TIMESTAMP, status = 'APPROVED'
                 WHERE tenant_id = ? AND id = ? AND status = 'BLOCKED_SAFETY_BRAKE'
                   AND approved_by IS NULL
                """, operatorId, tenantId, runId);
        if (claimed != 1) throw new RunNotApprovableException();

        List<Candidate> candidates = jdbc.query("""
                        SELECT employee_id, expected_hr_content_hash
                          FROM reconciliation_candidate
                         WHERE tenant_id = ? AND run_id = ? AND status = 'PENDING'
                         ORDER BY employee_id
                        """,
                (rs, rowNum) -> new Candidate(rs.getString("employee_id"),
                        rs.getString("expected_hr_content_hash")), tenantId, runId);

        int deactivated = 0;
        int skipped = 0;
        for (Candidate candidate : candidates) {
            var current = employees.findById(tenantId, candidate.employeeId());
            String candidateStatus;
            if (current.isPresent()
                    && current.get().status() == EmployeeStatus.ACTIVE
                    && Objects.equals(current.get().hrContentHash(), candidate.expectedHash())) {
                Decision result = ingest.ingest(new RosterEvent(tenantId, candidate.employeeId(),
                        FieldOwner.HR, current.get().hrVersion() + 1, EventType.DEACTIVATE, Map.of()));
                if (result == Decision.APPLY) {
                    deactivated++;
                    candidateStatus = "DEACTIVATED";
                } else {
                    skipped++;
                    candidateStatus = "SKIPPED_CHANGED";
                }
            } else {
                skipped++;
                candidateStatus = "SKIPPED_CHANGED";
            }
            jdbc.update("""
                    UPDATE reconciliation_candidate
                       SET status = ?
                     WHERE tenant_id = ? AND run_id = ? AND employee_id = ? AND status = 'PENDING'
                    """, candidateStatus,
                    tenantId, runId, candidate.employeeId());
        }
        return new ApprovalResult(runId, deactivated, skipped);
    }

    private record Candidate(String employeeId, String expectedHash) { }

    public record ApprovalResult(long runId, int deactivatedCount, int skippedCount) { }

    public record ReconciliationRunView(long id, String tenantId, java.time.Instant startedAt,
                                       java.time.Instant completedAt, String status, Integer totalRows,
                                       Integer newCount, Integer changedCount, Integer unchangedCount,
                                       Integer invalidCount, Integer missingCount, Integer activeInStore,
                                       java.math.BigDecimal missingPercentage, String approvedBy) { }

    public static class RunNotApprovableException extends RuntimeException {
        public RunNotApprovableException() { super("Run cannot be approved"); }
    }
}
