package com.perftrace.service.csv;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps HWiNFO64 CSV column headers to normalized internal sensor fields.
 *
 * Real HWiNFO64 CSV exports use headers such as:
 * - "Date", "Time"
 * - "Total CPU Usage [%]" or "CPU Total [%]"
 * - "Core Max [%]" or "Max CPU/Thread Usage [%]"
 * - "CPU Package [°C]" or "CPU Core [°C]"
 * - "Core Clocks (avg) [MHz]" or "CPU Clock [MHz]"
 * - "GPU Core Load [%]" or "GPU Utilization [%]"
 * - "GPU Temperature [°C]"
 * - "GPU Clock [MHz]"
 * - "Core Thermal Throttling [Yes/No]"
 * - "GPU Thermal Throttling [Yes/No]"
 * - "Memory Used [%]"
 * - "CPU Package Power [W]"
 * - "GPU Power [W]"
 *
 * NOTE: Real HWiNFO64 exports vary depending on motherboard, CPU vendor (Intel/AMD),
 * GPU vendor (NVIDIA/AMD/Intel), and HWiNFO version. This mapper uses canonical prefixes,
 * specificity-ranked aliases, and unit-aware matching to avoid prefix collisions such as
 * "CPU Core Clocks [MHz]" being mistaken for a CPU temperature column.
 */
@Component
public class HwinfoHeaderMapper {

    private static final Pattern UNIT_PATTERN = Pattern.compile("\\[([^\\]]+)]");

    private enum UnitFamily {
        PERCENT, TEMPERATURE, FREQUENCY, POWER, BOOLEAN
    }

    private final Map<NormalizedSensorField, List<String>> patterns = new EnumMap<>(NormalizedSensorField.class);
    private final Map<NormalizedSensorField, Set<UnitFamily>> expectedUnits = new EnumMap<>(NormalizedSensorField.class);

    public HwinfoHeaderMapper() {
        // Timestamp / Time columns
        patterns.put(NormalizedSensorField.TIMESTAMP, List.of(
            "time", "timestamp", "date/time", "log time", "time [h:m:s]"
        ));
        patterns.put(NormalizedSensorField.DATE, List.of(
            "date", "log date", "recording date"
        ));

        // CPU Total Utilization
        patterns.put(NormalizedSensorField.CPU_TOTAL_LOAD, List.of(
            "total cpu usage", "cpu total usage", "cpu total", "cpu usage", "cpu utilization",
            "cpu [#0]: total cpu usage"
        ));
        expectedUnits.put(NormalizedSensorField.CPU_TOTAL_LOAD, EnumSet.of(UnitFamily.PERCENT));

        // Busiest Single Core / Max Thread
        patterns.put(NormalizedSensorField.CPU_MAX_THREAD_LOAD, List.of(
            "core max", "max cpu/thread usage", "max thread usage", "cpu max thread",
            "cpu busiest thread", "max core usage"
        ));
        expectedUnits.put(NormalizedSensorField.CPU_MAX_THREAD_LOAD, EnumSet.of(UnitFamily.PERCENT));

        // CPU Temperature
        patterns.put(NormalizedSensorField.CPU_TEMP, List.of(
            "cpu package temperature", "cpu package", "cpu core temperature", "cpu core",
            "cpu temperature", "cpu die (average)", "cpu tctl/tdie", "cpu (tctl/tdie)",
            "cpu [#0]: cpu package"
        ));
        expectedUnits.put(NormalizedSensorField.CPU_TEMP, EnumSet.of(UnitFamily.TEMPERATURE));

        // CPU Clock Speed
        patterns.put(NormalizedSensorField.CPU_CLOCK, List.of(
            "core clocks (avg)", "average core clocks", "core clocks", "core clock",
            "cpu core clocks", "cpu core clock", "cpu average clock", "cpu clock",
            "cpu [#0]: core clocks"
        ));
        expectedUnits.put(NormalizedSensorField.CPU_CLOCK, EnumSet.of(UnitFamily.FREQUENCY));

        // GPU Load
        patterns.put(NormalizedSensorField.GPU_LOAD, List.of(
            "gpu core load", "gpu utilization", "gpu load", "gpu d3d usage",
            "gpu [#0]: gpu core load"
        ));
        expectedUnits.put(NormalizedSensorField.GPU_LOAD, EnumSet.of(UnitFamily.PERCENT));

        // GPU Temperature
        patterns.put(NormalizedSensorField.GPU_TEMP, List.of(
            "gpu core temperature", "gpu temperature", "gpu temp", "gpu [#0]: gpu temperature"
        ));
        expectedUnits.put(NormalizedSensorField.GPU_TEMP, EnumSet.of(UnitFamily.TEMPERATURE));

        // GPU Clock
        patterns.put(NormalizedSensorField.GPU_CLOCK, List.of(
            "gpu core clock", "gpu clock", "gpu [#0]: gpu clock"
        ));
        expectedUnits.put(NormalizedSensorField.GPU_CLOCK, EnumSet.of(UnitFamily.FREQUENCY));

        // CPU Throttling Flag
        patterns.put(NormalizedSensorField.CPU_THROTTLE, List.of(
            "core thermal throttling", "cpu thermal throttling", "ia: thermal throttling",
            "cpu throttling (yes/no)", "thermal throttling", "prochot"
        ));
        expectedUnits.put(NormalizedSensorField.CPU_THROTTLE, EnumSet.of(UnitFamily.BOOLEAN));

        // GPU Throttling Flag
        patterns.put(NormalizedSensorField.GPU_THROTTLE, List.of(
            "gpu thermal throttling", "gpu thermal limit", "gpu throttling (yes/no)",
            "gpu performance limit - thermal"
        ));
        expectedUnits.put(NormalizedSensorField.GPU_THROTTLE, EnumSet.of(UnitFamily.BOOLEAN));

        // RAM Usage
        patterns.put(NormalizedSensorField.RAM_USAGE, List.of(
            "memory used", "memory usage", "ram usage", "physical memory load"
        ));
        expectedUnits.put(NormalizedSensorField.RAM_USAGE, EnumSet.of(UnitFamily.PERCENT));

        // CPU Power
        patterns.put(NormalizedSensorField.CPU_POWER, List.of(
            "cpu package power", "cpu ppt", "package power", "cpu power"
        ));
        expectedUnits.put(NormalizedSensorField.CPU_POWER, EnumSet.of(UnitFamily.POWER));

        // GPU Power
        patterns.put(NormalizedSensorField.GPU_POWER, List.of(
            "gpu total board power", "gpu package power", "gpu tgp", "gpu power"
        ));
        expectedUnits.put(NormalizedSensorField.GPU_POWER, EnumSet.of(UnitFamily.POWER));
    }

