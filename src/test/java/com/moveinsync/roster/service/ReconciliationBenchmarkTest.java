package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.Decision;
import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("benchmark")
@SpringBootTest
class ReconciliationBenchmarkTest {

    private static final int ROWS = 200_000;
    private static final int INGEST_EVENTS = 1_000;
    private static final String TENANT = "tenant-benchmark";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReconciliationService reconciliation;
    @Autowired private RosterIngestService ingest;

    @Test
    void timesTwoHundredThousandRowDiffAndIngest(@TempDir Path tempDir) throws Exception {
        seedEmployees();
        Path export = writeExport(tempDir.resolve("export.csv"));
        AtomicLong peakHeap = new AtomicLong(usedHeap());
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> peakHeap.accumulateAndGet(usedHeap(), Math::max),
                0, 1, TimeUnit.MILLISECONDS);

        long diffStart = System.nanoTime();
        ReconciliationResult result = reconciliation.reconcile(TENANT, export);
        long diffMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - diffStart);
        sampler.shutdownNow();

        assertThat(result.unchangedCount()).isEqualTo(ROWS);
        assertThat(result.changedCount()).isZero();
        assertThat(result.newCount()).isZero();
        assertThat(diffMillis).isLessThan(60_000);

        List<Long> latencyNanos = new ArrayList<>(INGEST_EVENTS);
        long ingestStart = System.nanoTime();
        for (int i = 0; i < INGEST_EVENTS; i++) {
            long eventStart = System.nanoTime();
            Decision decision = ingest.ingest(new RosterEvent(TENANT, "throughput-" + i,
                    FieldOwner.HR, 1, EventType.UPSERT,
                    Map.of("name", "Throughput " + i, "email", "t" + i + "@example.test", "officeCode", "BLR")));
            assertThat(decision).isEqualTo(Decision.APPLY);
            latencyNanos.add(System.nanoTime() - eventStart);
        }
        double throughput = INGEST_EVENTS / ((System.nanoTime() - ingestStart) / 1_000_000_000.0);
        Collections.sort(latencyNanos);
        double p50Millis = toMillis(latencyNanos.get((int) (latencyNanos.size() * 0.50)));
        double p99Millis = toMillis(latencyNanos.get((int) (latencyNanos.size() * 0.99)));

        System.out.printf("""
                BENCHMARK RESULTS
                machine_os=%s
                machine_arch=%s
                java_version=%s
                processors=%d
                max_heap_mb=%d
                reconciliation_rows=%d
                reconciliation_diff_ms=%d
                reconciliation_peak_heap_sampled_mb=%d
                ingest_events=%d
                ingest_throughput_events_per_sec=%.2f
                ingest_p50_ms=%.3f
                ingest_p99_ms=%.3f
                """, System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / 1024 / 1024, ROWS, diffMillis,
                peakHeap.get() / 1024 / 1024, INGEST_EVENTS, throughput, p50Millis, p99Millis);
    }

    private void seedEmployees() {
        String hash = com.moveinsync.roster.domain.ContentHash.compute(new Employee.Builder("x", "x")
                .name("Employee").email("employee@example.test").managerId("manager")
                .officeCode("BLR").build());
        for (int start = 0; start < ROWS; start += 1_000) {
            int end = Math.min(start + 1_000, ROWS);
            List<Object[]> batch = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                batch.add(new Object[]{TENANT, employeeId(i), 1, 0, "Employee",
                        "employee@example.test", "manager", "BLR", "ACTIVE", hash});
            }
            jdbc.batchUpdate("""
                    INSERT INTO employee (tenant_id, employee_id, hr_version, transport_version,
                                          name, email, manager_id, office_code, status, hr_content_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, batch);
        }
    }

    private Path writeExport(Path path) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("employeeId,name,email,managerId,officeCode\n");
            for (int i = 0; i < ROWS; i++) {
                writer.write("%s,Employee,employee@example.test,manager,BLR%n".formatted(employeeId(i)));
            }
        }
        return path;
    }

    private String employeeId(int index) {
        return "emp-%06d".formatted(index);
    }

    private long usedHeap() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        return memory.getHeapMemoryUsage().getUsed();
    }

    private double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
