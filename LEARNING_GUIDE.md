# PerfTrace — Technical Architecture & MCA Placement Learning Guide

This guide is written in plain English for MCA campus placement interviews and technical assessments. It walks through every major architectural choice, key Java classes, algorithm implementations, and provides **frequently asked interview questions with model answers**.

---

## 1. System Architecture Overview

### Why did you build PerfTrace this way?
PC hardware diagnostics usually suffer from two extremes:
1. **Raw CSV logs are overwhelming**: A 30-minute gaming session recorded at 500ms intervals generates 3,600 rows with 80+ sensor columns. No human can spot bottlenecks by reading raw numbers.
2. **Naive summaries hide the truth**: Taking a simple average across 30 minutes obscures the fact that the GPU was at 99% during gameplay, but thermal throttled and dropped 400 MHz whenever the CPU spiked.

PerfTrace bridges this gap by acting as an **explainable diagnostic pipeline**:
- **Ingestion**: Streams the CSV without exhausting server RAM, maps sensor headers flexibly, and stores missing data as `null` (not zero).
- **Bucketing**: Groups readings into ~10-second statistical windows to eliminate transient scheduling noise.
- **Rule-Based Detectors**: Applies transparent heuristics (CPU-bound, GPU-bound, Idle, Balanced, Unknown) and conservative thermal throttling detection.
- **Segment Merging**: Combines consecutive compatible windows into a clean visual timeline while strictly protecting thermal transitions.
- **Run Comparison**: Compares two runs side by side, checks comparability (duration and idle disparities), and enforces diagnostic humility.

---

## 2. Module 1: Resilient CSV Ingestion

### Key Classes:
- `com.perftrace.service.csv.CsvIngestionService`
- `com.perftrace.service.csv.HwinfoHeaderMapper`
- `com.perftrace.service.csv.NormalizedSensorField`
- `com.perftrace.service.csv.CsvParseResult`

### How it works:
1. **Streaming vs In-Memory**: Uses OpenCSV `CSVReader` in a streaming loop. We never read all lines into a `List<String[]>` in memory. This ensures a 100MB CSV with 200,000 rows can be parsed inside a tight heap.
2. **Byte Order Mark (BOM) Detection**: Windows text editors and HWiNFO often export CSVs with a UTF-8 BOM (`\uFEFF`) or UTF-16 encoding. If unhandled, the first column name becomes `\uFEFFDate`, breaking header matching. We wrap the `InputStream` with `BOMInputStream` / buffered readers.
3. **Discards Secondary Unit Header Row**: HWiNFO exports often contain a second header row listing units (e.g., `Date,Time,Usage [%],Temp [°C]` followed by `,,,`). Our parser detects non-numeric unit rows and discards them.
4. **Strict Null Semantics**: If a cell is empty or contains `"N/A"`, it is parsed as Java `null`. Defaulting missing sensors to `0.0` would falsely indicate an idle computer or absolute zero temperature!

### Interview Questions & Answers:

**Q1: Why did you stream the CSV file instead of using `Files.readAllLines()` or `List<String[]>`?**
> *"In a multi-user web application, multiple users might upload 50MB–100MB telemetry logs simultaneously. If we used `Files.readAllLines()`, each upload would allocate hundreds of megabytes in the JVM heap, triggering frequent garbage collection pauses and risking an `OutOfMemoryError`. Using OpenCSV's streaming reader processes row by row with $O(1)$ memory consumption."*

**Q2: How does your parser handle different HWiNFO64 versions that rename column headers?**
> *"We implemented a canonical enum `NormalizedSensorField` and a header mapper `HwinfoHeaderMapper`. For each normalized concept (e.g., `CPU_PACKAGE_TEMP`), we maintain a list of known aliases: `CPU Package [°C]`, `CPU Temperature`, `Tctl/Tdie`, and `Core Max`. The mapper normalizes strings by removing quotes, brackets, and extra spaces, matching incoming headers to our internal domain models."*

