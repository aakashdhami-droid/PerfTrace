# PerfTrace — Hardware Telemetry Diagnostic Engine

PerfTrace is a full-stack hardware telemetry diagnostic application built with **Spring Boot**, **PostgreSQL 16**, and **React 19 + TypeScript**. It ingests high-frequency CSV logs exported by **HWiNFO64**, aggregates readings into statistical 10-second windows, classifies compute bottlenecks using transparent, rule-based heuristics, detects potential thermal throttling, and enables side-by-side run comparisons with strict ownership enforcement.

Designed as an enterprise-grade portfolio project for **MCA campus placements and technical interviews**, PerfTrace demonstrates sound backend architecture, data integrity, explainability, and defensive telemetry analysis.

---

## Architecture & Core Capabilities

```
  ┌─────────────────────────────────────────────────────────┐
  │                 HWiNFO64 CSV Log Export                 │
  └───────────────────────────┬─────────────────────────────┘
                              │ Streaming OpenCSV Pipeline
                              ▼
  ┌─────────────────────────────────────────────────────────┐
  │   1. CsvIngestionService                                │
  │   • BOM / UTF-8 / UTF-16 encoding auto-detection        │
  │   • Canonical header mapping across sensor variations   │
  │   • Missing sensor readings preserved as NULL (not 0)   │
  │   • Discards secondary unit header rows                 │
  └───────────────────────────┬─────────────────────────────┘
                              │
                              ▼
  ┌─────────────────────────────────────────────────────────┐
  │   2. TimeWindowAggregator (10s statistical windows)     │
  │   • Sub-second jitter reduction into discrete windows   │
  │   • Gap detection (>15s pause) preserves timeline state │
  └───────────────────────────┬─────────────────────────────┘
                              │
                              ▼
  ┌─────────────────────────────────────────────────────────┐
  │   3. Heuristic Classification Detectors                 │
  │   • IdleDetector: CPU < 20% AND GPU < 20%               │
  │   • CpuBoundDetector: CPU > 80% (or single-core sat.)   │
  │     while GPU has headroom (< 85%)                      │
  │   • GpuBoundDetector: GPU utilization >= 90%            │
  │   • Balanced: Neither crossed (not proof of optimality) │
  │   • Unknown: Insufficient data or missing sensors       │
  │   • ThermalThrottleDetector: Hard hardware flags vs     │
  │     conservative heuristic (temp + load + >12% clock reg)│
  └───────────────────────────┬─────────────────────────────┘
                              │
                              ▼
  ┌─────────────────────────────────────────────────────────┐
  │   4. SegmentMergingService                              │
  │   • 20s smoothing merges consecutive identical windows  │
  │   • CRITICAL: Never smooths away throttle transitions   │
  └───────────────────────────┬─────────────────────────────┘
                              │
                              ▼
  ┌─────────────────────────────────────────────────────────┐
  │   5. PostgreSQL 16 Persistence & Comparison Engine      │
  │   • Indexed tables: users, analysis_runs, samples,      │
  │     detected_segments (Flyway versioned migrations)     │
  │   • GET /api/runs/compare?a={idA}&b={idB}               │
  │   • Comparability warnings: duration & idle disparities │
  │   • Strict disclaimer: lower temp != higher performance │
  └───────────────────────────┬─────────────────────────────┘
                              │
                              ▼
  ┌─────────────────────────────────────────────────────────┐
  │   6. React 19 + TypeScript + Vite Dashboard             │
  │   • Interactive segment timeline with click-to-inspect  │
  │   • Downsampled time-series telemetry charts (Chart.js) │
  │   • Side-by-side run comparison diffs & radar tables    │
  └─────────────────────────────────────────────────────────┘
```

---

## Technology Stack

- **Backend**: Java 21 LTS, Spring Boot 4.1.2, Spring Security 7 (stateless JWT Lambda DSL), Spring Data JPA / Hibernate 7, OpenCSV 5.9, Flyway 12 (`flyway-database-postgresql`), SpringDoc OpenAPI 3.1.
- **Database**: PostgreSQL 16.6 (standalone portable / containerized) with Flyway migrations.
- **Frontend**: React 19, TypeScript 5, Vite 6, Chart.js, Lucide Icons, Glassmorphic CSS design system.
- **Testing**: JUnit 5, Mockito, Spring Security Test, Spring Boot MockMvc, H2 (isolated in-memory test database for fast regression suites).

---

## Diagnostic Principles & Guidelines

1. **Strict Missing-Data Semantics**: Missing sensors or malformed cells are persisted and calculated as `null`—never defaulted to zero. A missing GPU sensor is classified as `UNKNOWN`, never `BALANCED`.
2. **Balanced vs Optimal**: A classification of `BALANCED` signifies that neither CPU nor GPU crossed our explicit bottleneck thresholds; it is **never** presented as conclusive proof of optimal resource scheduling.
3. **Hardware Flags vs Heuristic Throttling**: Hardware thermal flags (`Core Thermal Throttling [Yes/No]`, PROCHOT) are distinguished from heuristic throttling (sustained high temperature + active load + >12% clock frequency regression from baseline). Temperature alone never triggers a throttling diagnosis.
4. **Run Comparison Humility**: When comparing two runs (`GET /api/runs/compare?a={idA}&b={idB}`), PerfTrace warns when runs differ significantly in duration (>1.3x) or idle ratio (>25%). It explicitly enforces the rule: *a lower operating temperature does not prove superior performance if clock frequencies dropped.*

---

## Setup & Running Instructions

### 1. Prerequisites & Environment Variables

