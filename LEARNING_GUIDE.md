# PerfTrace — Technical Architecture & Placement Learning Guide

This guide explains PerfTrace in plain English for MCA campus placements and technical interviews. It covers the architecture, the important classes, the algorithms, and the trade-offs behind them.

---

## 1. System Architecture Overview

### Why build PerfTrace this way?

PC hardware diagnostics suffer from two extremes:

1. **Raw CSV logs are overwhelming.** A 30-minute session at 500 ms intervals produces 3,600 rows across dozens of sensor columns. A human cannot reliably spot a bottleneck by reading the file.
2. **Naive averages hide the truth.** A single average over the whole session hides the fact that the GPU was saturated at 99% and then lost 400 MHz to thermal throttling.

PerfTrace is an **explainable diagnostic pipeline**:

- **Ingestion** validates and streams HWiNFO64 CSV exports without retaining every row.
- **Aggregation** reduces readings into ~10-second statistical windows.
- **Classification** applies transparent rules for idle, CPU-bound, GPU-bound, balanced, and unknown periods.
- **Thermal analysis** separates hardware throttle flags from heuristic clock regressions.
- **Segment merging** builds a readable timeline without hiding throttle transitions.
- **Comparison** checks duration, idle ratio, sensors, and user-declared workload metadata before presenting telemetry shifts.

---

## 2. Module 1: Resilient CSV Ingestion

### Key classes

- `com.perftrace.service.csv.CsvIngestionService`
- `com.perftrace.service.csv.CsvScanResult`
- `com.perftrace.service.csv.HwinfoHeaderMapper`
- `com.perftrace.service.csv.NormalizedSensorField`
- `com.perftrace.service.csv.CsvParseResult`

### How it works

1. **Two passes over a spooled file.** The upload is copied to a temporary CSV. Pass 1 (`scan`) validates headers, maps sensors, counts accepted/rejected rows, and computes timestamp metadata. Pass 2 (`streamSamples`) re-reads the file and emits one normalized `TelemetrySample` at a time to the window aggregator and JDBC batch writer. Neither pass stores a `List<String[]>` or a list of all samples.
2. **BOM and encoding detection.** UTF-8 BOM and UTF-16 LE/BE are detected before reading. Otherwise the first header can become `\uFEFFDate` and fail header matching.
3. **Secondary unit header rows.** HWiNFO may emit a second row such as `(sec),[%],[°C]`. Rows with multiple unit-like cells are skipped.
4. **Strict null semantics.** Empty cells, `N/A`, `-`, and similar markers become `null`. They are never converted to `0.0`.
5. **Timestamp reliability.** The parser supports date+time, time-only, numeric seconds, common locale formats, midnight rollover, and out-of-order rows. A malformed timestamp continues from the last known time plus the observed interval instead of resetting the timeline.

### Memory characteristics

The upload path is **bounded-memory**, not literally O(1):

- CSV rows are streamed; raw rows are not retained.
- `TimeWindow` keeps sums, counts, peaks, throttle counters, and active-clock totals.
- The thermal baseline uses a fixed 20,001-bin histogram (~80 KB per histogram).
- JDBC inserts are batched at 1,000 rows.
- A safety cap allows at most 200,000 window objects (over 23 days at 10-second windows).

Memory grows with recording length/window count and fixed buffers, not with the number of CSV rows.

### Interview questions

**Q1: Why not use `Files.readAllLines()` or `List<String[]>`?**

> "A multi-user service can receive several large uploads at once. Materializing every row multiplies heap usage and risks GC pauses or `OutOfMemoryError`. PerfTrace spools the upload, scans it once for metadata, then streams rows a second time into aggregate windows and JDBC batches. The analysis-side memory is bounded by window count and a fixed histogram, not by row count."

**Q2: Why is `null` different from `0.0` in telemetry?**

> "A GPU load of `0.0%` means the GPU is present and idle. `null` means the sensor did not report. Defaulting a missing GPU to zero would falsely classify the run as idle. Keeping `null` lets the classifier emit `UNKNOWN / Insufficient Data` instead of inventing evidence."

**Q3: How are bad timestamps handled?**

