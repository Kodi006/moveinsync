package com.moveinsync.roster.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationSchedulerTest {

    @Test
    void invokesReconciliationForConfiguredTenantAndExport() throws Exception {
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<Path> file = new AtomicReference<>();
        ReconciliationRunner runner = (tenantId, exportFile) -> {
            tenant.set(tenantId);
            file.set(exportFile);
            return new ReconciliationResult(4, "COMPLETED", 1, 0, 0, 0, 0, 0, 1, 0.0);
        };

        new ReconciliationScheduler(runner, "tenant-a", "/data/roster.csv")
                .runScheduledReconciliation();

        assertThat(tenant).hasValue("tenant-a");
        assertThat(file).hasValue(Path.of("/data/roster.csv"));
    }

    @Test
    void skipsWhenConfigurationIsMissing() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        ReconciliationRunner runner = (tenantId, exportFile) -> {
            called.set(true);
            return null;
        };

        new ReconciliationScheduler(runner, "", "").runScheduledReconciliation();

        assertThat(called).isFalse();
    }

    @Test
    void logsAndReturnsWhenTheExportCannotBeRead() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        ReconciliationRunner runner = (tenantId, exportFile) -> {
            called.set(true);
            throw new IOException("missing");
        };

        new ReconciliationScheduler(runner, "tenant-a", "/missing.csv")
                .runScheduledReconciliation();

        assertThat(called).isTrue();
    }
}
