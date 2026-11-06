# PerfTrace — Hardware Telemetry Diagnostic Engine

PerfTrace is a full-stack hardware telemetry diagnostic application built with **Spring Boot 4**, **PostgreSQL 16**, and **React 19 + TypeScript**. It ingests CSV logs exported by **HWiNFO64**, aggregates readings into statistical 10-second windows, classifies compute bottlenecks using transparent rule-based heuristics, detects possible thermal throttling, and supports side-by-side run comparison with strict ownership enforcement.

Designed as a portfolio project for MCA campus placements and technical interviews, PerfTrace emphasizes explainability, data integrity, bounded-memory ingestion, and defensive telemetry analysis.

---

## What the Pipeline Actually Does

```
HWiNFO64 CSV export
        │
        ▼
1. Upload spooling + two-pass CSV scan
   • Multipart upload is copied to a temporary file (never materialized as a row list)
   • Pass 1: validate headers, count accepted/rejected rows, establish timestamp metadata
   • Pass 2: stream rows one at a time into window aggregation and JDBC batches
   • BOM detection (UTF-8 / UTF-16 LE / UTF-16 BE), unit header skipping
   • Missing readings stay NULL; they are never coerced to zero
        │
        ▼
2. Streaming 10s window aggregation
   • Aggregate-only TimeWindow objects; raw samples are not retained by analysis
   • Empty windows are kept so sampling gaps remain visible as UNKNOWN time
   • Final window is clipped to the last observed timestamp
   • CPU and GPU clock averages use only independently active samples
        │
        ▼
3. Rule-based classification + thermal detection
   • IdleDetector: CPU < 15% AND GPU < 15%
   • CpuBoundDetector: GPU < 90% and (CPU total ≥ 90% or busiest thread ≥ 90%)
   • GpuBoundDetector: GPU ≥ 95% and busiest thread < 85%
   • BALANCED requires busiest-thread evidence; otherwise UNKNOWN
   • Hardware throttle flags are distinguished from heuristic clock regression
   • Preliminary P95 baseline excludes already-throttled windows before the final P90
        │
        ▼
4. Segment merging
   • Consecutive compatible windows are merged
   • Short unthrottled blips may be smoothed, but throttle transitions are never smoothed away
   • One aggregate metrics window per draft; source rows are not retained
        │
        ▼
5. PostgreSQL persistence and comparison
   • Flyway V1 + V2 migrations; Hibernate ddl-auto=validate
   • JDBC batch inserts (1,000 rows per batch) inside one transaction
   • Failed processing rolls back partial samples/segments and records the run as FAILED
   • Chart downsampling runs in SQL with NTILE buckets + min/max stress ranking
   • GET /api/runs/compare?a={runIdA}&b={runIdB}
        │
        ▼
6. React dashboard
   • Segment timeline with evidence
   • Downsampled Chart.js time series
   • Workload metadata selector and comparison warnings
```

---

## Technology Stack

- **Backend**: Java 21 LTS, Spring Boot 4.1.2, Spring Security 7 (stateless JWT), Spring Data JPA / Hibernate 7, OpenCSV 5.9, Flyway, SpringDoc OpenAPI.
- **Database**: PostgreSQL 16 (local portable instance or Docker Compose). H2 is used only for fast tests.
- **Frontend**: React 19, TypeScript 5, Vite 6, Chart.js, Lucide icons.
- **Testing**: JUnit 5, Mockito, Spring Security Test, MockMvc, H2 for fast integration tests, Testcontainers PostgreSQL 16 for the database-compatibility suite, Vitest for the frontend API client and workload metadata helpers.

---

## Diagnostic Principles

