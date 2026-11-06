package com.perftrace.service.csv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HwinfoHeaderMapperTest {

    private HwinfoHeaderMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new HwinfoHeaderMapper();
    }

    @Test
    @DisplayName("Unit-aware matching must not confuse CPU core clocks (MHz) with CPU temperature (°C)")
    void testFrequencyHeaderDoesNotBecomeTemperature() {
        String[] headers = {
            "Date", "Time", "CPU Core Clocks [MHz]", "CPU Package [°C]",
            "Total CPU Usage [%]", "GPU Core Load [%]"
        };

        Map<NormalizedSensorField, Integer> map = mapper.mapHeaders(headers);

        assertThat(map.get(NormalizedSensorField.CPU_CLOCK)).isEqualTo(2);
        assertThat(map.get(NormalizedSensorField.CPU_TEMP)).isEqualTo(3);
        assertThat(map.get(NormalizedSensorField.CPU_TOTAL_LOAD)).isEqualTo(4);
        assertThat(map.get(NormalizedSensorField.GPU_LOAD)).isEqualTo(5);
    }

    @Test
    @DisplayName("Should map common HWiNFO64 aliases and throttle flags")
    void testCommonAliases() {
        String[] headers = {
            "Time", "Max CPU/Thread Usage [%]", "CPU (Tctl/Tdie) [°C]",
            "Average Core Clocks [MHz]", "GPU Utilization [%]", "GPU Temperature [°C]",
            "GPU Clock [MHz]", "Core Thermal Throttling [Yes/No]",
            "GPU Performance Limit - Thermal [Yes/No]", "Memory Used [%]",
            "CPU Package Power [W]", "GPU Power [W]"
        };

        Map<NormalizedSensorField, Integer> map = mapper.mapHeaders(headers);

        assertThat(map.get(NormalizedSensorField.CPU_MAX_THREAD_LOAD)).isEqualTo(1);
        assertThat(map.get(NormalizedSensorField.CPU_TEMP)).isEqualTo(2);
        assertThat(map.get(NormalizedSensorField.CPU_CLOCK)).isEqualTo(3);
        assertThat(map.get(NormalizedSensorField.GPU_LOAD)).isEqualTo(4);
        assertThat(map.get(NormalizedSensorField.GPU_TEMP)).isEqualTo(5);
        assertThat(map.get(NormalizedSensorField.GPU_CLOCK)).isEqualTo(6);
        assertThat(map.get(NormalizedSensorField.CPU_THROTTLE)).isEqualTo(7);
        assertThat(map.get(NormalizedSensorField.GPU_THROTTLE)).isEqualTo(8);
        assertThat(map.get(NormalizedSensorField.RAM_USAGE)).isEqualTo(9);
        assertThat(map.get(NormalizedSensorField.CPU_POWER)).isEqualTo(10);
        assertThat(map.get(NormalizedSensorField.GPU_POWER)).isEqualTo(11);
    }

    @Test
    @DisplayName("Should report CPU total load and GPU load as the only critical sensors")
    void testMissingCriticalSensors() {
        Map<NormalizedSensorField, Integer> map = mapper.mapHeaders(
            new String[]{"Time", "Total CPU Usage [%]", "GPU Core Load [%]"});

        assertThat(mapper.getMissingCriticalSensors(map)).isEmpty();

        Map<NormalizedSensorField, Integer> onlyCpu = mapper.mapHeaders(
            new String[]{"Time", "Total CPU Usage [%]"});

        assertThat(mapper.getMissingCriticalSensors(onlyCpu)).containsExactly("GPU Load");
    }
}
