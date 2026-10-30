# MoveInSync HR Roster Sync

Spring Boot service for tenant-scoped HR and transport roster updates. Events are
versioned per employee and owner, applied with optimistic compare-and-set (CAS), and
recorded in a transactional outbox. See [DESIGN.md](DESIGN.md) for architecture,
ownership, and recovery behavior.

## Run locally

Requirements: Java 21+, Maven, Docker, and the Docker Compose plugin. Start Postgres
and the application with:

```sh
docker compose up --build
```

The API listens on `http://localhost:8080`. Compose provides development keys `admin`
and `operator`, both scoped to `demo-tenant`. Use the `X-API-Key` header. For local
development without Docker, use `mvn spring-boot:run -Dspring-boot.run.profiles=h2`.
Configure keys as environment variables named `ROSTER_SECURITY_APIKEYS_<KEY>` with
values `<tenant-id>:ADMIN` or `<tenant-id>:OPERATOR`. Never use the Compose sample keys
outside local development.

## API examples

Ingest a batch; each record gets an independent result (`APPLY`, `DUPLICATE`, `STALE`,
or `REJECTED`):

```sh
curl -s http://localhost:8080/api/v1/roster/events \
  -H 'Content-Type: application/json' -H 'X-API-Key: admin' \
  -d '{"events":[{"tenantId":"demo-tenant","employeeId":"e-100","owner":"HR","version":1,"eventType":"UPSERT","payload":{"name":"Asha Rao","email":"asha@example.test","officeCode":"BLR","status":"ACTIVE"}}]}'
```

Read an employee (tenant comes from the API key):

```sh
curl -i http://localhost:8080/api/v1/employees/e-100 -H 'X-API-Key: admin'
```

List reconciliation runs with either key; approve pending candidates with an
`OPERATOR` key:

```sh
curl -s http://localhost:8080/api/v1/reconciliation/runs -H 'X-API-Key: operator'
curl -s -X POST http://localhost:8080/api/v1/reconciliation/runs/1/approve \
  -H 'Content-Type: application/json' -H 'X-API-Key: operator' \
  -d '{"operatorId":"on-call-1"}'
```

Health and Prometheus metrics are available at `/actuator/health` and
`/actuator/prometheus`.

## Assumptions and scope

- The API accepts batches of events, not a bulk employee import endpoint. The
  reconciliation service consumes an HR CSV export with `employeeId`, `name`, `email`,
  `managerId`, and `officeCode` headers.
- HR owns `name`, `email`, `managerId`, `officeCode`, and `status`; transport owns
  `pickupPoint` and `specialAssistance`. Fields owned by another sender are masked.
- Each owner has its own monotonic employee version. Equal versions are duplicates and
  lower versions are stale, scoped by tenant and employee.
- The outbox is delivered at least once. Consumers must be idempotent. Its included
  publisher is an in-process demonstration adapter.
- A reconciliation missing more than the configured 30% of active employees is blocked
  by default and requires operator approval. Adjust `roster.reconciliation` settings
  for a controlled environment.
- The outbox poller is intentionally single-instance. The local read cache has a
  30-second TTL; application writes evict after commit, while external writes can remain
  stale until expiration.

## Deliberately out of scope

- **Kafka:** the outbox/publisher seam demonstrates durable handoff and at-least-once
  delivery without requiring a broker. A production deployment can replace the
  in-process publisher with Kafka and retain idempotent consumers.
- **JWT:** this case study uses API keys to keep the focus on tenant isolation and
  role enforcement. Production identity federation, rotation, and fine-grained policy
  require a dedicated identity provider and threat model.
- **Multi-instance outbox polling:** row locking with `SKIP LOCKED` is database-specific
  and not part of this H2-compatible example. A scaled deployment needs distributed
  claiming/leases or PostgreSQL locking plus operational monitoring.

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
