package com.perftrace.service.csv;

import com.perftrace.exception.InvalidFileException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvIngestionServiceTest {

    private CsvIngestionService csvIngestionService;

    @BeforeEach
    void setUp() {
        HwinfoHeaderMapper mapper = new HwinfoHeaderMapper();
        csvIngestionService = new CsvIngestionService(mapper, 500000);
    }

    @Test
    @DisplayName("Should successfully parse valid HWiNFO64 CSV with timestamps and headers")
    void testParseValidHwinfoCsv() throws IOException {
        String csv = """
            Date,Time,"Total CPU Usage [%]","Core Max [%]","CPU Package [°C]","Core Clocks (avg) [MHz]","GPU Core Load [%]","GPU Temperature [°C]","GPU Clock [MHz]","Core Thermal Throttling [Yes/No]"
            26.10.2026,10:00:00,45.2,60.5,65.0,4200.0,98.2,71.0,1950.0,No
            26.10.2026,10:00:02,46.0,62.1,65.5,4200.0,99.0,71.5,1950.0,No
            26.10.2026,10:00:04,44.8,59.8,66.0,4195.0,98.5,72.0,1945.0,No
            26.10.2026,10:00:06,47.1,63.4,66.2,4200.0,97.9,72.2,1950.0,No
            """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        CsvParseResult result = csvIngestionService.parse(is);

        assertThat(result.getValidRows()).isEqualTo(4);
        assertThat(result.getTotalRowsParsed()).isEqualTo(4);
        assertThat(result.getSamples()).hasSize(4);
        assertThat(result.getTotalDurationSeconds()).isEqualTo(6.0); // 0s, 2s, 4s, 6s

        // Verify first sample normalized fields
        var s0 = result.getSamples().get(0);
        assertThat(s0.getTimeOffsetSeconds()).isEqualTo(0.0);
        assertThat(s0.getCpuTotalLoad()).isEqualTo(45.2);
        assertThat(s0.getCpuMaxThreadLoad()).isEqualTo(60.5);
        assertThat(s0.getCpuTempC()).isEqualTo(65.0);
        assertThat(s0.getCpuClockMhz()).isEqualTo(4200.0);
        assertThat(s0.getGpuLoad()).isEqualTo(98.2);
        assertThat(s0.getGpuTempC()).isEqualTo(71.0);
        assertThat(s0.getGpuClockMhz()).isEqualTo(1950.0);
        assertThat(s0.getCpuThrottleFlag()).isFalse();
    }

    @Test
    @DisplayName("Should parse UTF-8 with BOM correctly without corrupting first header")
    void testParseUtf8WithBom() throws IOException {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        String csv = """
            Time,"Total CPU Usage [%]","GPU Core Load [%]"
            0.0,50.0,80.0
            1.0,52.0,82.0
            2.0,51.0,81.0
            """;
        byte[] content = csv.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, combined, 0, bom.length);
        System.arraycopy(content, 0, combined, bom.length, content.length);

        InputStream is = new ByteArrayInputStream(combined);
        CsvParseResult result = csvIngestionService.parse(is);

        assertThat(result.getValidRows()).isEqualTo(3);
        assertThat(result.getSamples().get(0).getCpuTotalLoad()).isEqualTo(50.0);
        assertThat(result.getSamples().get(0).getGpuLoad()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("Should skip secondary HWiNFO64 units header row if present")
    void testSkipSecondaryUnitsHeaderRow() throws IOException {
        String csv = """
            Time,"Total CPU Usage [%]","GPU Core Load [%]","CPU Package [°C]"
            (sec),[%],[%],[°C]
            0.0,30.0,50.0,55.0
            1.0,32.0,52.0,56.0
            2.0,31.0,51.0,55.5
            """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        CsvParseResult result = csvIngestionService.parse(is);

        assertThat(result.getValidRows()).isEqualTo(3);
        assertThat(result.getSamples().get(0).getCpuTotalLoad()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("Should reject CSV if essential CPU and GPU sensors are missing")
    void testRejectMissingEssentialSensors() {
        String csv = """
            Time,"Disk Read Rate [MB/s]","Network Utilization [%]"
            0.0,10.5,5.0
            1.0,12.0,6.0
            2.0,11.0,5.5
            """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> csvIngestionService.parse(is))
            .isInstanceOf(InvalidFileException.class)
            .hasMessageContaining("does not contain required telemetry sensors");
    }

    @Test
    @DisplayName("Should reject empty CSV file")
    void testRejectEmptyFile() {
        InputStream is = new ByteArrayInputStream(new byte[0]);
        assertThatThrownBy(() -> csvIngestionService.parse(is))
            .isInstanceOf(InvalidFileException.class)
            .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("Should reject file with fewer than 3 valid data rows")
    void testRejectInsufficientRows() {
        String csv = """
            Time,"Total CPU Usage [%]","GPU Core Load [%]"
            0.0,50.0,80.0
            1.0,52.0,82.0
            """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> csvIngestionService.parse(is))
            .isInstanceOf(InvalidFileException.class)
            .hasMessageContaining("insufficient valid data rows");
    }

    @Test
    @DisplayName("Should handle missing values (N/A, blank, dash) by preserving null (not converting to zero)")
    void testHandleMissingValuesAsNull() throws IOException {
        String csv = """
            Time,"Total CPU Usage [%]","GPU Core Load [%]","CPU Package [°C]","Core Clocks (avg) [MHz]"
            0.0,60.0,90.0,N/A,4200.0
            1.0,62.0,91.0,,null
            2.0,61.0,89.0,-,4210.0
            """;

        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        CsvParseResult result = csvIngestionService.parse(is);

        assertThat(result.getValidRows()).isEqualTo(3);
        assertThat(result.getSamples().get(0).getCpuTempC()).isNull();
        assertThat(result.getSamples().get(1).getCpuTempC()).isNull();
        assertThat(result.getSamples().get(1).getCpuClockMhz()).isNull();
        assertThat(result.getSamples().get(2).getCpuTempC()).isNull();
    }

    @Test
    @DisplayName("An invalid first timestamp must not shift later offsets by an epoch-sized amount")
    void testInvalidFirstTimestampDoesNotCorruptOffsets() throws IOException {
        String csv = """
            Date,Time,"Total CPU Usage [%]","GPU Core Load [%]"
            N/A,N/A,50.0,60.0
            26.10.2026,10:00:02,51.0,61.0
            26.10.2026,10:00:04,52.0,62.0
            26.10.2026,10:00:06,53.0,63.0
            """;

        CsvParseResult result = csvIngestionService.parse(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.getSamples()).hasSize(4);
        assertThat(result.getSamples().get(0).getTimeOffsetSeconds()).isEqualTo(0.0);
        assertThat(result.getSamples().get(1).getTimeOffsetSeconds()).isEqualTo(2.0);
        assertThat(result.getSamples().get(2).getTimeOffsetSeconds()).isEqualTo(4.0);
        assertThat(result.getSamples().get(3).getTimeOffsetSeconds()).isEqualTo(6.0);
        assertThat(result.getTotalDurationSeconds()).isEqualTo(6.0);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("unparseable timestamps"));
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("first valid timestamp"));
    }

    @Test
    @DisplayName("A malformed timestamp mid-file must continue from the last known time")
    void testMalformedMidFileTimestampKeepsTimelineMonotonic() throws IOException {
        String csv = """
            Date,Time,"Total CPU Usage [%]","GPU Core Load [%]"
            26.10.2026,10:00:00,50.0,60.0
            26.10.2026,10:00:02,51.0,61.0
            N/A,N/A,52.0,62.0
            26.10.2026,10:00:06,53.0,63.0
            """;

        CsvParseResult result = csvIngestionService.parse(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.getValidRows()).isEqualTo(4);
        assertThat(result.getSamples()).extracting(s -> s.getTimeOffsetSeconds())
            .containsExactly(0.0, 2.0, 4.0, 6.0);
        assertThat(result.getTotalDurationSeconds()).isEqualTo(6.0);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("unparseable timestamps"));
    }

    @Test
    @DisplayName("Changing calendar date at midnight must not reset or duplicate offsets")
    void testDateRolloverAcrossMidnight() throws IOException {
        String csv = """
            Date,Time,"Total CPU Usage [%]","GPU Core Load [%]"
            26.10.2026,23:59:58,50.0,60.0
            26.10.2026,23:59:59,51.0,61.0
            27.10.2026,00:00:00,52.0,62.0
            27.10.2026,00:00:02,53.0,63.0
            """;

        CsvParseResult result = csvIngestionService.parse(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.getSamples()).extracting(s -> s.getTimeOffsetSeconds())
            .containsExactly(0.0, 1.0, 2.0, 4.0);
        assertThat(result.getTotalDurationSeconds()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("Logs without timestamp columns should use a documented 1s synthetic interval")
    void testNoTimestampColumnUsesSyntheticOffsets() throws IOException {
        String csv = """
            "Total CPU Usage [%]","GPU Core Load [%]"
            50.0,60.0
            51.0,61.0
            52.0,62.0
            """;

        CsvParseResult result = csvIngestionService.parse(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.getSamples()).extracting(s -> s.getTimeOffsetSeconds())
            .containsExactly(0.0, 1.0, 2.0);
        assertThat(result.getTotalDurationSeconds()).isEqualTo(2.0);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("No timestamp column detected"));
    }

    @Test
    @DisplayName("Should handle midnight rollover for time-only logs")
    void testMidnightRollover() throws IOException {
        String csv = """
            Time,"Total CPU Usage [%]","GPU Core Load [%]"
            23:59:58,50.0,60.0
            23:59:59,51.0,61.0
            00:00:00,52.0,62.0
            00:00:02,53.0,63.0
            """;

        CsvParseResult result = csvIngestionService.parse(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.getSamples()).hasSize(4);
        assertThat(result.getSamples().get(0).getTimeOffsetSeconds()).isEqualTo(0.0);
        assertThat(result.getSamples().get(1).getTimeOffsetSeconds()).isEqualTo(1.0);
        assertThat(result.getSamples().get(2).getTimeOffsetSeconds()).isEqualTo(2.0);
        assertThat(result.getSamples().get(3).getTimeOffsetSeconds()).isEqualTo(4.0);
        assertThat(result.getTotalDurationSeconds()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("Should strip units and normalize GHz, decimal commas, and thousands separators")
    void testNumericUnitNormalization() throws IOException {
        String csv = """
            Time,"Total CPU Usage [%]","GPU Core Load [%]","Core Clocks (avg) [MHz]","CPU Package [°C]"
            0,72 %,88 %,4.2 GHz,"65,5 °C"
            1,73 %,89 %,"4,250 MHz","66,0 °C"
            2,74 %,90 %,5.0 GHz,"67,5 °C"
            """;

        CsvParseResult result = csvIngestionService.parse(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.getSamples()).hasSize(3);
        assertThat(result.getSamples().get(0).getCpuTotalLoad()).isEqualTo(72.0);
        assertThat(result.getSamples().get(0).getGpuLoad()).isEqualTo(88.0);
        assertThat(result.getSamples().get(0).getCpuClockMhz()).isEqualTo(4200.0);
        assertThat(result.getSamples().get(0).getCpuTempC()).isEqualTo(65.5);
        assertThat(result.getSamples().get(1).getCpuClockMhz()).isEqualTo(4250.0);
        assertThat(result.getSamples().get(2).getCpuClockMhz()).isEqualTo(5000.0);
    }

    @Test
    @DisplayName("Should preserve monotonic offsets and warn when timestamps are out of order")
    void testOutOfOrderTimestamps() throws IOException {
        String csv = """
            Time,"Total CPU Usage [%]","GPU Core Load [%]"
            00:00:00,50.0,60.0
            00:00:04,51.0,61.0
            00:00:02,52.0,62.0
            00:00:06,53.0,63.0
            """;

        CsvParseResult result = csvIngestionService.parse(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.getSamples()).extracting(s -> s.getTimeOffsetSeconds())
            .containsExactly(0.0, 4.0, 4.0, 6.0);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("out-of-order"));
    }
}
