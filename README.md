# MoveInSync HR Roster Sync

## Outbox delivery

Outbox delivery is at least once. If the process publishes an event and stops before
marking its row as published, the next poll can deliver it again. Consumers must make
their event handling idempotent.

## Reconciliation benchmark

Run the tagged 200,000-row reconciliation and ingest benchmark with:

```sh
mvn -Pbenchmark test
```

It prints a copyable block with the diff time, sampled peak heap, ingest throughput,
and p50/p99 latency for the machine running the benchmark.

Run recorded on Mac OS X (aarch64), Java 27.0.1.0.3, 8 processors, 2 GB max heap:

```text
BENCHMARK RESULTS
machine_os=Mac OS X
machine_arch=aarch64
java_version=27.0.1.0.3
processors=8
max_heap_mb=2048
reconciliation_rows=200000
reconciliation_diff_ms=562
reconciliation_peak_heap_sampled_mb=683
ingest_events=1000
ingest_throughput_events_per_sec=1866.11
ingest_p50_ms=0.256
ingest_p99_ms=3.060
```

## Employee read cache

The employee detail read endpoint uses a local Caffeine cache with a 30-second
expire-after-write TTL. A read can be stale for up to 30 seconds after a database write
made outside this application instance; writes made through this instance evict the
entry after commit. The cache does not participate in version checks, idempotency, or
reconciliation decisions, so removing it changes read performance only.

## Run the application

With Docker and the Compose plugin installed, start Postgres and the app with:

```sh
docker compose up
```

The API listens on `http://localhost:8080`. The Compose file supplies development-only
API keys `admin` and `operator` for tenant `demo-tenant`; send them in the `X-API-Key`
header. Replace those keys and database credentials for any shared deployment.

For a no-Docker run, start the app with the H2 profile:

```sh
mvn spring-boot:run -Dspring-boot.run.profiles=h2
```

Configure API keys with `ROSTER_SECURITY_APIKEYS_<KEY>` environment variables whose
values are `<tenant-id>:ADMIN` or `<tenant-id>:OPERATOR`.