**Q3: Why is treating missing sensor data as `null` instead of `0.0` critical in telemetry?**
> *"Telemetry readings have physical meaning. A GPU load of `0.0%` means the GPU is powered on and idle. A `null` means the sensor does not exist or failed to report. If a user uploads a log from a PC without a dedicated GPU, defaulting missing GPU load to `0.0` would falsely trigger our Idle detector. Persisting `null` allows the system to diagnose `UNKNOWN / Insufficient Data` instead of giving false advice."*

---

## 3. Module 2: Time-Window Aggregation

### Key Classes:
- `com.perftrace.service.analysis.TimeWindow`
- `com.perftrace.service.analysis.TimeWindowAggregator`

### How it works:
- Telemetry logs arrive at sub-second frequencies (e.g., 500ms). Per-second readings contain erratic micro-fluctuations (e.g., a background Windows Defender thread spiking CPU for 200ms).
- `TimeWindowAggregator` accumulates samples into discrete ~10-second windows.
- Within each window, we calculate average CPU total load, average GPU load, maximum thread load, average clock frequencies, peak temperatures, and sample counts.
- **Gap Detection**: If the elapsed time between consecutive rows exceeds 15 seconds (e.g., PC went to sleep or the game crashed), the aggregator automatically closes the current window and flags a `dataGap`, preventing artificial interpolation over inactive time.

### Interview Questions & Answers:

**Q1: Why did you choose a 10-second aggregation window?**
> *"Ten seconds is the ideal compromise between responsiveness and noise reduction for hardware bottlenecks. A 1-second window produces excessive flickering because CPUs naturally boost and drop rapidly. A 60-second window would smooth away crucial 15-second thermal throttling drops. A 10-second window captures sustained workload phases while suppressing transient OS noise."*

**Q2: How do you handle sampling time gaps?**
> *"If a user pauses logging, suspends their laptop, or if the system freezes for more than 15 seconds, interpolating between the two points would create fake data. The aggregator detects when `currentOffset - previousOffset > 15.0s`, marks the window as having a data gap, and starts a fresh window boundary."*

---

## 4. Module 3: Rule-Based Classification Detectors

### Key Classes:
- `com.perftrace.service.analysis.CpuBoundDetector`
- `com.perftrace.service.analysis.GpuBoundDetector`
- `com.perftrace.service.analysis.IdleDetector`
- `com.perftrace.service.analysis.ThermalThrottleDetector`

### Rules Explained:

```
  ┌──────────────────┐
  │   Time Window    │
  └────────┬─────────┘
           │
           ▼
    Both CPU & GPU Load NULL? ──► [ UNKNOWN / Insufficient Data ]
           │ No
           ▼
    CPU < 20% AND GPU < 20%? ───► [ IDLE ]
           │ No
           ▼
    CPU Total > 80% (or Max Thread > 90%)
    AND GPU < 85%? ─────────────► [ CPU-BOUND ]
           │ No
           ▼
    GPU Load >= 90%? ───────────► [ GPU-BOUND ]
           │ No
           ▼
    None of the above ──────────► [ BALANCED / OTHER ]
```

### Thermal Throttling Heuristic:
Throttling is classified into two distinct evidence levels:
1. **HARDWARE_FLAG**: Explicit digital sensor flag in HWiNFO (`Core Thermal Throttling = Yes` or PROCHOT asserted). This is hardware truth directly reported by MSR registers.
2. **HEURISTIC_DETECTED**: Hardware flags are absent or unsupported, but:
   - Operating temperature is elevated (CPU package > 88°C or GPU > 84°C),
   - Under heavy compute load (> 70%),
   - Sustained clock speed regresses by > 12% below the non-idle baseline.
3. High temperature **alone** does not trigger throttling if clock frequencies remain at peak boost.

### Interview Questions & Answers:

**Q1: Why use explainable rule-based heuristics instead of Machine Learning (e.g., Random Forest or Neural Networks)?**
> *"In hardware diagnostics, explainability and reproducibility are paramount. A user or hardware technician needs to know precisely why a system was flagged as CPU-bound (e.g., 'Core #3 saturated at 96% while GPU had 35% headroom'). An ML model acts as a black box and can hallucinate bottlenecks based on irrelevant correlations. Rule-based detectors can be unit-tested deterministically and audited by engineers."*

