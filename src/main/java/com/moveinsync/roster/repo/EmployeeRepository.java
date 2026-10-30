package com.moveinsync.roster.repo;

import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.EmployeeStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin data-access layer for the {@code employee} table.
 *
 * <p><strong>Tenant isolation by construction:</strong> every public method takes
 * {@code tenantId} as a leading parameter, and every SQL statement puts
 * {@code tenant_id} first in the WHERE clause.  There is no method that reads across
 * tenants.
 *
 * <p>{@link JdbcTemplate} is Spring's thin JDBC wrapper: it acquires a connection from
 * the pool, runs the query, maps the ResultSet, releases the connection, and translates
 * checked {@link java.sql.SQLException} into unchecked Spring exceptions.  In C++ you
 * would do all that manually or use a library like SOCI.
 */
@Repository
public class EmployeeRepository {

    /**
     * RowMapper is a {@code @FunctionalInterface}: one method {@code (rs, rowNum) -> T}.
     * Written as a lambda; in C++ this would be a lambda returning a struct.
     *
     * <p>Declared static so it is shared across all instances of this class rather than
     * allocated once per injection.
     */
    private static final RowMapper<Employee> ROW_MAPPER = (rs, rowNum) -> {
        String statusStr = rs.getString("status");
        return new Employee.Builder(
                rs.getString("tenant_id"),
                rs.getString("employee_id"))
                .hrVersion(rs.getInt("hr_version"))
                .transportVersion(rs.getInt("transport_version"))
                .name(rs.getString("name"))
                .email(rs.getString("email"))
                .managerId(rs.getString("manager_id"))
                .officeCode(rs.getString("office_code"))
                .status(statusStr != null
                        ? EmployeeStatus.valueOf(statusStr)
                        : EmployeeStatus.ACTIVE)
                .pickupPoint(rs.getString("pickup_point"))
                .specialAssistance(rs.getString("special_assistance"))
                .hrContentHash(rs.getString("hr_content_hash"))
                .build();
    };

    private final JdbcTemplate jdbc;

    /**
     * Constructor injection: preferred over {@code @Autowired} field injection because
     * it makes dependencies explicit and the class testable without a Spring context.
     */
    public EmployeeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─────────────────────────────────────────────────────────────────────

    /**
     * Fetches one employee by primary key.
     *
     * <p>{@code tenant_id} always leads the WHERE clause — tenant isolation.
     *
     * @return the employee wrapped in {@code Optional}, or {@code Optional.empty()} if
     *         not found.  ({@code Optional<T>} is Java's equivalent of
     *         {@code std::optional<T>}.)
     */
    public Optional<Employee> findById(String tenantId, String employeeId) {
        List<Employee> rows = jdbc.query(
                "SELECT * FROM employee WHERE tenant_id = ? AND employee_id = ?",
                ROW_MAPPER,
                tenantId, employeeId);
        return rows.stream().findFirst();
    }

    /**
     * Inserts a new employee row.
     *
     * <p>Callers catch {@link org.springframework.dao.DuplicateKeyException} as a signal
     * that a concurrent insert won the race — treat it the same as a lost CAS.
     */
    public void insert(Employee emp) {
        jdbc.update("""
                INSERT INTO employee
                    (tenant_id, employee_id, hr_version, transport_version,
                     name, email, manager_id, office_code, status,
                     pickup_point, special_assistance, hr_content_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                emp.tenantId(), emp.employeeId(),
                emp.hrVersion(), emp.transportVersion(),
                emp.name(), emp.email(), emp.managerId(), emp.officeCode(),
                emp.status() != null ? emp.status().name() : EmployeeStatus.ACTIVE.name(),
                emp.pickupPoint(), emp.specialAssistance(), emp.hrContentHash());
    }

    /**
     * Optimistic-concurrency update (compare-and-swap).
     *
     * <p>Both {@code hr_version} and {@code transport_version} appear in the WHERE
     * clause, making the <em>entire row</em> the CAS unit.  If another writer updated
     * the row between our read and this write, the WHERE clause matches zero rows and
     * this method returns {@code false}.  The caller re-reads and retries.
     *
     * <p>We do not use {@code SELECT ... FOR UPDATE} (pessimistic locking) because:
     * <ol>
     *   <li>It requires holding a transaction open across network hops.
     *   <li>It serialises writers that may not conflict at all (different owners).
     *   <li>Re-reading + re-running the applier on a lost CAS is safe because the
     *       applier is a pure function — there are no side effects to undo.
     * </ol>
     *
     * @param expected the state we read before computing {@code newState}.
     * @param newState the state we want to commit.
     * @return {@code true} if exactly one row was updated; {@code false} if the CAS lost.
     */
    public boolean casUpdate(Employee expected, Employee newState) {
        int rows = jdbc.update("""
                UPDATE employee
                   SET hr_version         = ?,
                       transport_version  = ?,
                       name               = ?,
                       email              = ?,
                       manager_id         = ?,
                       office_code        = ?,
                       status             = ?,
                       pickup_point       = ?,
                       special_assistance = ?,
                       hr_content_hash    = ?,
                       updated_at         = NOW()
                 WHERE tenant_id          = ?
                   AND employee_id        = ?
                   AND hr_version         = ?
                   AND transport_version  = ?
                """,
                newState.hrVersion(), newState.transportVersion(),
                newState.name(), newState.email(), newState.managerId(), newState.officeCode(),
                newState.status() != null ? newState.status().name() : null,
                newState.pickupPoint(), newState.specialAssistance(), newState.hrContentHash(),
                expected.tenantId(), expected.employeeId(),
                expected.hrVersion(), expected.transportVersion());
        return rows == 1;
    }

    /**
     * Loads a {@code (employeeId → hrContentHash)} map for all ACTIVE employees of one
     * tenant.  Used by the nightly reconciliation engine.
     *
     * <p>Memory bound: at 200k employees this map holds approximately
     * 200k × (100 bytes key + 64 bytes hash) ≈ 33 MB — genuinely bounded and safe to
     * hold in heap.  For tenants beyond ~10M employees, a sorted merge-join against the
     * export stream would be the right approach (Commit 5 documents this).
     */
    public Map<String, String> loadContentHashMap(String tenantId) {
        Map<String, String> result = new HashMap<>();
        RowCallbackHandler collectHashes = rs ->
                result.put(rs.getString("employee_id"), rs.getString("hr_content_hash"));
        jdbc.query(
                """
                SELECT employee_id, hr_content_hash
                  FROM employee
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """,
                collectHashes,
                tenantId);
        return result;
    }
}