Copy `.env.example` to `.env`:
```bash
cp .env.example .env
```

Default configuration in `application.properties`:
- Port: `8080`
- Database: `jdbc:postgresql://localhost:5432/perftrace`
- User: `perftrace`
- Password: (blank or your secure password)

### 2. Starting PostgreSQL

#### Option A: Portable Standalone PostgreSQL (Workspace Environment)
```powershell
& "A:\Tools\pgsql\bin\pg_ctl.exe" start -D "A:\Tools\pgsql_data" -l "A:\Tools\pgsql\logfile.log"
```

#### Option B: Docker Compose
```bash
docker compose up -d
```

### 3. Starting the Spring Boot Backend

Using Maven Wrapper from `A:\Project\PerfTrace\backend`:
```powershell
# Set JAVA_HOME if not on system PATH
$env:JAVA_HOME = "A:\Tools\jdk-21.0.7+6"
$env:PATH = "A:\Tools\jdk-21.0.7+6\bin;" + $env:PATH

cd A:\Project\PerfTrace\backend
.\mvnw.cmd spring-boot:run
```
The backend initializes database tables via Flyway and listens on `http://localhost:8080`.
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`

### 4. Starting the React Frontend

From `A:\Project\PerfTrace\frontend`:
```powershell
# Set Node on PATH if using portable installation
$env:PATH = "A:\Tools\node-v22.15.0-win-x64;" + $env:PATH

cd A:\Project\PerfTrace\frontend
npm run dev
```
The frontend dev server opens on `http://localhost:5173/` and proxies all `/api` requests to `http://localhost:8080`.

To build the production bundle:
```powershell
npm run build
```
Builds verified: clean exit code 0 (`dist/` directory generated).

---

## Automated Test Suites

PerfTrace maintains 36 automated tests across unit and integration suites:

```powershell
cd A:\Project\PerfTrace\backend

# Run Unit Tests (Surefire Plugin: 35 tests)
.\mvnw.cmd test

# Run Full Verification (Surefire + Failsafe Integration Tests: 36 tests)
.\mvnw.cmd verify
```

### Test Breakdown:
- `DetectorsTest` (15 tests):
  - `IdleDetector` (3 tests): Idle detection, active workload rejection, null safety.
  - `CpuBoundDetector` (4 tests): Multi-core saturation, single-thread bottleneck with GPU headroom, GPU-saturated rejection, missing sensor handling.
  - `GpuBoundDetector` (3 tests): GPU saturation, low GPU load rejection, null sensor safety.
  - `ThermalThrottleDetector` (5 tests): Explicit hardware flag detection, heuristic clock regression with elevated temp, high temp without clock drop (no false positive), baseline clock drop detection, normal operating temperatures.
- `CsvIngestionServiceTest` (7 tests): Standard HWiNFO64 header mapping, alternate sensor names, missing columns, comma-in-quotes, numeric formatting, UTF-8 BOM handling, second unit header row skipping.
- `SegmentMergingServiceTest` (4 tests): Adjacent compatible window merging, boundary preservation on classification change, boundary preservation on throttle transitions (protection rule), empty input.
- `RunComparisonServiceTest` (4 tests): Metric delta calculations, duration disparity warning (>1.3x), idle ratio mismatch warning (>25%), sensor coverage difference tracking.
- `AuthControllerTest` (5 tests): User registration, duplicate username rejection, invalid email rejection, login success, invalid password rejection.
- `AnalysisRunIntegrationTest` (1 comprehensive E2E test): Full multi-user lifecycle test against mock MVC and database: upload -> batch insert -> segment retrieval -> downsampled samples -> user cross-access isolation (HTTP 403) -> side-by-side run comparison -> cascading deletion.

---

## REST API Specification

| Method | Endpoint | Description | Auth Required |
|---|---|---|---|
| `POST` | `/api/auth/register` | Register a new user | No |
| `POST` | `/api/auth/login` | Authenticate and obtain JWT token | No |
| `GET` | `/api/auth/me` | Get authenticated user profile | Yes (Bearer) |
| `POST` | `/api/runs/upload` | Upload and analyze HWiNFO64 CSV file | Yes (Bearer) |
| `GET` | `/api/runs` | List user's analysis runs (paginated) | Yes (Bearer) |
| `GET` | `/api/runs/{id}` | Get run summary, active % breakdown | Yes (Bearer) |
| `GET` | `/api/runs/{id}/segments` | Get detected timeline segments & evidence | Yes (Bearer) |
| `GET` | `/api/runs/{id}/samples` | Get downsampled points for charts | Yes (Bearer) |
| `GET` | `/api/runs/compare?a={idA}&b={idB}` | Compare two runs side-by-side | Yes (Bearer) |
| `DELETE` | `/api/runs/{id}` | Delete a run and associated telemetry | Yes (Bearer) |

---

## Known Limitations & Verification Status

1. **HWiNFO64 Real Export Verification**: The CSV parser and canonical header mappings were built according to standard HWiNFO64 export specifications and verified against synthetic test fixtures (`synthetic_cpu_bound.csv`, `synthetic_gpu_bound.csv`, `synthetic_throttling.csv`). Because real HWiNFO64 exports vary across localized Windows environments, sensor hardware, and HWiNFO versions, **real-file compatibility remains marked as unverified until tested against genuine user hardware exports**.
2. **Heuristic vs Hardware Ground Truth**: Heuristic thermal throttling detection requires baseline clock estimation; workloads with dynamic P-state scaling (e.g., bursty web browsing) may trigger clock variations that require manual verification against the explicit hardware throttle register flag.
