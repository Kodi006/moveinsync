package com.moveinsync.roster.domain;

/**
 * Aggregate representing one employee row.
 *
 * <p>Immutable: every "mutation" produces a new instance via the inner {@link Builder}.
 * In C++ terms, think of this as a value type — you copy, modify, and swap the whole
 * thing rather than mutating it in place.  This makes the optimistic-concurrency retry
 * loop safe: the expected state and the new state are always distinct objects.
 *
 * <p><strong>Builder pattern</strong> is idiomatic Java when a constructor would take
 * more than ~4 parameters.  In C++ you might use designated initialisers or a settings
 * struct.  Each setter returns {@code this} so calls can be chained fluently.
 */
public final class Employee {

    private final String tenantId;
    private final String employeeId;
    private final int hrVersion;
    private final int transportVersion;

    // ── HR-owned fields ──────────────────────────────────────────────────
    private final String name;
    private final String email;
    private final String managerId;
    private final String officeCode;
    private final EmployeeStatus status;

    // ── Transport-owned fields ───────────────────────────────────────────
    private final String pickupPoint;
    private final String specialAssistance;

    /**
     * SHA-256 fingerprint of the HR-owned fields in a fixed canonical order.
     * Stored in the DB so the reconciliation engine can diff a full export against
     * the store without loading every column of every row.
     */
    private final String hrContentHash;

    private Employee(Builder b) {
        this.tenantId          = b.tenantId;
        this.employeeId        = b.employeeId;
        this.hrVersion         = b.hrVersion;
        this.transportVersion  = b.transportVersion;
        this.name              = b.name;
        this.email             = b.email;
        this.managerId         = b.managerId;
        this.officeCode        = b.officeCode;
        this.status            = b.status;
        this.pickupPoint       = b.pickupPoint;
        this.specialAssistance = b.specialAssistance;
        this.hrContentHash     = b.hrContentHash;
    }

    // Accessors — no "get" prefix (modern Java convention; records use the same style).
    public String tenantId()          { return tenantId; }
    public String employeeId()        { return employeeId; }
    public int    hrVersion()         { return hrVersion; }
    public int    transportVersion()  { return transportVersion; }
    public String name()              { return name; }
    public String email()             { return email; }
    public String managerId()         { return managerId; }
    public String officeCode()        { return officeCode; }
    public EmployeeStatus status()    { return status; }
    public String pickupPoint()       { return pickupPoint; }
    public String specialAssistance() { return specialAssistance; }
    public String hrContentHash()     { return hrContentHash; }

    // ────────────────────────────────────────────────────────────────────

    public static final class Builder {

        // Primary-key components: fixed at construction, never reassigned.
        private final String tenantId;
        private final String employeeId;

        private int    hrVersion;
        private int    transportVersion;
        private String name;
        private String email;
        private String managerId;
        private String officeCode;
        private EmployeeStatus status = EmployeeStatus.ACTIVE; // safe default for new rows
        private String pickupPoint;
        private String specialAssistance;
        private String hrContentHash;

        /** Constructs a Builder for a brand-new employee row. */
        public Builder(String tenantId, String employeeId) {
            this.tenantId   = tenantId;
            this.employeeId = employeeId;
        }

        /**
         * Copy-constructor: pre-fills this builder from an existing {@link Employee}.
         *
         * <p>Used by {@link RosterEventApplier} to produce an updated copy without
         * mutating the original — in C++ you'd copy a struct and then set the
         * fields you want to change.
         */
        public static Builder from(Employee src) {
            Builder b = new Builder(src.tenantId, src.employeeId);
            b.hrVersion         = src.hrVersion;
            b.transportVersion  = src.transportVersion;
            b.name              = src.name;
            b.email             = src.email;
            b.managerId         = src.managerId;
            b.officeCode        = src.officeCode;
            b.status            = src.status;
            b.pickupPoint       = src.pickupPoint;
            b.specialAssistance = src.specialAssistance;
            b.hrContentHash     = src.hrContentHash;
            return b;
        }

        // Fluent setters — return `this` to allow call chaining.
        public Builder hrVersion(int v)            { this.hrVersion = v;            return this; }
        public Builder transportVersion(int v)     { this.transportVersion = v;     return this; }
        public Builder name(String v)              { this.name = v;                 return this; }
        public Builder email(String v)             { this.email = v;                return this; }
        public Builder managerId(String v)         { this.managerId = v;            return this; }
        public Builder officeCode(String v)        { this.officeCode = v;           return this; }
        public Builder status(EmployeeStatus v)    { this.status = v;               return this; }
        public Builder pickupPoint(String v)       { this.pickupPoint = v;          return this; }
        public Builder specialAssistance(String v) { this.specialAssistance = v;    return this; }
        public Builder hrContentHash(String v)     { this.hrContentHash = v;        return this; }

        public Employee build() { return new Employee(this); }
    }
}