> "A single malformed timestamp must not reset the recording to zero. The tracker continues from the last known absolute time using the observed sampling interval, records a warning, and preserves monotonic offsets. Out-of-order rows are clamped so segment durations never go negative."

---

## 3. Module 2: Streaming Time-Window Aggregation

### Key classes

- `com.perftrace.service.analysis.TimeWindow`
- `com.perftrace.service.analysis.TimeWindowAggregator`
- `com.perftrace.service.analysis.BoundedClockHistogram`

### How it works

- Samples arrive in monotonic order from the CSV stream.
- `TimeWindowAggregator.WindowCollector` fills the current 10-second window and emits it when the timestamp crosses a boundary.
- Empty windows are **kept** to represent telemetry gaps; they become `UNKNOWN` time instead of disappearing.
- The final window is clipped to the last observed timestamp, so the run never claims more elapsed time than the recording contains.
- A zero-duration trailing window created on an exact boundary is folded back into the previous window.
- `TimeWindow` stores aggregate sums and counts only. It does **not** expose or retain the source samples.
- CPU and GPU clocks are averaged only over active samples, independently:
  - CPU is active when CPU total load or busiest-thread load is at least 15%.
  - GPU is active when GPU load is at least 15%.
  This prevents an idle GPU clock from lowering a CPU-bound average.

### Interview questions

**Q1: Why a 10-second window?**

> "One-second windows flicker because CPUs boost and drop rapidly; 60-second windows can smooth away a short thermal event. Ten seconds suppresses OS noise while still capturing sustained workload phases."

**Q2: How are gaps handled?**

> "If the recording jumps by more than 1.5 windows (15 seconds), the intermediate windows are emitted with zero samples and marked as gaps. Analysis counts them as `UNKNOWN` and reports a warning. Gap time is excluded from valid-data duration rather than being mislabelled as idle."

**Q3: How are clock averages protected from idle readings?**

> "A CPU or GPU clock is only accumulated when that component independently demonstrates active work. A GPU-bound game therefore does not drag the CPU clock average down with otherwise-idle CPU samples."

---

## 4. Module 3: Rule-Based Classification

### Key classes

- `com.perftrace.service.analysis.CpuBoundDetector`
- `com.perftrace.service.analysis.GpuBoundDetector`
- `com.perftrace.service.analysis.IdleDetector`
- `com.perftrace.service.analysis.ThermalThrottleDetector`

### Classification order

```
CPU total or GPU load missing?          -> UNKNOWN
CPU < 15% AND GPU < 15%?                -> IDLE
CPU total or busiest thread >= 90%
  while GPU < 90%?                      -> CPU_BOUND
GPU >= 95% AND busiest thread < 85%?    -> GPU_BOUND
Busiest-thread reading missing?         -> UNKNOWN
Otherwise                               -> BALANCED
```

`BALANCED` means neither resource crossed PerfTrace's explicit bottleneck thresholds. It is not proof of optimal software execution.

### Thermal throttling

Two evidence levels are reported:

1. **HARDWARE_FLAG** — an explicit HWiNFO throttle/PROCHOT flag was set. This is direct hardware evidence.
2. **HEURISTIC_DETECTED** — no hardware flag was available, but:
   - CPU package ≥ 90°C or GPU ≥ 83°C,
   - active load ≥ 50%,
   - and clock speed is below 88% of the reference baseline.

Temperature alone never triggers a throttle diagnosis.

### Baseline correction

A single mean baseline can be dragged down by a long throttled run. PerfTrace uses two stages:

1. Compute a **preliminary P95** baseline from active, non-hardware-throttled windows.
2. Exclude windows that already look heuristically throttled against that baseline.
3. Compute the **final P90** from the remaining eligible windows.

This makes self-masking less likely, but it is not magic: if more than roughly 95% of active windows are already compressed, the preliminary baseline itself can be affected. The analysis emits a warning whenever windows are excluded from the baseline.

### Interview questions

**Q1: Why rules instead of machine learning?**

> "Hardware diagnostics need reproducibility and an audit trail. A user should see exactly why a window was called CPU-bound: GPU headroom plus CPU total or busiest-thread saturation. A learned model can be opaque and can correlate with irrelevant features. Rules are deterministic and unit-testable."