    /**
     * Maps raw header strings to normalized column indices.
     *
     * @param rawHeaders array of header names from row 0 of the CSV
     * @return Map of NormalizedSensorField -> column index in the CSV
     */
    public Map<NormalizedSensorField, Integer> mapHeaders(String[] rawHeaders) {
        Map<NormalizedSensorField, Integer> columnMap = new EnumMap<>(NormalizedSensorField.class);
        if (rawHeaders == null) return columnMap;

        for (int i = 0; i < rawHeaders.length; i++) {
            String raw = cleanHeader(rawHeaders[i]);
            if (raw.isBlank()) continue;

            String normalized = normalizeHeader(raw);
            UnitFamily unit = detectUnit(raw);

            // Find the best matching field by longest alias (most specific) first.
            NormalizedSensorField bestField = null;
            int bestAliasLen = -1;
            for (Map.Entry<NormalizedSensorField, List<String>> entry : patterns.entrySet()) {
                NormalizedSensorField field = entry.getKey();
                if (columnMap.containsKey(field)) continue;

                if (unit != null) {
                    Set<UnitFamily> expected = expectedUnits.get(field);
                    if (expected != null && !expected.contains(unit)) continue;
                }

                for (String alias : entry.getValue()) {
                    String target = alias.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
                    if (aliasMatches(normalized, target) && target.length() > bestAliasLen) {
                        bestField = field;
                        bestAliasLen = target.length();
                    }
                }
            }

            if (bestField != null) {
                columnMap.put(bestField, i);
            }
        }

        return columnMap;
    }

    private String normalizeHeader(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        String withoutUnits = lower.replaceAll("\\[.*?\\]", " ").trim();
        return withoutUnits.replaceAll("\\s+", " ");
    }

    private boolean aliasMatches(String normalized, String target) {
        if (normalized.equals(target)) return true;
        return normalized.startsWith(target + " ");
    }

    private UnitFamily detectUnit(String rawHeader) {
        Matcher matcher = UNIT_PATTERN.matcher(rawHeader);
        while (matcher.find()) {
            String unit = matcher.group(1)
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("\u00A0", "");
            if (unit.contains("%")) return UnitFamily.PERCENT;
            if (unit.contains("°c") || unit.contains("ºc") || unit.equals("c") || unit.contains("celsius")) {
                return UnitFamily.TEMPERATURE;
            }
            if (unit.contains("mhz") || unit.contains("ghz") || unit.equals("hz")) return UnitFamily.FREQUENCY;
            if (unit.equals("w") || unit.equals("mw") || unit.contains("watt")) return UnitFamily.POWER;
            if (unit.contains("yes/no") || unit.contains("true/false") || unit.contains("yes")) {
                return UnitFamily.BOOLEAN;
            }
        }
        return null;
    }

    public static String cleanHeader(String header) {
        if (header == null) return "";
        // Strip BOM (\uFEFF) if present at start
        String cleaned = header.replace("\uFEFF", "").trim();
        // Strip outer quotes if any
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    public List<String> getMissingCriticalSensors(Map<NormalizedSensorField, Integer> columnMap) {
        List<String> missing = new ArrayList<>();
        if (!columnMap.containsKey(NormalizedSensorField.CPU_TOTAL_LOAD)) {
            missing.add("CPU Total Load");
        }
        if (!columnMap.containsKey(NormalizedSensorField.GPU_LOAD)) {
            missing.add("GPU Load");
        }
        return missing;
    }

    public List<String> getRecognizedSensorNames(Map<NormalizedSensorField, Integer> columnMap) {
        List<String> names = new ArrayList<>();
        for (NormalizedSensorField field : columnMap.keySet()) {
            names.add(field.getDisplayName());
        }
        return names;
    }

    public List<String> getMissingSensorNames(Map<NormalizedSensorField, Integer> columnMap) {
        List<String> missing = new ArrayList<>();
        for (NormalizedSensorField field : NormalizedSensorField.values()) {
            if (!columnMap.containsKey(field)) {
                missing.add(field.getDisplayName());
            }
        }
        return missing;
    }
}
