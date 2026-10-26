package com.perftrace.dto;

public class TelemetrySampleDto {

    private Long id;
    private double timeOffsetSeconds;
    private Double cpuTotalLoad;
    private Double cpuMaxThreadLoad;
    private Double cpuTempC;
    private Double cpuClockMhz;
    private Double gpuLoad;
    private Double gpuTempC;
    private Double gpuClockMhz;
    private Boolean cpuThrottleFlag;
    private Boolean gpuThrottleFlag;
    private Double ramUsagePercent;
    private Double cpuPowerW;
    private Double gpuPowerW;

    public TelemetrySampleDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public double getTimeOffsetSeconds() { return timeOffsetSeconds; }
    public void setTimeOffsetSeconds(double timeOffsetSeconds) { this.timeOffsetSeconds = timeOffsetSeconds; }

    public Double getCpuTotalLoad() { return cpuTotalLoad; }
    public void setCpuTotalLoad(Double cpuTotalLoad) { this.cpuTotalLoad = cpuTotalLoad; }

    public Double getCpuMaxThreadLoad() { return cpuMaxThreadLoad; }
    public void setCpuMaxThreadLoad(Double cpuMaxThreadLoad) { this.cpuMaxThreadLoad = cpuMaxThreadLoad; }

    public Double getCpuTempC() { return cpuTempC; }
    public void setCpuTempC(Double cpuTempC) { this.cpuTempC = cpuTempC; }

    public Double getCpuClockMhz() { return cpuClockMhz; }
    public void setCpuClockMhz(Double cpuClockMhz) { this.cpuClockMhz = cpuClockMhz; }

    public Double getGpuLoad() { return gpuLoad; }
    public void setGpuLoad(Double gpuLoad) { this.gpuLoad = gpuLoad; }

    public Double getGpuTempC() { return gpuTempC; }
    public void setGpuTempC(Double gpuTempC) { this.gpuTempC = gpuTempC; }

    public Double getGpuClockMhz() { return gpuClockMhz; }
    public void setGpuClockMhz(Double gpuClockMhz) { this.gpuClockMhz = gpuClockMhz; }

    public Boolean getCpuThrottleFlag() { return cpuThrottleFlag; }
    public void setCpuThrottleFlag(Boolean cpuThrottleFlag) { this.cpuThrottleFlag = cpuThrottleFlag; }

    public Boolean getGpuThrottleFlag() { return gpuThrottleFlag; }
    public void setGpuThrottleFlag(Boolean gpuThrottleFlag) { this.gpuThrottleFlag = gpuThrottleFlag; }

    public Double getRamUsagePercent() { return ramUsagePercent; }
    public void setRamUsagePercent(Double ramUsagePercent) { this.ramUsagePercent = ramUsagePercent; }

    public Double getCpuPowerW() { return cpuPowerW; }
    public void setCpuPowerW(Double cpuPowerW) { this.cpuPowerW = cpuPowerW; }

    public Double getGpuPowerW() { return gpuPowerW; }
    public void setGpuPowerW(Double gpuPowerW) { this.gpuPowerW = gpuPowerW; }
}
