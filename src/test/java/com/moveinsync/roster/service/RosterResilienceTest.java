package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.Decision;
import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;
import com.moveinsync.roster.domain.RosterEvent;
import com.moveinsync.roster.outbox.ChangeEventPublisher;
import com.moveinsync.roster.outbox.OutboxPoller;
import com.moveinsync.roster.repo.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RosterResilienceTest {

    private static final String TENANT = "tenant-resilience";

    @Autowired private RosterIngestService ingest;
    @Autowired private EmployeeRepository employees;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxPoller poller;
    @Autowired private ChangeEventPublisher publisher;

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM reconciliation_candidate");
        jdbc.update("DELETE FROM reconciliation_run");
        jdbc.update("DELETE FROM booking");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM employee");
    }

    @Test
    void fiftyConcurrentVersionsConvergeToVersionFiftyAcrossTwentyRuns() throws Exception {
        for (int round = 0; round < 20; round++) {
            String employeeId = "concurrent-" + round;
            List<Integer> versions = new ArrayList<>();
            for (int version = 1; version <= 50; version++) versions.add(version);
            Collections.shuffle(versions, new Random(round));

            ExecutorService pool = Executors.newFixedThreadPool(50);
            CountDownLatch ready = new CountDownLatch(50);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Decision>> results = new ArrayList<>();
            for (int version : versions) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                    return ingest.ingest(event(employeeId, version));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Decision> result : results) result.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            Employee stored = employees.findById(TENANT, employeeId).orElseThrow();
            assertThat(stored.hrVersion()).isEqualTo(50);
        }
    }

    @Test
    void publisherFailureLeavesCommittedOutboxRowForNextPoll() {
        AtomicBoolean failOnce = new AtomicBoolean(true);
        publisher.addListener(event -> {
            if (failOnce.getAndSet(false)) throw new IllegalStateException("injected failure");
        });
        assertThat(ingest.ingest(event("crash-case", 1))).isEqualTo(Decision.APPLY);
        long id = jdbc.queryForObject("SELECT id FROM outbox_event WHERE tenant_id = ? AND employee_id = ?",
                Long.class, TENANT, "crash-case");

        assertThat(poller.pollOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT published FROM outbox_event WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_event WHERE id = ?", String.class, id))
                .isEqualTo("IllegalStateException");

        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT published FROM outbox_event WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    void replayingSyntheticFeedAppliesNothingOnSecondPass() {
        List<RosterEvent> feed = new ArrayList<>();
        for (int employee = 0; employee < 50; employee++) {
            for (int version = 1; version <= 3; version++) {
                feed.add(new RosterEvent(TENANT, "feed-" + employee, FieldOwner.HR, version,
                        EventType.UPSERT, Map.of("name", "Employee " + employee + " v" + version,
                                "email", "e" + employee + "@example.test", "officeCode", "BLR")));
            }
        }

        long firstPassApplied = feed.stream().filter(e -> ingest.ingest(e) == Decision.APPLY).count();
        List<Employee> beforeReplay = new ArrayList<>();
        for (int employee = 0; employee < 50; employee++) {
            beforeReplay.add(employees.findById(TENANT, "feed-" + employee).orElseThrow());
        }
        int outboxBeforeReplay = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class);

        long secondPassApplied = feed.stream().filter(e -> ingest.ingest(e) == Decision.APPLY).count();

        assertThat(firstPassApplied).isEqualTo(150);
        assertThat(secondPassApplied).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(outboxBeforeReplay);
        for (int employee = 0; employee < 50; employee++) {
            Employee afterReplay = employees.findById(TENANT, "feed-" + employee).orElseThrow();
            assertThat(afterReplay.hrVersion()).isEqualTo(beforeReplay.get(employee).hrVersion());
            assertThat(afterReplay.name()).isEqualTo(beforeReplay.get(employee).name());
            assertThat(afterReplay.hrContentHash()).isEqualTo(beforeReplay.get(employee).hrContentHash());
        }
    }

    private RosterEvent event(String employeeId, int version) {
        return new RosterEvent(TENANT, employeeId, FieldOwner.HR, version, EventType.UPSERT,
                Map.of("name", "Employee " + version, "email", employeeId + "@example.test", "officeCode", "BLR"));
    }
}