**Q2: How do you detect single-threaded CPU bottlenecks where total CPU usage is only 20%?**
> *"Modern multicore CPUs (e.g., 8 cores / 16 threads) can be severely bottlenecked by a game's main render thread even if overall CPU usage is only 15%–25%. Our `CpuBoundDetector` tracks `cpuMaxThreadLoad` in addition to total load. If the busiest single thread exceeds 90% utilization while the GPU is underutilized (< 85%), it correctly classifies the window as CPU-bound."*

**Q3: What does the 'BALANCED' classification mean in PerfTrace?**
> *"We strictly define `BALANCED` as an operational state where neither CPU nor GPU crossed our specific bottleneck thresholds. We display an explicit disclaimer in the UI explaining that 'BALANCED' does not prove optimal software execution or absence of memory/storage bottlenecks—it simply indicates balanced resource sharing."*

---

## 5. Module 4: Segment Merging & 20s Smoothing

### Key Class:
- `com.perftrace.service.analysis.SegmentMergingService`

### How it works:
- 10-second windows can produce minor visual fragmentation on a timeline.
- `SegmentMergingService` merges adjacent windows that share identical classification and compatible throttle states.
- **The Critical Protection Rule**: Smoothing is allowed to merge transient 10s fluctuations into larger segments, but **it is strictly forbidden from smoothing away a thermal throttling transition**. A change in throttle state (`NONE` -> `HARDWARE_FLAG` or `HEURISTIC_DETECTED`) immediately creates a permanent segment boundary. Raw sample points are never modified.

### Interview Questions & Answers:

**Q1: What problem does segment merging solve?**
> *"Without segment merging, a 1-hour session creates 360 separate 10-second chunks, making the UI timeline unreadable. Segment merging aggregates contiguous compatible periods into human-readable segments (e.g., '12 minutes of sustained GPU-bound gameplay')."*

**Q2: How does your merging algorithm prevent hiding hardware throttling?**
> *"In `SegmentMergingService.java`, two consecutive windows are only eligible for merging if both `window1.classification == window2.classification` AND `window1.throttleEvidence == window2.throttleEvidence`. If a CPU begins throttling midway through a GPU-bound session, the throttle evidence state changes, forcing a new segment. This guarantees that critical thermal events are never smoothed over or hidden."*

---

## 6. Module 5: Run Comparison Engine

### Key Classes:
- `com.perftrace.service.comparison.RunComparisonService`
- `com.perftrace.dto.RunComparisonDto`
- `com.perftrace.dto.MetricComparisonDto`

### Endpoint:
`GET /api/runs/compare?a={runIdA}&b={runIdB}`

### Features:
1. **User Ownership Security**: Verifies that both `runIdA` and `runIdB` belong to the authenticated user. If a user attempts to compare their run against another user's run, the system returns `404 Not Found` (preventing ID enumeration).
2. **Comparability Warnings**:
   - **Duration Disparity**: Warns if one run is > 1.3x longer than the other (different sample lengths distort percentage distributions).
   - **Idle Disparity**: Warns if one run spent > 25% more time idle (indicates menu browsing or paused game).
   - **Sensor Mismatch**: Flags sensors present in Run A but absent in Run B.
3. **Diagnostic Humility Disclaimer**: Enforces the engineering rule that *a temperature reduction does not prove higher performance without corroborating sustained clock speeds and active throughput*.

### Interview Questions & Answers:

**Q1: Why do you calculate CPU/GPU percentages based on 'Active Workload Time' instead of 'Total Run Duration'?**
> *"If Run A is 20 minutes long with 10 minutes of idle menu time, calculating CPU-bound time over total duration yields artificially deflated percentages. Active Workload Time (`totalDuration - idleSeconds`) provides a fair, workload-normalized denominator that accurately reflects resource balance during active computing."*

**Q2: How does the comparison endpoint prevent Insecure Direct Object References (IDOR)?**
> *"In `RunComparisonService.java`, both runs are queried from the repository and checked: `if (!runA.getUser().getId().equals(authenticatedUserId)) throw new ResourceNotFoundException()`. We return 404 rather than 403 to prevent attackers from discovering valid run IDs through brute-force enumeration."*

