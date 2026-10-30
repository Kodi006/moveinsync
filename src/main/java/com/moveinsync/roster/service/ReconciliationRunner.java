package com.moveinsync.roster.service;

import java.io.IOException;
import java.nio.file.Path;

/** Small seam between the scheduler and the reconciliation implementation. */
@FunctionalInterface
public interface ReconciliationRunner {
    ReconciliationResult reconcile(String tenantId, Path exportFile) throws IOException;
}