1. **Strict missing-data semantics**: missing or malformed cells become `null`. A window without both CPU total load and GPU load is `UNKNOWN`, not idle.
2. **Busiest-thread evidence**: `BALANCED` requires a busiest CPU thread reading. Without it, PerfTrace reports `UNKNOWN` rather than guessing.
3. **Balanced is not optimal**: `BALANCED` only means neither CPU nor GPU crossed PerfTrace's bottleneck thresholds.
4. **Temperature alone is not throttling**: a heuristic throttle requires high temperature, active load, and a clock regression relative to a reference baseline. Hardware flags are reported separately as `HARDWARE_FLAG`.
5. **Thermal baseline correction**: a preliminary P95 baseline identifies already-compressed windows; those windows are excluded before the final P90 reference is computed. A long throttled run therefore cannot lower its own baseline and hide the regression.
6. **Active-workload clocks**: CPU and GPU clocks are averaged only over samples where that component was independently active, so idle GPU clocks do not drag down a CPU-bound average.
7. **User-supplied workload metadata**: `workloadType` is declared at upload time and defaults to `UNSPECIFIED`. PerfTrace never infers workload type from sensor values. Comparisons warn when metadata is missing or differs.
8. **Comparison humility**: a lower temperature does not prove higher performance. Comparisons report telemetry shifts, not proven throughput improvements.

---

## Bounded-Memory Ingestion — Exact Scope

The upload path does **not** retain every CSV row or every `TelemetrySample` in memory:

- The multipart file is spooled to a temporary file.
- The first pass computes header mappings, row counts, timestamp baseline, warnings, and duration without retaining rows.
- The second pass re-reads the spool and emits one normalized sample at a time.
- `TimeWindow` stores aggregate sums, counts, peaks, clock histograms, and throttle counters only.
- The clock baseline uses a fixed histogram of 20,001 one-MHz bins (about 80 KB per histogram).
- Samples are inserted via JDBC batches of 1,000 rows.
- Empty gap windows are retained, with a safety cap of 200,000 windows (more than 23 days at 10-second windows).

Memory therefore scales with **recording duration / number of analysis windows plus fixed buffers**, not with the number of CSV rows. This is bounded-memory ingestion, not a claim of literal O(1) total memory.

Chart downsampling also happens in the database. The query uses `NTILE` buckets and selects the lowest- and highest-stress row in each bucket, so short spikes survive instead of being averaged away.

---

## Setup & Running

### 1. Prerequisites

- JDK 21
- Node.js 22 (or compatible)
- PostgreSQL 16, either local or via Docker Compose
- Docker only if you want to run the PostgreSQL Testcontainers test locally

Copy `.env.example` to `.env` and set real values:

```bash
cp .env.example .env
```

Default backend configuration:
- Port: `8080`
- Database: `jdbc:postgresql://localhost:5432/perftrace`
- User: `perftrace`
- Password: from `PERFTRACE_DB_PASSWORD`
- JWT secret: from `PERFTRACE_JWT_SECRET` (at least 256 bits)

### 2. Start PostgreSQL

Portable local instance:

```powershell
& "A:\Tools\pgsql\bin\pg_ctl.exe" start -D "A:\Tools\pgsql_data" -l "A:\Tools\pgsql\logfile.log"
```

Or Docker Compose:

```bash
docker compose up -d
```

### 3. Start the backend

```powershell
$env:JAVA_HOME = "A:\Tools\jdk-21.0.7+6"
cd A:\Project\PerfTrace\backend
.\mvnw.cmd spring-boot:run
```

Flyway applies `V1__init_schema.sql` and `V2__workload_metadata.sql` on startup, and Hibernate validates the resulting schema.

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`

### 4. Start the frontend

```powershell
$env:PATH = "A:\Tools\node-v22.15.0-win-x64;" + $env:PATH
cd A:\Project\PerfTrace\frontend
npm install
npm run dev
```

The Vite dev server runs on `http://localhost:5173/` and proxies `/api` to `http://localhost:8080`.

Production build:

```powershell
npm run build
```

---

## Test Suites

### Backend unit tests (Surefire)

```powershell
cd A:\Project\PerfTrace\backend
.\mvnw.cmd test
```

Current verified result (2026-11-02): **75 tests, 0 failures, 0 errors, 0 skipped**.

Coverage includes:
- detector rules, missing-data semantics, and thermal heuristics
- streaming window boundaries, gap retention, and active-clock exclusion
- thermal baseline correction with a majority of throttled windows
- segment merging and throttle-transition protection
- CSV header mapping, numeric normalization, midnight rollover, malformed timestamps, and monotonic offsets
- comparison metrics/warnings and null-percentage handling
- authentication and duplicate registration handling
- failed-upload transaction rollback