**Q2: How is a single-thread bottleneck detected when total CPU is only 20%?**

> "The CPU-bound rule also checks the busiest thread. One render thread at 96% can limit frame throughput even when total CPU is low. Conversely, `BALANCED` requires the busiest-thread reading; without it we cannot distinguish balance from a single-thread limit, so the window is `UNKNOWN`."

**Q3: Why is a lower temperature not proof of better performance?**

> "Temperature is an outcome, not throughput. A cooler run may be slower because clocks dropped, the workload changed, or the GPU was waiting on the CPU. PerfTrace compares temperatures alongside sustained clocks and classification, and never turns a temperature drop into a performance-improvement claim."

---

## 5. Module 4: Segment Merging

### Key class

- `com.perftrace.service.analysis.SegmentMergingService`

### How it works

- Adjacent windows with the same classification and compatible throttle state are merged.
- Short unthrottled segments (< 20 seconds) surrounded by the same classification may be smoothed into their neighbours.
- A throttle transition is a hard boundary. `NONE` next to `HARDWARE_FLAG` or `HEURISTIC_DETECTED` is never smoothed away.
- Each merge draft keeps one aggregate `TimeWindow`, not the original source windows or samples. Segment averages and peaks therefore describe the complete final segment with bounded memory.

### Interview questions

**Q1: Why merge segments?**

> "A one-hour recording creates 360 ten-second windows, which is unreadable on a timeline. Merging combines contiguous compatible periods into meaningful phases such as '12 minutes of sustained GPU-bound gameplay'."

**Q2: How are thermal events protected?**

> "Two windows merge only when classification and throttle status match. If throttling starts mid-segment, the evidence state changes and a new segment is forced. Smoothing also refuses to absorb a throttled short segment."

---

## 6. Module 5: Run Comparison and Workload Metadata

### Key classes

- `com.perftrace.service.comparison.RunComparisonService`
- `com.perftrace.dto.RunComparisonDto`
- `com.perftrace.dto.MetricComparisonDto`
- `com.perftrace.entity.WorkloadType`

### Endpoint

`GET /api/runs/compare?a={runIdA}&b={runIdB}`

### Features

1. **Ownership enforcement.** Both runs are loaded with the authenticated user ID. Cross-user access returns `404 Not Found`, preventing ID enumeration.
2. **User-supplied workload type.** Uploads may declare `GAMING`, `CPU_RENDER`, `GPU_RENDER`, `STREAMING`, `PRODUCTIVITY`, `SYNTHETIC_BENCHMARK`, `OTHER`, or leave `UNSPECIFIED`. PerfTrace never infers the type.
3. **Comparability warnings.**
   - unspecified or different workload types,
   - duration ratio greater than 1.3x,
   - idle-ratio difference greater than 25 percentage points,
   - sensor coverage mismatch.
4. **Observed and active duration.** Percentages use observed telemetry duration (excluding gaps) and active duration (excluding idle). When no denominator exists, the percentage is `null`, not a misleading `0%`.
5. **Diagnostic disclaimer.** A temperature reduction is not presented as proof of higher performance.

### Interview questions

**Q1: Why use active workload time as the denominator?**

> "If a run is 20 minutes long with 10 minutes of menu time, dividing CPU-bound time by total duration deflates the percentage. Active time excludes idle and sampling gaps, giving a fair denominator for resource balance during real work."

**Q2: Why not detect the workload type automatically?**

> "Telemetry cannot reliably distinguish a game from a GPU render or a benchmark. Inferring it would create false confidence. The user declares it, and comparisons warn when it is missing or different."

**Q3: How is IDOR prevented?**

> "The repository query includes the authenticated user ID. If either run does not belong to the caller, the service throws a not-found exception. Returning 404 instead of 403 also hides whether the ID exists."

---

## 7. Module 6: Persistence, Transactions, and Chart Downsampling

### Key classes and files

- `com.perftrace.security.SecurityConfig`
- `com.perftrace.security.JwtAuthenticationFilter`
- `com.perftrace.service.analysis.AnalysisRunService`
- `src/main/resources/db/migration/V1__init_schema.sql`
- `src/main/resources/db/migration/V2__workload_metadata.sql`

