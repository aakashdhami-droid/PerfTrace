import React, { useState, useEffect } from 'react';
import { AnalysisRun, DetectedSegment, TelemetrySample } from '../types';
import { api } from '../services/api';
import { MetricCard } from '../components/MetricCard';
import { SegmentTimeline } from '../components/SegmentTimeline';
import { TelemetryChart } from '../components/TelemetryChart';
import { DiagnosticDisclaimer } from '../components/DiagnosticDisclaimer';
import {
  ArrowLeft,
  Clock,
  Cpu,
  Zap,
  Scale,
  Moon,
  Flame,
  Thermometer,
  Gauge,
  AlertTriangle,
  CheckCircle2,
  GitCompare,
} from 'lucide-react';

interface Props {
  runId: number;
  onBack: () => void;
  onCompare: (runId: number) => void;
}

export const RunDetailPage: React.FC<Props> = ({ runId, onBack, onCompare }) => {
  const [run, setRun] = useState<AnalysisRun | null>(null);
  const [segments, setSegments] = useState<DetectedSegment[]>([]);
  const [samples, setSamples] = useState<TelemetrySample[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchData = async () => {
      setLoading(true);
      setError(null);
      try {
        const [runData, segmentData, sampleData] = await Promise.all([
          api.getRun(runId),
          api.getSegments(runId),
          api.getSamples(runId, 500),
        ]);
        setRun(runData);
        setSegments(segmentData);
        setSamples(sampleData);
      } catch (err: any) {
        setError(err.message || 'Failed to load run details.');
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [runId]);

  if (loading) {
    return (
      <div style={{ maxWidth: '1380px', margin: '40px auto', padding: '0 24px', textAlign: 'center', color: 'var(--text-muted)' }}>
        Loading telemetry analysis for run #{runId}...
      </div>
    );
  }

  if (error || !run) {
    return (
      <div style={{ maxWidth: '1380px', margin: '40px auto', padding: '0 24px' }}>
        <button onClick={onBack} className="btn btn-secondary" style={{ marginBottom: '20px' }}>
          <ArrowLeft size={16} /> Back to Dashboard
        </button>
        <div className="glass-panel" style={{ padding: '30px', textAlign: 'center', color: 'var(--accent-rose)' }}>
          {error || 'Run not found.'}
        </div>
      </div>
    );
  }

  const formatSeconds = (sec?: number) => {
    if (sec == null || !Number.isFinite(sec)) return 'N/A';
    const total = Math.max(0, Math.round(sec));
    const m = Math.floor(total / 60);
    const s = total % 60;
    if (m === 0) return `${s}s`;
    return `${m}m ${s}s`;
  };

  // Use the observed-data duration so sampling gaps are not mislabelled as active time.
  const observedDuration = run.validDataDurationSeconds ?? run.totalDurationSeconds ?? 0;
  const activeDuration = Math.max(0, observedDuration - (run.idleSeconds ?? 0));

  return (
    <div style={{ maxWidth: '1380px', margin: '0 auto', padding: '32px 24px', display: 'flex', flexDirection: 'column', gap: '24px' }}>
      {/* Header with Back and Action Buttons */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '14px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <button onClick={onBack} className="btn btn-secondary" style={{ padding: '7px 12px' }}>
            <ArrowLeft size={16} /> Back
          </button>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <h1 style={{ fontSize: '1.375rem', fontWeight: 700, color: '#ffffff' }}>
                {run.fileName}
              </h1>
              <span className="badge badge-cpu">Run #{run.id}</span>
            </div>
            {run.description && (
              <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginTop: '2px' }}>
                {run.description}
              </p>
            )}
          </div>
        </div>

        <div style={{ display: 'flex', gap: '10px' }}>
          <button onClick={() => onCompare(run.id)} className="btn btn-secondary">
            <GitCompare size={15} />
            Compare This Run
          </button>
        </div>
      </div>

      {/* Sensor Coverage and Warnings Banner */}
      <div className="glass-panel" style={{ padding: '16px 20px', fontSize: '0.8125rem' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
          <CheckCircle2 size={16} style={{ color: 'var(--accent-emerald)' }} />
          <strong style={{ color: 'var(--text-primary)' }}>Recognized Sensors:</strong>
          <span style={{ color: 'var(--text-secondary)' }}>
            {run.recognizedSensors || 'None detected'}
          </span>
        </div>

        {run.missingSensors && (
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-muted)', marginTop: '4px' }}>
            <strong style={{ color: 'var(--text-secondary)' }}>Missing Optional Sensors:</strong>
            <span>{run.missingSensors}</span>
          </div>
        )}

        {run.dataQualityWarnings && (
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--accent-amber)', marginTop: '8px' }}>
            <AlertTriangle size={15} style={{ flexShrink: 0 }} />
            <span>Data Quality Notice: {run.dataQualityWarnings}</span>
          </div>
        )}
      </div>

      {/* Summary Stat Cards Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '14px' }}>
        <MetricCard
          title="Total Duration"
          value={formatSeconds(run.totalDurationSeconds)}
          subtitle={`Active workload: ${formatSeconds(activeDuration)}`}
          icon={<Clock size={18} />}
          highlight="blue"
        />

        <MetricCard
          title="CPU-Limited Active Time"
          value={run.cpuBoundActivePercent != null ? `${run.cpuBoundActivePercent}%` : 'N/A'}
          subtitle={`${formatSeconds(run.cpuBoundSeconds)} of active workload`}
          icon={<Cpu size={18} />}
          highlight="blue"
        />

        <MetricCard
          title="GPU-Limited Active Time"
          value={run.gpuBoundActivePercent != null ? `${run.gpuBoundActivePercent}%` : 'N/A'}
          subtitle={`${formatSeconds(run.gpuBoundSeconds)} of active workload`}
          icon={<Zap size={18} />}
          highlight="emerald"
        />

        <MetricCard
          title="Balanced / Other"
          value={run.balancedActivePercent != null ? `${run.balancedActivePercent}%` : 'N/A'}
          subtitle={`${formatSeconds(run.balancedSeconds)} active time`}
          icon={<Scale size={18} />}
          highlight="neutral"
        />

        <MetricCard
          title="Idle Time (% of observed)"
          value={run.idleTotalPercent != null ? `${run.idleTotalPercent}%` : 'N/A'}
          subtitle={`${formatSeconds(run.idleSeconds)} below 15% CPU/GPU load`}
          icon={<Moon size={18} />}
          highlight="neutral"
        />

        <MetricCard
          title="Thermal Throttling"
          value={(run.throttleDurationSeconds || 0) > 0 ? `${formatSeconds(run.throttleDurationSeconds)}` : 'None'}
          subtitle={(run.hardwareFlagThrottleSeconds || 0) > 0 ? 'Hardware register flagged' : ((run.heuristicThrottleSeconds || 0) > 0 ? 'Heuristic clock drop' : 'No throttling observed')}
          icon={<Flame size={18} />}
          highlight={(run.throttleDurationSeconds || 0) > 0 ? 'rose' : 'emerald'}
        />

        <MetricCard
          title="Peak Temperatures"
          value={run.peakCpuTempC != null ? `${run.peakCpuTempC}°C` : 'N/A'}
          unit="CPU"
          subtitle={`GPU Peak: ${run.peakGpuTempC != null ? `${run.peakGpuTempC}°C` : 'N/A'}`}
          icon={<Thermometer size={18} />}
          highlight={run.peakCpuTempC != null && run.peakCpuTempC >= 90 ? 'rose' : 'neutral'}
        />

        <MetricCard
          title="Avg Sustained Clock"
          value={run.avgCpuClockMhz != null ? `${Math.round(run.avgCpuClockMhz)}` : 'N/A'}
          unit="MHz CPU"
          subtitle={`GPU Sustained: ${run.avgGpuClockMhz != null ? `${Math.round(run.avgGpuClockMhz)} MHz` : 'N/A'}`}
          icon={<Gauge size={18} />}
          highlight="blue"
        />
      </div>

      {/* Segment Timeline */}
      <SegmentTimeline segments={segments} totalDurationSeconds={run.totalDurationSeconds} />

      {/* Downsampled Time-Series Charts */}
      <TelemetryChart samples={samples} />

      {/* Methodological Disclaimer */}
      <DiagnosticDisclaimer />
    </div>
  );
};
