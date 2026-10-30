package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.ContentHash;
import com.moveinsync.roster.domain.Decision;
import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.EmployeeStatus;
import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.repo.EmployeeRepository;
import com.moveinsync.roster.validation.RecordValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private static final List<String> REQUIRED_COLUMNS =
            List.of("employeeId", "name", "email", "managerId", "officeCode");

    private final EmployeeRepository employees;
    private final RosterIngestService ingest;
    private final RecordValidator validator;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final double safetyBrakeThreshold;

    public ReconciliationService(EmployeeRepository employees, RosterIngestService ingest,
                                 RecordValidator validator, JdbcTemplate jdbc, Clock clock,
                                 @Value("${roster.reconciliation.safety-brake-threshold:0.30}")
                                 double safetyBrakeThreshold) {
        this.employees = employees;
        this.ingest = ingest;
        this.validator = validator;
        this.jdbc = jdbc;
        this.clock = clock;
        this.safetyBrakeThreshold = safetyBrakeThreshold;
    }

    /** Streams the export twice; no employee rows from the export are accumulated in memory. */
    public ReconciliationResult reconcile(String tenantId, Path exportFile) throws IOException {
        long runId = startRun(tenantId);
        // Only IDs and hashes are loaded, never full employee rows. HashMap overhead puts
        // 200k IDs in the tens-of-MB range; beyond roughly 10M employees use a sorted merge-join.
        Map<String, String> storedHashes = employees.loadContentHashMap(tenantId);
        ScanCounts counts = new ScanCounts(storedHashes.size());
        Set<String> seenIds = new HashSet<>();

        try {
            scan(exportFile, tenantId, counts, seenIds, row -> {
                if (row == null) return;
                String previousHash = storedHashes.get(row.employeeId());
                if (previousHash == null && !storedHashes.containsKey(row.employeeId())) {
                    counts.newCount++;
                } else if (row.contentHash().equals(previousHash)) {
                    counts.unchangedCount++;
                } else {
                    counts.changedCount++;
                }
            });
        } catch (IOException | RuntimeException failure) {
            finishRun(tenantId, runId, "FAILED", counts, 0, 0.0);
            throw failure;
        }

        Set<String> presentIds = seenIds;
        List<String> missingIds = storedHashes.keySet().stream()
                .filter(employeeId -> !presentIds.contains(employeeId))
                .toList();
        counts.missingCount = missingIds.size();
        counts.missingPercentage = counts.activeInStore == 0
                ? 0.0 : 100.0 * counts.missingCount / counts.activeInStore;

        if (counts.activeInStore > 0
                && ((double) counts.missingCount / counts.activeInStore) > safetyBrakeThreshold) {
            log.warn("Reconciliation blocked by safety brake for tenant {}: {} of {} active rows missing ({}%)",
                    tenantId, counts.missingCount, counts.activeInStore, counts.missingPercentage);
            saveBlockedCandidates(tenantId, runId, missingIds, storedHashes);
            finishRun(tenantId, runId, "BLOCKED_SAFETY_BRAKE", counts,
                    counts.missingCount, counts.missingPercentage);
            return counts.result(runId, "BLOCKED_SAFETY_BRAKE");
        }

        try {
            applyExport(tenantId, exportFile, storedHashes);
            deactivateMissing(tenantId, missingIds, storedHashes);
            finishRun(tenantId, runId, "COMPLETED", counts,
                    counts.missingCount, counts.missingPercentage);
            return counts.result(runId, "COMPLETED");
        } catch (IOException | RuntimeException failure) {
            finishRun(tenantId, runId, "FAILED", counts,
                    counts.missingCount, counts.missingPercentage);
            throw failure;
        }
    }

    private void saveBlockedCandidates(String tenantId, long runId, List<String> employeeIds,
                                       Map<String, String> storedHashes) {
        String sql = """
                INSERT INTO reconciliation_candidate
                    (tenant_id, run_id, employee_id, expected_hr_content_hash, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """;
        List<Object[]> batch = new ArrayList<>(1_000);
        for (String employeeId : employeeIds) {
            batch.add(new Object[]{tenantId, runId, employeeId, storedHashes.get(employeeId)});
            if (batch.size() == 1_000) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) jdbc.batchUpdate(sql, batch);
    }

    private long startRun(String tenantId) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO reconciliation_run (tenant_id, started_at, status)
                    VALUES (?, ?, 'IN_PROGRESS')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, tenantId);
            statement.setTimestamp(2, Timestamp.from(clock.instant()));
            return statement;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("Could not create reconciliation run");
        return id.longValue();
    }

    private void finishRun(String tenantId, long runId, String status, ScanCounts counts,
                           int missingCount, double missingPercentage) {
        jdbc.update("""
                UPDATE reconciliation_run
                   SET completed_at = CURRENT_TIMESTAMP, status = ?, total_rows = ?,
                       new_count = ?, changed_count = ?, unchanged_count = ?,
                       invalid_count = ?, missing_count = ?, active_in_store = ?, missing_pct = ?
                 WHERE tenant_id = ? AND id = ?
                """, status, counts.totalRows, counts.newCount, counts.changedCount,
                counts.unchangedCount, counts.invalidCount, missingCount,
                counts.activeInStore, missingPercentage, tenantId, runId);
    }

    private void scan(Path file, String tenantId, ScanCounts counts, Set<String> seenIds,
                      RowConsumer consumer) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            List<String> header = headerLine == null ? List.of() : parseCsv(headerLine);
            Map<String, Integer> columns = headerIndexes(header);
            String line;
            while ((line = reader.readLine()) != null) {
                counts.totalRows++;
                RosterRow row = parseRow(line, columns);
                if (row == null || blank(row.employeeId())) {
                    counts.invalidCount++;
                    continue;
                }
                if (!seenIds.add(row.employeeId())) {
                    counts.invalidCount++;
                    continue;
                }
                if (!isValid(tenantId, row)) {
                    counts.invalidCount++;
                    continue;
                }
                consumer.accept(withHash(row));
            }
        }
    }

    private Map<String, Integer> headerIndexes(List<String> header) throws IOException {
        Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < header.size(); i++) indexes.put(header.get(i).trim(), i);
        if (!indexes.keySet().containsAll(REQUIRED_COLUMNS)) {
            throw new IOException("Roster export header is invalid");
        }
        return indexes;
    }

    private RosterRow parseRow(String line, Map<String, Integer> columns) {
        try {
            List<String> cells = parseCsv(line);
            String employeeId = cell(cells, columns.get("employeeId"));
            String name = cell(cells, columns.get("name"));
            String email = cell(cells, columns.get("email"));
            String managerId = cell(cells, columns.get("managerId"));
            String officeCode = cell(cells, columns.get("officeCode"));
            Map<String, Object> payload = new HashMap<>();
            payload.put("name", emptyToNull(name));
            payload.put("email", emptyToNull(email));
            payload.put("managerId", emptyToNull(managerId));
            payload.put("officeCode", emptyToNull(officeCode));
            payload.put("status", EmployeeStatus.ACTIVE.name());
            return new RosterRow(employeeId, payload, null);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private boolean isValid(String tenantId, RosterRow row) {
        if (blank(row.employeeId()) || blank((String) row.payload().get("name"))) return false;
        return validator.validate(tenantId, row.employeeId(), FieldOwner.HR, 1,
                EventType.UPSERT, row.payload()).valid();
    }

    private RosterRow withHash(RosterRow row) {
        Map<String, Object> payload = row.payload();
        Employee draft = new Employee.Builder("reconciliation", row.employeeId())
                .name((String) payload.get("name"))
                .email((String) payload.get("email"))
                .managerId((String) payload.get("managerId"))
                .officeCode((String) payload.get("officeCode"))
                .status(EmployeeStatus.ACTIVE)
                .build();
        return new RosterRow(row.employeeId(), payload, ContentHash.compute(draft));
    }

    private void applyExport(String tenantId, Path file, Map<String, String> storedHashes) throws IOException {
        Set<String> appliedIds = new HashSet<>();
        ScanCounts ignoredCounts = new ScanCounts(storedHashes.size());
        scan(file, tenantId, ignoredCounts, appliedIds, row -> {
            if (row == null) return;
            boolean existed = storedHashes.containsKey(row.employeeId());
            String originalHash = storedHashes.get(row.employeeId());
            var current = employees.findById(tenantId, row.employeeId());
            if (existed) {
                if (current.isEmpty() || !java.util.Objects.equals(current.get().hrContentHash(), originalHash)) return;
                if (row.contentHash().equals(originalHash)) return;
            } else if (current.isPresent()) {
                return; // A concurrent insert won after the preflight scan.
            }
            int nextVersion = current.map(e -> e.hrVersion() + 1).orElse(1);
            Decision result = ingest.ingest(new RosterEvent(tenantId, row.employeeId(), FieldOwner.HR,
                    nextVersion, EventType.UPSERT, row.payload()));
            if (result == Decision.APPLY || result == Decision.DUPLICATE || result == Decision.STALE) return;
        });
    }

    private void deactivateMissing(String tenantId, List<String> missingIds,
                                   Map<String, String> storedHashes) {
        for (String employeeId : missingIds) {
            var current = employees.findById(tenantId, employeeId);
            if (current.isEmpty() || current.get().status() != EmployeeStatus.ACTIVE
                    || !java.util.Objects.equals(current.get().hrContentHash(), storedHashes.get(employeeId))) {
                continue; // A concurrent HR change makes the original absence decision stale.
            }
            ingest.ingest(new RosterEvent(tenantId, employeeId, FieldOwner.HR,
                    current.get().hrVersion() + 1, EventType.DEACTIVATE, Map.of()));
        }
    }

    private List<String> parseCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quoted field");
        cells.add(cell.toString());
        return cells;
    }

    private String cell(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index).trim() : "";
    }

    private String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(RosterRow row);
    }

    private record RosterRow(String employeeId, Map<String, Object> payload, String contentHash) {
    }

    private static final class ScanCounts {
        private final int activeInStore;
        private int totalRows;
        private int newCount;
        private int changedCount;
        private int unchangedCount;
        private int invalidCount;
        private int missingCount;
        private double missingPercentage;

        private ScanCounts(int activeInStore) { this.activeInStore = activeInStore; }

        private ReconciliationResult result(long id, String status) {
            return new ReconciliationResult(id, status, totalRows, newCount, changedCount,
                    unchangedCount, invalidCount, missingCount, activeInStore, missingPercentage);
        }
    }
}