### How it works

1. **Stateless JWT security.** Passwords are BCrypt-hashed. Only registration, login, API docs, and error handling are public; telemetry endpoints require a Bearer token.
2. **Flyway + schema validation.** `V1` creates users, runs, samples, and segments. `V2` adds `workload_type` with a default of `UNSPECIFIED` and an index. Hibernate runs with `ddl-auto=validate`, so entity/schema drift fails startup.
3. **JDBC batch inserts.** Samples are inserted with `JdbcTemplate.batchUpdate()` in batches of 1,000 instead of loading every entity into Hibernate's persistence context.
4. **Transactional processing.** The spooled file is parsed outside a database transaction. Sample insertion, analysis, and segment persistence run inside one `TransactionTemplate`. Any failure rolls back partial telemetry and segments. A separate transaction then records the run as `FAILED`.
5. **Set-based deletes.** Samples and segments are deleted with bulk statements before the run row, avoiding loading large child collections.
6. **Database-side downsampling.** When a run has more samples than the requested point limit, SQL uses `NTILE` to form buckets and `ROW_NUMBER` to select the minimum- and maximum-stress sample in each bucket. The stress score combines loads, temperatures, clocks, and power. This preserves short spikes without loading every entity.

### Interview questions

**Q1: Why `JdbcTemplate.batchUpdate()` instead of `saveAll()`?**

> "For tens of thousands of samples, `saveAll()` retains every entity in the persistence context and adds dirty-checking overhead. Batched JDBC avoids that and sends multi-row inserts efficiently."

**Q2: Why parse outside the transaction?**

> "Holding a database transaction open during file I/O increases lock time. PerfTrace commits the run row first, does file work outside a transaction, then wraps only persistence in a transaction. If persistence fails, the transaction rolls back cleanly, and a separate transaction marks the run as FAILED without fighting the original row lock."

**Q3: Why downsample in SQL instead of Java?**

> "Loading every sample into JPA just to reduce the chart to 500 points defeats the purpose. `NTILE` plus min/max ranking runs where the data lives and returns only the representative rows."

---

## 8. Verification and Test Strategy

### Backend

- **Surefire unit tests:** 75 tests covering detectors, window aggregation, thermal baseline correction, segment merging, CSV parsing, comparison logic, workload metadata warnings, authentication, and failed-upload rollback.
- **Failsafe H2 integration tests:** 4 tests covering the full lifecycle, workload metadata round-trip, cross-user authorization, and spike-preserving downsampling.
- **Failsafe PostgreSQL Testcontainers tests:** 2 tests that run against real `postgres:16-alpine` with Flyway and `ddl-auto=validate`.
  - Locally these are **skipped** when Docker is unavailable.
  - GitHub Actions runs them on a Docker-enabled runner.
  - H2 success is never described as PostgreSQL proof.

### Frontend

- 13 Vitest tests across 2 files, covering the API client, auth/session behavior, upload form data, sample query parameters, and workload metadata formatting.
- `npm run build` runs TypeScript checking and the Vite production build.

### Why both H2 and PostgreSQL?

H2 is fast and convenient for most regression tests, but its SQL dialect, constraints, and query planner differ from PostgreSQL. The downsampling query uses window functions and PostgreSQL-compatible expressions, and the schema is created by PostgreSQL-specific Flyway migrations. A real PostgreSQL container is the only way to claim database compatibility.

---

## 9. Summary Checklist for Interviews

1. **Defensive ingestion:** two-pass spooling, BOM handling, canonical header aliases, strict `null` semantics, and timestamp recovery.
2. **Bounded memory:** aggregate-only windows, fixed-size clock histograms, JDBC batches, and a documented window cap — not an unsupported O(1) claim.
3. **Explainable rules:** deterministic detectors, busiest-thread evidence, and explicit `UNKNOWN` states.
4. **Thermal rigor:** hardware flags separated from heuristics; preliminary P95 plus final P90 baseline; temperature alone never proves throttling or performance.
5. **Database honesty:** H2 for speed, PostgreSQL Testcontainers for compatibility, and skipped tests reported as skipped.
6. **User-supplied metadata:** workload type is declared, never inferred; comparisons warn when it is missing or different.
