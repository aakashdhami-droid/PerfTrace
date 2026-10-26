export type Classification = 'CPU_BOUND' | 'GPU_BOUND' | 'BALANCED' | 'IDLE' | 'UNKNOWN';

export type ThrottleEvidence = 'NONE' | 'HARDWARE_FLAG' | 'HEURISTIC_DETECTED' | 'INSUFFICIENT_DATA';

export type RunStatus = 'UPLOADING' | 'ANALYZING' | 'COMPLETED' | 'FAILED';

export interface User {
  id: number;
  username: string;
  email: string;
  createdAt: string;
}

export interface AuthResponse {
  token: string;
  type: string;
  id: number;
  username: string;
  email: string;
}

export interface AnalysisRun {
  id: number;
  fileName: string;
  description?: string;
  status: RunStatus;
  errorMessage?: string;
  totalDurationSeconds?: number;
  validDataDurationSeconds?: number;

  cpuBoundSeconds?: number;
  gpuBoundSeconds?: number;
  balancedSeconds?: number;
  idleSeconds?: number;
  unknownSeconds?: number;

  cpuBoundActivePercent?: number;
  gpuBoundActivePercent?: number;
  balancedActivePercent?: number;
  idleTotalPercent?: number;

  throttleDurationSeconds?: number;
  hardwareFlagThrottleSeconds?: number;
  heuristicThrottleSeconds?: number;
  throttlePercent?: number;

  peakCpuTempC?: number;
  peakGpuTempC?: number;
  avgCpuClockMhz?: number;
  avgGpuClockMhz?: number;

  totalRows?: number;
  validRows?: number;
  rejectedRows?: number;
  recognizedSensors?: string;
  missingSensors?: string;
  dataQualityWarnings?: string;

  createdAt: string;
  completedAt?: string;
}

export interface DetectedSegment {
  id: number;
  startOffsetSeconds: number;
  endOffsetSeconds: number;
  durationSeconds: number;
  classification: Classification;
  throttleEvidence: ThrottleEvidence;
  evidenceExplanation?: string;

  avgCpuLoad?: number;
  avgGpuLoad?: number;
  avgCpuMaxThread?: number;
  avgCpuTempC?: number;
  avgGpuTempC?: number;
  avgCpuClockMhz?: number;
  avgGpuClockMhz?: number;
  peakCpuTempC?: number;
  peakGpuTempC?: number;
  sampleCount?: number;
}

export interface TelemetrySample {
  id: number;
  timeOffsetSeconds: number;
  cpuTotalLoad?: number;
  cpuMaxThreadLoad?: number;
  cpuTempC?: number;
  cpuClockMhz?: number;
  gpuLoad?: number;
  gpuTempC?: number;
  gpuClockMhz?: number;
  cpuThrottleFlag?: boolean;
  gpuThrottleFlag?: boolean;
  ramUsagePercent?: number;
  cpuPowerW?: number;
  gpuPowerW?: number;
}

export interface MetricComparison {
  name: string;
  category: string;
  unit: string;
  valueA?: number;
  valueB?: number;
  difference?: number;
  percentChange?: number;
  interpretation?: string;
}

export interface RunComparison {
  runA: AnalysisRun;
  runB: AnalysisRun;
  metrics: MetricComparison[];
  sensorCoverageDiffs: string[];
  comparabilityWarnings: string[];
  conclusionNote: string;
}

export interface UploadResponse {
  runId: number;
  fileName: string;
  status: RunStatus;
  message: string;
  totalRowsParsed?: number;
  validRows?: number;
  rejectedRows?: number;
  totalDurationSeconds?: number;
}