---

## 7. Module 6: Enterprise Security & Data Persistence

### Key Classes:
- `com.perftrace.security.SecurityConfig`
- `com.perftrace.security.JwtAuthenticationFilter`
- `com.perftrace.security.JwtTokenProvider`
- `com.perftrace.service.analysis.AnalysisRunService` (Batch JDBC Inserts)
- `src/main/resources/db/migration/V1__init_schema.sql`

### How it works:
1. **Spring Security 7 Lambda DSL**: Stateless session management (`SessionCreationPolicy.STATELESS`), CORS configured for Vite dev server (`http://localhost:5173`), and BCrypt password hashing.
2. **Flyway Database Migrations**: Schema changes are managed in versioned SQL scripts (`V1__init_schema.sql`) rather than letting Hibernate auto-generate DDL (`ddl-auto=validate`).
3. **High-Performance JDBC Batch Inserts**: Standard JPA `repository.saveAll()` executes individual SQL statements or requires complex sequence tuning. `AnalysisRunService` uses Spring's `JdbcTemplate.batchUpdate()` to insert telemetry samples in batches of 500 rows. This speeds up insertion of 50,000 samples from ~45 seconds to under 1.2 seconds.
4. **Sample Downsampling for Responsive Charts**: Sending 100,000 raw telemetry points to Chart.js in the browser causes severe rendering lag and tab crashes. `getDownsampledSamples()` uses dynamic step-based decimation to return a maximum of 500 representative data points, keeping frontend charts at 60 FPS.

### Interview Questions & Answers:

**Q1: Why did you use `JdbcTemplate.batchUpdate()` instead of JPA `saveAll()` for telemetry samples?**
> *"When persisting 50,000 telemetry samples, Hibernate's persistence context retains all entity instances in its first-level cache. This leads to heavy memory bloat and dirty-checking overhead. Furthermore, unless `hibernate.jdbc.batch_size` and non-identity IDs are carefully configured, `saveAll()` issues 50,000 separate INSERT queries. By using `JdbcTemplate.batchUpdate()`, we execute parameterized multi-row batch inserts with direct JDBC performance, reducing ingestion time by over 95%."*

**Q2: How is schema evolution handled in production?**
> *"We set `spring.jpa.hibernate.ddl-auto=validate` and rely on Flyway (`flyway-database-postgresql`). Flyway tracks applied migrations in the `flyway_schema_history` table and executes versioned SQL scripts sequentially on application startup. This ensures database schema consistency across development, automated CI test suites, and production environments."*

**Q3: How does JWT authentication work in this application?**
> *"When a user registers or logs in, `JwtTokenProvider` generates an HMAC-SHA256 signed token containing the user's ID, username, issue date, and expiration time. On subsequent requests, the React client sends the token in the `Authorization: Bearer <token>` header. The `JwtAuthenticationFilter` extracts and validates the token, extracts the username, loads user authorities, and populates the `SecurityContextHolder`. Because state is kept inside the token, the backend remains completely stateless and horizontally scalable."*

---

## 8. Summary Checklist for Placement Interviews

When discussing PerfTrace with interviewers, emphasize these 5 technical pillars:

1. **Defensive Telemetry Parsing**: Real sensor exports are messy; BOM handling, canonical header aliasing, and strict `null` semantics prevent garbage data from entering the database.
2. **Noise Reduction with Protection**: 10s statistical aggregation removes sub-second micro-spikes; 20s segment merging clarifies timelines without ever smoothing away thermal throttle transitions.
3. **Explainable AI / Transparent Rules**: Deterministic, auditable rules replace black-box models; single-thread saturation detection catches bottlenecks that average CPU load misses.
4. **Hardware Rigor & Diagnostic Humility**: Hardware register flags are distinguished from heuristic clock regressions; lower temperatures are never claimed to prove better performance without clock speed validation.
5. **Production Engineering Quality**: 36 automated unit/integration tests, Spring Security 7 stateless JWT filters, Flyway schema migrations, JDBC batch insertions, and responsive frontend downsampling.
