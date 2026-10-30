package com.moveinsync.roster.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/** Launches the configured tenant's latest mounted HR export once per day. */
@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationRunner reconciliation;
    private final String tenantId;
    private final String exportPath;

    public ReconciliationScheduler(ReconciliationRunner reconciliation,
                                   @Value("${roster.reconciliation.tenant-id:}") String tenantId,
                                   @Value("${roster.reconciliation.export-path:}") String exportPath) {
        this.reconciliation = reconciliation;
        this.tenantId = tenantId;
        this.exportPath = exportPath;
    }

    @Scheduled(cron = "${roster.reconciliation.cron:0 0 2 * * *}", zone = "UTC")
    public void runScheduledReconciliation() {
        if (tenantId.isBlank() || exportPath.isBlank()) {
            log.info("Scheduled reconciliation is not configured; set tenant-id and export-path");
            return;
        }
        try {
            ReconciliationResult result = reconciliation.reconcile(tenantId, Path.of(exportPath));
            log.info("Scheduled reconciliation completed with status {} and run id {}",
                    result.status(), result.runId());
        } catch (IOException | RuntimeException failure) {
            // ReconciliationService records a FAILED run once it has started; keep the
            // scheduler alive so the next nightly run can proceed.
            log.error("Scheduled reconciliation failed ({})", failure.getClass().getSimpleName());
        }
    }
}
