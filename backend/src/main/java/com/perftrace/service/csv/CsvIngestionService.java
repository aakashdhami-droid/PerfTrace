package com.perftrace.service.csv;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import com.perftrace.entity.TelemetrySample;
import com.perftrace.exception.InvalidFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Parses HWiNFO64 CSV exports.
 *
 * <p>The upload path uses {@link #scan(Path)} followed by
 * {@link #streamSamples(Path, CsvScanResult, Consumer)}. The first pass validates headers
 * and computes timestamp metadata without retaining rows; the second pass re-reads the
 * spooled file and emits one normalized sample at a time. Memory therefore does not grow
 * with the number of CSV rows. A bounded number of windows and a fixed-size clock
 * histogram are the only analysis-side accumulators.</p>
 *
 * <p>{@link #parse(InputStream)} is retained for unit tests and small callers. It spools
 * the stream to a temporary file rather than materializing raw CSV rows.</p>
 */
@Service
public class CsvIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CsvIngestionService.class);

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("d.M.yyyy H:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("d.M.yyyy H:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("d/M/yyyy H:mm:ss")
    );

    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("HH:mm:ss.SS"),
        DateTimeFormatter.ofPattern("HH:mm:ss.S"),
        DateTimeFormatter.ofPattern("HH:mm:ss"),
        DateTimeFormatter.ofPattern("H:m:s.SSS"),
        DateTimeFormatter.ofPattern("H:m:s.SS"),
        DateTimeFormatter.ofPattern("H:m:s.S"),
        DateTimeFormatter.ofPattern("H:m:s")
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
        DateTimeFormatter.ofPattern("d.M.yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("M/d/yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy")
    );

    private static final double MIDNIGHT_ROLLOVER_THRESHOLD_SECONDS = 12 * 60 * 60;

    private final HwinfoHeaderMapper headerMapper;
    private final int maxRows;

    public CsvIngestionService(
        HwinfoHeaderMapper headerMapper,
        @Value("${perftrace.analysis.max-rows:500000}") int maxRows
    ) {
        this.headerMapper = headerMapper;
        this.maxRows = maxRows;
    }

    /**
     * Parses an input stream into a materialized result. The stream is spooled to a
     * temporary file first, so parsing itself remains row-streaming internally.
     */
    public CsvParseResult parse(InputStream inputStream) throws IOException {
        Path spool = Files.createTempFile("perftrace-parse-", ".csv");
        try {
            Files.copy(inputStream, spool, StandardCopyOption.REPLACE_EXISTING);
            return parse(spool);
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /**
     * Materializes a parsed result. Used by tests and small callers; uploads use the
     * scan/stream pair instead.
     */
    public CsvParseResult parse(Path csvFile) throws IOException {
        CsvScanResult scan = scan(csvFile);
        List<TelemetrySample> samples = new ArrayList<>();
        streamSamples(csvFile, scan, samples::add);

        CsvParseResult result = new CsvParseResult();
        result.setSamples(samples);
        result.setTotalRowsParsed(scan.getTotalRowsParsed());
        result.setValidRows(scan.getValidRows());
        result.setRejectedRows(scan.getRejectedRows());
        result.setTotalDurationSeconds(scan.getTotalDurationSeconds());
        result.setRecognizedSensors(scan.getRecognizedSensors());
        result.setMissingSensors(scan.getMissingSensors());
        result.setWarnings(scan.getWarnings());
        return result;
    }

    /**
     * First streaming pass: validates the header, counts accepted/rejected rows, and
     * computes the timestamp baseline needed by the second pass. No samples are retained.
     */
    public CsvScanResult scan(Path csvFile) throws IOException {
        CsvScanResult result = new CsvScanResult();
        List<String> warnings = new ArrayList<>();

        try (Reader reader = openReader(csvFile)) {
            CSVReader csvReader = newCsvReader(reader);
            HeaderInfo header = readHeader(csvReader);
            result.setColumnMap(header.columnMap());
            result.setRecognizedSensors(header.recognizedSensors());
            result.setMissingSensors(header.missingSensors());

            boolean hasTimeColumn = header.columnMap().containsKey(NormalizedSensorField.TIMESTAMP);
            boolean hasDateColumn = header.columnMap().containsKey(NormalizedSensorField.DATE);
            boolean hasTimestampColumns = hasTimeColumn || hasDateColumn;

            result.setHasTimestampColumns(hasTimestampColumns);
            result.setHasDateColumn(hasDateColumn);

            if (!hasTimestampColumns) {
                warnings.add("No timestamp column detected. Estimating a 1.0-second interval between samples.");
            }

            TimestampTracker tracker = new TimestampTracker(hasTimestampColumns, hasDateColumn);
            int totalRows = 0;
            int validRows = 0;
            int rejectedRows = 0;
            int acceptedIndex = 0;
            int lastAcceptedIndex = -1;

            Double firstParsedAbsolute = null;
            int firstParsedIndex = -1;
            Double firstAcceptedAbsolute = null;
            Double lastAcceptedAbsolute = null;

            String[] row;
            try {
                while ((row = csvReader.readNext()) != null) {
                    totalRows++;
                    if (totalRows > maxRows) {
                        warnings.add("Enforced maximum row limit (" + maxRows + "). Remaining rows skipped.");
                        break;
                    }
                    if (row.length == 0 || (row.length == 1 && row[0].isBlank())) continue;
                    if (isUnitHeaderRow(row)) continue;

                    ResolvedTimestamp resolved = tracker.resolve(row, header.columnMap());
                    TelemetrySample sample = parseSample(row, header.columnMap());

                    if (sample.getCpuTotalLoad() != null || sample.getGpuLoad() != null) {
                        if (resolved.parsed() && firstParsedAbsolute == null) {
                            firstParsedAbsolute = resolved.absoluteSeconds();
                            firstParsedIndex = acceptedIndex;
                        }
                        if (firstAcceptedAbsolute == null) {
                            firstAcceptedAbsolute = resolved.absoluteSeconds();
                        }
                        lastAcceptedAbsolute = resolved.absoluteSeconds();
                        lastAcceptedIndex = acceptedIndex;
                        acceptedIndex++;
                        validRows++;
                    } else {
                        rejectedRows++;
                    }
                }
            } catch (CsvValidationException e) {
                warnings.add("CSV row formatting error encountered: " + e.getMessage());
            }

            if (validRows < 3) {
                throw new InvalidFileException("File contains insufficient valid data rows (found " +
                    validRows + ", minimum 3 required for telemetry analysis).");
            }

            double fallbackInterval = tracker.getFallbackInterval();
            double baseline;
            if (firstParsedAbsolute != null) {
                baseline = firstParsedAbsolute - (firstParsedIndex * fallbackInterval);
            } else {
                baseline = firstAcceptedAbsolute != null ? firstAcceptedAbsolute : 0.0;
            }

            double lastOffset;
            if (firstParsedAbsolute != null && lastAcceptedIndex < firstParsedIndex) {
                lastOffset = lastAcceptedIndex * fallbackInterval;
            } else {
                lastOffset = Math.max(0.0, (lastAcceptedAbsolute != null ? lastAcceptedAbsolute : 0.0) - baseline);
            }

            result.setFirstTimestampFound(firstParsedAbsolute != null);
            result.setFirstParsedIndex(Math.max(0, firstParsedIndex));
            result.setFallbackIntervalSeconds(fallbackInterval);
            result.setBaselineAbsoluteSeconds(baseline);
            result.setTotalRowsParsed(totalRows);
            result.setValidRows(validRows);
            result.setRejectedRows(rejectedRows);
            result.setTotalDurationSeconds(Math.round(lastOffset * 100.0) / 100.0);

            if (tracker.getInvalidTimestampCount() > 0) {
                warnings.add(tracker.getInvalidTimestampCount() + " row(s) had missing or unparseable timestamps; " +
                    "time offsets were estimated from the previous sample interval.");
            }
            if (tracker.getOutOfOrderCount() > 0) {
                warnings.add(tracker.getOutOfOrderCount() + " out-of-order timestamp(s) detected; monotonic ordering was preserved.");
            }
            if (firstParsedIndex > 0) {
                warnings.add("The first valid timestamp occurred after " + firstParsedIndex +
                    " row(s) with invalid timestamps; early offsets were estimated.");
            }
        }

        result.setWarnings(warnings);
        return result;
    }

    /**
     * Second streaming pass: re-reads the spooled CSV and emits samples with final offsets.
     * The consumer is called one row at a time; no sample list is retained here.
     */
    public void streamSamples(Path csvFile, CsvScanResult scan, Consumer<TelemetrySample> consumer)
        throws IOException {

        try (Reader reader = openReader(csvFile)) {
            CSVReader csvReader = newCsvReader(reader);
            HeaderInfo header = readHeader(csvReader);

            TimestampTracker tracker = new TimestampTracker(
                scan.isHasTimestampColumns(),
                scan.isHasDateColumn()
            );

            int totalRows = 0;
            int acceptedIndex = 0;
            double lastEmittedOffset = -1.0;

            String[] row;
            try {
                while ((row = csvReader.readNext()) != null) {
                    totalRows++;
                    if (totalRows > maxRows) break;
                    if (row.length == 0 || (row.length == 1 && row[0].isBlank())) continue;
                    if (isUnitHeaderRow(row)) continue;

                    ResolvedTimestamp resolved = tracker.resolve(row, header.columnMap());
                    TelemetrySample sample = parseSample(row, header.columnMap());

                    if (sample.getCpuTotalLoad() == null && sample.getGpuLoad() == null) {
                        continue;
                    }

                    double offset;
                    if (scan.isFirstTimestampFound() && acceptedIndex < scan.getFirstParsedIndex()) {
                        offset = acceptedIndex * scan.getFallbackIntervalSeconds();
                    } else {
                        offset = Math.max(0.0, resolved.absoluteSeconds() - scan.getBaselineAbsoluteSeconds());
                    }
                    // The parser already clamps backwards timestamps; repeat the guarantee at
                    // the emission boundary so downstream consumers can assume monotonicity.
                    if (offset < lastEmittedOffset) offset = lastEmittedOffset;
                    offset = Math.round(offset * 100.0) / 100.0;

                    sample.setTimeOffsetSeconds(offset);
                    lastEmittedOffset = offset;
                    acceptedIndex++;
                    consumer.accept(sample);
                }
            } catch (CsvValidationException e) {
                // The scan pass already recorded this warning. Stop at the same row so
                // counts and emissions stay consistent.
                log.debug("Stopping sample stream after CSV formatting error: {}", e.getMessage());
            }
        }
    }

    private HeaderInfo readHeader(CSVReader csvReader) throws IOException {
        String[] headers;
        try {
            headers = csvReader.readNext();
        } catch (CsvValidationException e) {
            throw new InvalidFileException("Malformed CSV header row: " + e.getMessage());
        }

        if (headers == null || headers.length == 0) {
            throw new InvalidFileException("Uploaded CSV file is empty");
        }

        Map<NormalizedSensorField, Integer> columnMap = headerMapper.mapHeaders(headers);
        List<String> missingCritical = headerMapper.getMissingCriticalSensors(columnMap);
        if (!missingCritical.isEmpty()) {
            throw new InvalidFileException("CSV does not contain required telemetry sensors: " +
                String.join(", ", missingCritical) + ". Please ensure HWiNFO64 CPU and GPU logging are enabled.");
        }

        return new HeaderInfo(
            columnMap,
            headerMapper.getRecognizedSensorNames(columnMap),
            headerMapper.getMissingSensorNames(columnMap)
        );
    }

    private Reader openReader(Path csvFile) throws IOException {
        InputStream raw = Files.newInputStream(csvFile);
        BufferedInputStream bis = new BufferedInputStream(raw);
        bis.mark(4);
        byte[] bom = new byte[4];
        int n = bis.read(bom, 0, 4);
        bis.reset();

        Charset charset = StandardCharsets.UTF_8;
        int skipBytes = 0;

        if (n >= 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
            charset = StandardCharsets.UTF_8;
            skipBytes = 3;
        } else if (n >= 2 && bom[0] == (byte) 0xFF && bom[1] == (byte) 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            skipBytes = 2;
        } else if (n >= 2 && bom[0] == (byte) 0xFE && bom[1] == (byte) 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            skipBytes = 2;
        }

        if (skipBytes > 0) {
            long skipped = bis.skip(skipBytes);
            log.debug("Skipped {} BOM bytes", skipped);
        }

        return new InputStreamReader(bis, charset);
    }

    private CSVReader newCsvReader(Reader reader) {
        return new CSVReaderBuilder(reader)
            .withCSVParser(new CSVParserBuilder().withSeparator(',').withQuoteChar('"').build())
            .build();
    }

    private TelemetrySample parseSample(String[] row, Map<NormalizedSensorField, Integer> columnMap) {
        TelemetrySample sample = new TelemetrySample();

        sample.setCpuTotalLoad(parseDouble(row, columnMap.get(NormalizedSensorField.CPU_TOTAL_LOAD), 0.0, 100.0));
        sample.setCpuMaxThreadLoad(parseDouble(row, columnMap.get(NormalizedSensorField.CPU_MAX_THREAD_LOAD), 0.0, 100.0));
        sample.setCpuTempC(parseDouble(row, columnMap.get(NormalizedSensorField.CPU_TEMP), -40.0, 150.0));
        sample.setCpuClockMhz(parseDouble(row, columnMap.get(NormalizedSensorField.CPU_CLOCK), 0.0, 10000.0));

        sample.setGpuLoad(parseDouble(row, columnMap.get(NormalizedSensorField.GPU_LOAD), 0.0, 100.0));
        sample.setGpuTempC(parseDouble(row, columnMap.get(NormalizedSensorField.GPU_TEMP), -40.0, 150.0));
        sample.setGpuClockMhz(parseDouble(row, columnMap.get(NormalizedSensorField.GPU_CLOCK), 0.0, 10000.0));

        sample.setCpuThrottleFlag(parseBooleanFlag(row, columnMap.get(NormalizedSensorField.CPU_THROTTLE)));
        sample.setGpuThrottleFlag(parseBooleanFlag(row, columnMap.get(NormalizedSensorField.GPU_THROTTLE)));

        sample.setRamUsagePercent(parseDouble(row, columnMap.get(NormalizedSensorField.RAM_USAGE), 0.0, 100.0));
        sample.setCpuPowerW(parseDouble(row, columnMap.get(NormalizedSensorField.CPU_POWER), 0.0, 2000.0));
        sample.setGpuPowerW(parseDouble(row, columnMap.get(NormalizedSensorField.GPU_POWER), 0.0, 2000.0));

        return sample;
    }

    private boolean isUnitHeaderRow(String[] row) {
        int unitCount = 0;
        for (String cell : row) {
            String trimmed = cell.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) unitCount++;
            else if (trimmed.equalsIgnoreCase("yes/no") || trimmed.equalsIgnoreCase("true/false")) unitCount++;
        }
        return unitCount >= 2;
    }

    private Double parseDouble(String[] row, Integer colIdx, double min, double max) {
        if (colIdx == null || colIdx >= row.length) return null;
        String val = row[colIdx];
        if (val == null) return null;
        val = val.replace('\u00A0', ' ').trim();
        if (val.isBlank()) return null;

        String lower = val.toLowerCase(Locale.ROOT);
        if (lower.equals("n/a") || lower.equals("na") || lower.equals("null")
                || lower.equals("-") || lower.equals("--") || lower.equals("nan")) {
            return null;
        }

        boolean gigaHertz = lower.endsWith("ghz");
        String cleaned = lower.replaceAll("\\s*(%|°c|ºc|mhz|ghz|hz|w|v|rpm|mb/s|gb/s)\\s*$", "");

        // Accept both "1,234.5" (thousands separator) and "1234,5" (decimal comma).
        if (cleaned.matches("-?\\d{1,3}(,\\d{3})+(\\.\\d+)?")) {
            cleaned = cleaned.replace(",", "");
        } else if (cleaned.matches("-?\\d+,\\d+")) {
            cleaned = cleaned.replace(',', '.');
        }

        try {
            double d = Double.parseDouble(cleaned.trim());
            if (gigaHertz) d *= 1000.0;
            if (!Double.isFinite(d) || d < min || d > max) return null;
            return Math.round(d * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean parseBooleanFlag(String[] row, Integer colIdx) {
        if (colIdx == null || colIdx >= row.length) return null;
        String val = row[colIdx].trim().toLowerCase(Locale.ROOT);
        if (val.isBlank() || val.equals("n/a") || val.equals("-")) return null;
        if (val.equals("yes") || val.equals("true") || val.equals("1")) return true;
        if (val.equals("no") || val.equals("false") || val.equals("0")) return false;
        return null;
    }

    private Double parseDateTimeToSeconds(String dateStr, String timeStr) {
        boolean hasDate = dateStr != null && !dateStr.isBlank();
        boolean hasTime = timeStr != null && !timeStr.isBlank();

        if (hasDate && hasTime) {
            String normalized = dateStr.trim() + " " + timeStr.trim().replace("T", " ");
            for (DateTimeFormatter dtf : DATE_TIME_FORMATTERS) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(normalized, dtf);
                    return (double) ldt.toEpochSecond(ZoneOffset.UTC);
                } catch (DateTimeParseException ignored) {}
            }
        }
        if (hasTime) {
            Double fromTime = parseTimeValueToSeconds(timeStr);
            if (fromTime != null) return fromTime;
        }
        if (hasDate) {
            for (DateTimeFormatter dtf : DATE_FORMATTERS) {
                try {
                    LocalDate ld = LocalDate.parse(dateStr.trim(), dtf);
                    return (double) ld.toEpochDay() * 86400.0;
                } catch (DateTimeParseException ignored) {}
            }
        }
        return null;
    }

    private Double parseTimeValueToSeconds(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) return null;
        String trimmed = rawTime.trim();

        // Try direct numeric seconds (e.g., "12.5" or "100")
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {}

        // Try standard time formats
        for (DateTimeFormatter dtf : TIME_FORMATTERS) {
            try {
                LocalTime lt = LocalTime.parse(trimmed, dtf);
                return (double) lt.toNanoOfDay() / 1_000_000_000.0;
            } catch (DateTimeParseException ignored) {}
        }

        // If date-time string, parse full date+time to epoch seconds
        if (trimmed.contains(" ") || trimmed.contains("T")) {
            String normalized = trimmed.replace("T", " ");
            for (DateTimeFormatter dtf : DATE_TIME_FORMATTERS) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(normalized, dtf);
                    return (double) ldt.toEpochSecond(ZoneOffset.UTC);
                } catch (DateTimeParseException ignored) {}
            }
            // Fall through to take time portion
            String[] parts = normalized.split(" ");
            if (parts.length >= 2) {
                return parseTimeValueToSeconds(parts[1]);
            }
        }

        return null;
    }

    private record HeaderInfo(
        Map<NormalizedSensorField, Integer> columnMap,
        List<String> recognizedSensors,
        List<String> missingSensors
    ) {}

    private record ResolvedTimestamp(boolean parsed, double absoluteSeconds) {}

    private final class TimestampTracker {

        private final boolean hasTimestampColumns;
        private final boolean hasDateColumn;

        private double dayRolloverOffset = 0.0;
        private double fallbackInterval = 1.0;
        private double syntheticTime = 0.0;
        private Double lastRawTime = null;
        private Double lastAbsolute = null;
        private int invalidTimestampCount = 0;
        private int outOfOrderCount = 0;

        private TimestampTracker(boolean hasTimestampColumns, boolean hasDateColumn) {
            this.hasTimestampColumns = hasTimestampColumns;
            this.hasDateColumn = hasDateColumn;
        }

        private ResolvedTimestamp resolve(String[] row, Map<NormalizedSensorField, Integer> columnMap) {
            Double currentTimestampSec = null;
            if (hasTimestampColumns) {
                String dateStr = null;
                String timeStr = null;
                Integer dateIndex = columnMap.get(NormalizedSensorField.DATE);
                Integer timeIndex = columnMap.get(NormalizedSensorField.TIMESTAMP);
                if (dateIndex != null && dateIndex < row.length) dateStr = row[dateIndex];
                if (timeIndex != null && timeIndex < row.length) timeStr = row[timeIndex];
                currentTimestampSec = parseDateTimeToSeconds(dateStr, timeStr);
            }

            double absoluteTime;
            boolean parsed = currentTimestampSec != null;

            if (parsed) {
                // Handle midnight rollover for time-only timestamps. A backwards jump of
                // a few seconds is a clock adjustment, not a new day.
                if (!hasDateColumn && lastRawTime != null
                        && lastRawTime - currentTimestampSec > MIDNIGHT_ROLLOVER_THRESHOLD_SECONDS) {
                    dayRolloverOffset += 86400.0;
                }
                if (lastRawTime != null) {
                    double delta = currentTimestampSec - lastRawTime;
                    if (delta > 0 && delta < 3600) {
                        fallbackInterval = delta;
                    }
                }
                lastRawTime = currentTimestampSec;

                absoluteTime = currentTimestampSec + dayRolloverOffset;
                if (lastAbsolute != null && absoluteTime < lastAbsolute) {
                    outOfOrderCount++;
                    absoluteTime = lastAbsolute;
                }
            } else if (hasTimestampColumns) {
                // A single malformed timestamp must not reset the timeline to zero.
                // Continue from the last known time using the observed sampling interval.
                invalidTimestampCount++;
                absoluteTime = (lastAbsolute != null) ? lastAbsolute + fallbackInterval : syntheticTime;
                syntheticTime += fallbackInterval;
            } else {
                absoluteTime = syntheticTime;
                syntheticTime += 1.0;
            }

            lastAbsolute = absoluteTime;
            return new ResolvedTimestamp(parsed, absoluteTime);
        }

        private double getFallbackInterval() { return fallbackInterval; }
        private int getInvalidTimestampCount() { return invalidTimestampCount; }
        private int getOutOfOrderCount() { return outOfOrderCount; }
    }
}
