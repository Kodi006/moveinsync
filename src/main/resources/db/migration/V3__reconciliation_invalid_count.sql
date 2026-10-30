ALTER TABLE reconciliation_run
    ADD COLUMN invalid_count INT NOT NULL DEFAULT 0;
