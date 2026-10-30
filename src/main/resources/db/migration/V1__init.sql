-- ============================================================
-- V1 — Baseline schema
-- ============================================================
-- Dialect: standard SQL with no Postgres-specific extensions.
-- Runs identically on PostgreSQL (production) and H2 in
-- PostgreSQL-compatibility mode (unit / integration tests).
-- Postgres-only features (partial indexes, JSONB, FOR UPDATE
-- SKIP LOCKED) are introduced in code comments, not here.
-- ============================================================

-- ── Employee ─────────────────────────────────────────────────────────────
-- Primary key is (tenant_id, employee_id) so every index lookup is
-- implicitly scoped to a single tenant — tenant isolation by construction.
CREATE TABLE employee (
    tenant_id          VARCHAR(100)             NOT NULL,
    employee_id        VARCHAR(100)             NOT NULL,

    -- Two independent version counters (Invariant 1).
    -- HR writes advance hr_version only; transport writes advance
    -- transport_version only.  Both counters appear in the CAS WHERE clause.
    hr_version         INT                      NOT NULL DEFAULT 0,
    transport_version  INT                      NOT NULL DEFAULT 0,

    -- HR-owned fields (FieldOwner.HR)
    name               VARCHAR(255),
    email              VARCHAR(255),
    manager_id         VARCHAR(100),
    office_code        VARCHAR(50),
    status             VARCHAR(20)              NOT NULL DEFAULT 'ACTIVE',

    -- Transport-owned fields (FieldOwner.TRANSPORT)
    pickup_point       VARCHAR(500),
    special_assistance VARCHAR(500),

    -- SHA-256 of the HR-owned fields in canonical order.
    -- Stored so the reconciliation engine can diff a full export without
    -- loading every column of every row.
    hr_content_hash    VARCHAR(64),

    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    PRIMARY KEY (tenant_id, employee_id)
);

-- Reconciliation reads active employees by tenant; ingest reads by (tenant, status).
CREATE INDEX idx_employee_tenant_status ON employee (tenant_id, status);


-- ── Outbox ───────────────────────────────────────────────────────────────
-- Every committed state change is written here in the SAME transaction as
-- the employee row update.  The poller (Commit 3) delivers rows to downstream
-- consumers and then marks them published.
--
-- At-least-once delivery guarantee: even if the process crashes after the DB
-- commit, the row survives and the poller republishes on restart.  Consumers
-- MUST be idempotent (Commit 3 README note).
--
-- `GENERATED ALWAYS AS IDENTITY` is ISO SQL (also supported by H2 2.x).
-- PostgreSQL's BIGSERIAL is equivalent but is dialect-specific.
CREATE TABLE outbox_event (
    id           BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    VARCHAR(100)             NOT NULL,
    employee_id  VARCHAR(100)             NOT NULL,
    event_type   VARCHAR(50)              NOT NULL,
    payload      TEXT                     NOT NULL,  -- JSON; JSONB in Postgres if needed
    published    BOOLEAN                  NOT NULL DEFAULT FALSE,
    attempts     INT                      NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP WITH TIME ZONE
);

-- Poller selects WHERE published = FALSE ORDER BY created_at ASC.
-- In Postgres production, add FOR UPDATE SKIP LOCKED to allow multiple poller
-- instances without double-publishing (H2 does not support this idiom reliably,
-- so we document it as a production-only scaling step rather than adding it here).
CREATE INDEX idx_outbox_published_time ON outbox_event (published, created_at);


-- ── Reconciliation run log ───────────────────────────────────────────────
-- One row per nightly run.  Status values:
--   IN_PROGRESS          — run is executing
--   COMPLETED            — all changes applied successfully
--   BLOCKED_SAFETY_BRAKE — missing% exceeded threshold; nobody was deactivated
--   FAILED               — unexpected error
--
-- approved_by / approved_at — set by the operator approval endpoint (Commit 6).
CREATE TABLE reconciliation_run (
    id              BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       VARCHAR(100)             NOT NULL,
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE,
    status          VARCHAR(50)              NOT NULL,
    total_rows      INT,
    new_count       INT,
    changed_count   INT,
    unchanged_count INT,
    missing_count   INT,
    active_in_store INT,
    missing_pct     NUMERIC(5, 2),
    approved_by     VARCHAR(100),
    approved_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_recon_tenant_status ON reconciliation_run (tenant_id, status);
