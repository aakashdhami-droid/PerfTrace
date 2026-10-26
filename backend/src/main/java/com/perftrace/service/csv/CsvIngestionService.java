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

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class CsvIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CsvIngestionService.class);

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
     * Parses an input stream of CSV data into normalized TelemetrySample objects.
     */
    public CsvParseResult parse(InputStream inputStream) throws IOException {
        // Read initial bytes to detect encoding and BOM
        BufferedInputStream bis = new BufferedInputStream(inputStream);
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

        Reader reader = new InputStreamReader(bis, charset);
        return parseReader(reader);
    }

    public CsvParseResult parseReader(Reader reader) throws IOException {
        CsvParseResult result = new CsvParseResult();

        CSVReader csvReader = new CSVReaderBuilder(reader)
            .withCSVParser(new CSVParserBuilder().withSeparator(',').withQuoteChar('"').build())
            .build();

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

        result.setRecognizedSensors(headerMapper.getRecognizedSensorNames(columnMap));
        result.setMissingSensors(headerMapper.getMissingSensorNames(columnMap));

        // Read subsequent rows
        List<TelemetrySample> samples = new ArrayList<>();
        int totalRows = 0;
        int validRows = 0;
        int rejectedRows = 0;

        String[] row;
        Double firstTimestampSeconds = null;
        double syntheticTimeOffset = 0.0;
        boolean hasTimeColumn = columnMap.containsKey(NormalizedSensorField.TIMESTAMP);

        if (!hasTimeColumn) {
            result.getWarnings().add("No timestamp column detected. Estimating 1.0-second interval between samples.");
        }

        try {
            while ((row = csvReader.readNext()) != null) {
                totalRows++;
                if (totalRows > maxRows) {
                    result.getWarnings().add("Enforced maximum row limit (" + maxRows + "). Remaining rows skipped.");
                    break;
                }

                // Skip blank lines
                if (row.length == 0 || (row.length == 1 && row[0].isBlank())) {
                    continue;
                }

                // Check for HWiNFO64 secondary unit header row (e.g., "[%]", "[°C]", "[MHz]")
                if (isUnitHeaderRow(row)) {
                    continue;
                }

                // Compute time offset
                Double currentTimestampSec = null;
                if (hasTimeColumn) {
                    int timeIdx = columnMap.get(NormalizedSensorField.TIMESTAMP);
                    if (timeIdx < row.length) {
                        currentTimestampSec = parseTimeValueToSeconds(row[timeIdx]);
                    }
                }

                double timeOffset;
                if (currentTimestampSec != null) {
                    if (firstTimestampSeconds == null) {
                        firstTimestampSeconds = currentTimestampSec;
                    }
                    timeOffset = Math.max(0.0, currentTimestampSec - firstTimestampSeconds);
                } else {
                    timeOffset = syntheticTimeOffset;
                    syntheticTimeOffset += 1.0;
                }

                TelemetrySample sample = new TelemetrySample();
                sample.setTimeOffsetSeconds(Math.round(timeOffset * 100.0) / 100.0);

                // Populate sensor values
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

                // A sample is considered valid if it has at least CPU or GPU load reading
                if (sample.getCpuTotalLoad() != null || sample.getGpuLoad() != null) {
                    samples.add(sample);
                    validRows++;
                } else {
                    rejectedRows++;
                }
            }
        } catch (CsvValidationException e) {
            result.getWarnings().add("CSV row formatting error encountered: " + e.getMessage());
        }

        if (validRows < 3) {
            throw new InvalidFileException("File contains insufficient valid data rows (found " +
                validRows + ", minimum 3 required for telemetry analysis).");
        }

        result.setSamples(samples);
        result.setTotalRowsParsed(totalRows);
        result.setValidRows(validRows);
        result.setRejectedRows(rejectedRows);

        double totalDuration = samples.isEmpty() ? 0.0 : samples.get(samples.size() - 1).getTimeOffsetSeconds();
        result.setTotalDurationSeconds(Math.round(totalDuration * 100.0) / 100.0);

        return result;
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
        String val = row[colIdx].trim();
        if (val.isBlank() || val.equalsIgnoreCase("n/a") || val.equalsIgnoreCase("null") || val.equals("-")) {
            return null;
        }
        try {
            // Remove any trailing unit characters or percentage signs
            String cleaned = val.replace("%", "").replace("°C", "").replace("MHz", "").replace("W", "").trim();
            double d = Double.parseDouble(cleaned);
            if (d < min || d > max) return null;
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

    private Double parseTimeValueToSeconds(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) return null;
        String trimmed = rawTime.trim();

        // Try direct numeric seconds (e.g., "12.5" or "100")
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {}

        // Try standard time formats
        List<DateTimeFormatter> formatters = List.of(
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("HH:mm:ss.SS"),
            DateTimeFormatter.ofPattern("HH:mm:ss.S"),
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("H:m:s")
        );

        for (DateTimeFormatter dtf : formatters) {
            try {
                LocalTime lt = LocalTime.parse(trimmed, dtf);
                return (double) lt.toNanoOfDay() / 1_000_000_000.0;
            } catch (DateTimeParseException ignored) {}
        }

        // If date-time string like "2026-10-26 14:30:15", take time portion
        if (trimmed.contains(" ")) {
            String[] parts = trimmed.split(" ");
            if (parts.length >= 2) {
                return parseTimeValueToSeconds(parts[1]);
            }
        }

        return null;
    }
}