### Backend verification (Surefire + Failsafe)

```powershell
.\mvnw.cmd verify
```

Current local result (2026-11-02):
- Surefire: **75 tests passed**
- Failsafe H2 integration tests: **4 tests passed** (`AnalysisRunIntegrationTest`)
  - full upload → segments → downsampled samples → compare → delete lifecycle
  - workload metadata round-trip and UNSPECIFIED default
  - cross-user denial on every run endpoint
  - database downsampling preserves a short stress spike
- Failsafe PostgreSQL Testcontainers test: **2 tests skipped locally** because no Docker daemon is available on this machine
- Build: `BUILD SUCCESS`

The PostgreSQL suite (`PostgresIntegrationTest`) is intentionally **not** satisfied by H2. It starts a real `postgres:16-alpine` container, applies the real Flyway migrations, and boots Hibernate with `ddl-auto=validate`. It verifies:
- `V2` is the latest applied migration and `workload_type` is `NOT NULL`
- user-supplied workload metadata persists and defaults to `UNSPECIFIED`
- PostgreSQL `NTILE` min/max downsampling preserves a short spike

When Docker is unavailable, `@Testcontainers(disabledWithoutDocker = true)` skips these tests. Skipped is reported as skipped, not as a pass. The GitHub Actions workflow runs them on a Docker-enabled runner.

### Frontend tests

```powershell
cd A:\Project\PerfTrace\frontend
npm test
npm run build
```

Current verified result: **13 Vitest tests passed** across 2 files, and the production build completed successfully.

---

## REST API

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/api/auth/register` | Register a new user | No |
| `POST` | `/api/auth/login` | Authenticate and obtain JWT | No |
| `GET` | `/api/auth/me` | Get authenticated profile | Yes |
| `POST` | `/api/runs/upload` | Upload and analyze a CSV (`file`, optional `description`, optional `workloadType`) | Yes |
| `GET` | `/api/runs` | List the caller's runs (paginated) | Yes |
| `GET` | `/api/runs/{id}` | Get run summary and percentages | Yes |
| `GET` | `/api/runs/{id}/segments` | Get timeline segments and evidence | Yes |
| `GET` | `/api/runs/{id}/samples?maxPoints=500` | Get database-downsampled chart points | Yes |
| `GET` | `/api/runs/compare?a={runIdA}&b={runIdB}` | Compare two completed runs | Yes |
| `DELETE` | `/api/runs/{id}` | Delete a run and its telemetry | Yes |

Supported `workloadType` values: `UNSPECIFIED`, `GAMING`, `CPU_RENDER`, `GPU_RENDER`, `STREAMING`, `PRODUCTIVITY`, `SYNTHETIC_BENCHMARK`, `OTHER`.

Cross-user access returns `404 Not Found` rather than `403`, so run IDs cannot be enumerated.

---

## Verification Status and Limitations

1. **Real HWiNFO64 exports**: the parser is verified against synthetic fixtures and unit tests. Real exports vary by HWiNFO version, locale, and sensor hardware, so genuine user-file compatibility remains user-verified rather than claimed.
2. **PostgreSQL compatibility**: the real PostgreSQL Testcontainers test is configured and included in CI, but it was **skipped in this local environment** because Docker was unavailable. H2 results are not presented as PostgreSQL proof.
3. **Heuristic throttling**: the heuristic is conservative and requires temperature, load, and a clock regression. Dynamic P-state behavior can still require manual confirmation against hardware throttle registers.
4. **Thermal baseline**: the two-stage P95/P90 method reduces self-masking by long throttled periods, but if more than roughly 95% of active windows are already throttled, the preliminary baseline can still be affected. Results should be interpreted with the emitted warnings.
5. **Workload detection**: workload type is never inferred automatically. If it is `UNSPECIFIED`, comparison warns that workload equivalence cannot be verified.
6. **No automatic performance-improvement claims**: PerfTrace reports telemetry shifts and rule-based saturation. Lower temperature alone is never treated as proof of higher performance.
