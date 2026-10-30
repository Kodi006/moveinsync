CREATE TABLE reconciliation_candidate (
    tenant_id                 VARCHAR(100) NOT NULL,
    run_id                    BIGINT NOT NULL,
    employee_id               VARCHAR(100) NOT NULL,
    expected_hr_content_hash  VARCHAR(64),
    status                    VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, run_id, employee_id),
    FOREIGN KEY (run_id) REFERENCES reconciliation_run(id)
);

CREATE INDEX idx_reconciliation_candidate_status
    ON reconciliation_candidate (tenant_id, run_id, status);
