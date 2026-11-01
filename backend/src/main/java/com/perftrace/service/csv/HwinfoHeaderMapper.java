package com.perftrace.service.csv;

import org.springframework.stereotype.Component;

import java.util.*;

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
 * GPU vendor (NVIDIA/AMD/Intel), and HWiNFO version. This mapper uses canonical prefixes
 * and aliases verified in public HWiNFO log repositories.
 */
@Component
public class HwinfoHeaderMapper {

    private final Map<NormalizedSensorField, List<String>> patterns = new EnumMap<>(NormalizedSensorField.class);

    public HwinfoHeaderMapper() {
        // Timestamp / Time columns
        patterns.put(NormalizedSensorField.TIMESTAMP, List.of(
            "time", "timestamp", "date/time", "log time"
        ));

        // CPU Total Utilization
        patterns.put(NormalizedSensorField.CPU_TOTAL_LOAD, List.of(
            "total cpu usage", "cpu total", "cpu usage", "cpu [#0]: total cpu usage", "cpu utilization"
        ));

        // Busiest Single Core / Max Thread
        patterns.put(NormalizedSensorField.CPU_MAX_THREAD_LOAD, List.of(
            "core max", "max cpu/thread usage", "cpu max thread", "cpu busiest thread", "max core usage"
        ));

        // CPU Temperature
        patterns.put(NormalizedSensorField.CPU_TEMP, List.of(
            "cpu package", "cpu core", "cpu temperature", "cpu die (average)", "cpu tctl/tdie", "cpu [#0]: cpu package"
        ));

        // CPU Clock Speed
        patterns.put(NormalizedSensorField.CPU_CLOCK, List.of(
            "core clocks (avg)", "cpu clock", "average core clock", "core clock", "cpu average clock"
        ));

        // GPU Load
        patterns.put(NormalizedSensorField.GPU_LOAD, List.of(
            "gpu core load", "gpu utilization", "gpu load", "gpu [#0]: gpu core load", "gpu d3d usage"
        ));

        // GPU Temperature
        patterns.put(NormalizedSensorField.GPU_TEMP, List.of(
            "gpu temperature", "gpu core temperature", "gpu temp", "gpu [#0]: gpu temperature"
        ));

        // GPU Clock
        patterns.put(NormalizedSensorField.GPU_CLOCK, List.of(
            "gpu clock", "gpu core clock", "gpu [#0]: gpu clock"
        ));

        // CPU Throttling Flag
        patterns.put(NormalizedSensorField.CPU_THROTTLE, List.of(
            "core thermal throttling", "cpu thermal throttling", "ia: thermal throttling",
            "cpu throttling (yes/no)", "thermal throttling"
        ));

        // GPU Throttling Flag
        patterns.put(NormalizedSensorField.GPU_THROTTLE, List.of(
            "gpu thermal throttling", "gpu thermal limit", "gpu throttling (yes/no)"
        ));

        // RAM Usage
        patterns.put(NormalizedSensorField.RAM_USAGE, List.of(
            "memory used [%]", "memory usage", "ram usage", "physical memory load", "memory used"
        ));

        // CPU Power
        patterns.put(NormalizedSensorField.CPU_POWER, List.of(
            "cpu package power", "cpu ppt", "cpu power", "package power"
        ));

        // GPU Power
        patterns.put(NormalizedSensorField.GPU_POWER, List.of(
            "gpu power", "gpu package power", "gpu total board power", "gpu tgp"
        ));
    }

    /**
     * Maps raw header strings to normalized column indices.
     *
     * @param rawHeaders array of header names from row 0 of the CSV
     * @return Map of NormalizedSensorField -> column index in the CSV
     */
    public Map<NormalizedSensorField, Integer> mapHeaders(String[] rawHeaders) {
        Map<NormalizedSensorField, Integer> columnMap = new EnumMap<>(NormalizedSensorField.class);

        for (int i = 0; i < rawHeaders.length; i++) {
            String raw = cleanHeader(rawHeaders[i]);
            if (raw.isBlank()) continue;

            String normalized = normalizeHeader(raw);

            // Find the best matching field by longest alias (most specific) first.
            NormalizedSensorField bestField = null;
            int bestAliasLen = -1;
            for (Map.Entry<NormalizedSensorField, List<String>> entry : patterns.entrySet()) {
                NormalizedSensorField field = entry.getKey();
                if (columnMap.containsKey(field)) continue;

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
