import React, { useState } from 'react';
import { DetectedSegment, Classification } from '../types';
import { ClassificationBadge } from './ClassificationBadge';
import { ThrottleBadge } from './ThrottleBadge';
import { Info, Clock } from 'lucide-react';

interface Props {
  segments: DetectedSegment[];
  totalDurationSeconds?: number;
}

export const SegmentTimeline: React.FC<Props> = ({ segments, totalDurationSeconds }) => {
  const [selectedSegment, setSelectedSegment] = useState<DetectedSegment | null>(
    segments.length > 0 ? segments[0] : null
  );

  const effectiveTotal = totalDurationSeconds && totalDurationSeconds > 0
    ? totalDurationSeconds
    : (segments.length > 0 ? segments[segments.length - 1].endOffsetSeconds : 100);

  const getColor = (c: Classification) => {
    switch (c) {
      case 'CPU_BOUND': return 'var(--cpu-color)';
      case 'GPU_BOUND': return 'var(--gpu-color)';
      case 'BALANCED': return 'var(--balanced-color)';
      case 'IDLE': return 'var(--idle-color)';
      case 'UNKNOWN':
      default: return 'var(--unknown-color)';
    }
  };

  const formatTime = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s < 10 ? '0' : ''}${s}`;
  };

  return (
    <div className="glass-panel" style={{ padding: '20px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '14px' }}>
        <div>
          <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)' }}>
            Workload Classification Timeline
          </h3>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            Merged contiguous analysis windows. Click any block to inspect supporting sensor evidence.
          </p>
        </div>
        <div style={{ display: 'flex', gap: '12px', fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: 'var(--cpu-color)' }}></span> CPU-Limited
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: 'var(--gpu-color)' }}></span> GPU-Limited
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: 'var(--balanced-color)' }}></span> Balanced
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: 'var(--idle-color)' }}></span> Idle
          </span>
        </div>
      </div>

      {/* Visual Timeline Bar */}
      <div
        style={{
          display: 'flex',
          width: '100%',
          height: '38px',
          borderRadius: 'var(--radius-md)',
          overflow: 'hidden',
          background: 'rgba(0, 0, 0, 0.4)',
          border: '1px solid var(--border-color)',
          cursor: 'pointer',
        }}
      >
        {segments.map((seg) => {
          const widthPercent = Math.max(0.5, (seg.durationSeconds / effectiveTotal) * 100);
          const isSelected = selectedSegment?.id === seg.id;
          const isThrottled = seg.throttleEvidence === 'HARDWARE_FLAG' || seg.throttleEvidence === 'HEURISTIC_DETECTED';

          return (
            <div
              key={seg.id}
              onClick={() => setSelectedSegment(seg)}
              title={`${seg.classification} (${seg.durationSeconds.toFixed(1)}s): ${seg.evidenceExplanation || ''}`}
              style={{
                width: `${widthPercent}%`,
                height: '100%',
                backgroundColor: getColor(seg.classification),
                opacity: isSelected ? 1 : 0.82,
                transition: 'all 0.15s ease',
                position: 'relative',
                boxShadow: isSelected ? 'inset 0 0 0 2px #ffffff' : 'none',
                borderRight: '1px solid rgba(0, 0, 0, 0.3)',
              }}
            >
              {isThrottled && (
                <div
                  style={{
                    position: 'absolute',
                    top: 0,
                    left: 0,
                    right: 0,
                    height: '4px',
                    backgroundColor: 'var(--accent-rose)',
                  }}
                />
              )}
            </div>
          );
        })}
      </div>

      {/* Time Markers */}
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '6px' }}>
        <span>0:00</span>
        <span>{formatTime(effectiveTotal / 2)}</span>
        <span>{formatTime(effectiveTotal)}</span>
      </div>

      {/* Selected Segment Evidence Inspector */}
      {selectedSegment && (
        <div
          style={{
            marginTop: '16px',
            padding: '16px',
            background: 'rgba(0, 0, 0, 0.25)',
            borderRadius: 'var(--radius-md)',
            border: '1px solid var(--border-color)',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <ClassificationBadge classification={selectedSegment.classification} />
              <ThrottleBadge evidence={selectedSegment.throttleEvidence} />
              <span style={{ fontSize: '0.8125rem', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '4px' }}>
                <Clock size={13} />
                {formatTime(selectedSegment.startOffsetSeconds)} – {formatTime(selectedSegment.endOffsetSeconds)}
                ({selectedSegment.durationSeconds.toFixed(1)}s)
              </span>
            </div>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
              {selectedSegment.sampleCount || 0} observations
            </span>
          </div>

          <div style={{ fontSize: '0.875rem', color: 'var(--text-primary)', marginBottom: '12px', display: 'flex', gap: '8px' }}>
            <Info size={16} style={{ flexShrink: 0, marginTop: '2px', color: 'var(--accent-blue)' }} />
            <span>{selectedSegment.evidenceExplanation}</span>
          </div>

          {/* Segment Average Metrics Grid */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))', gap: '10px', fontSize: '0.8125rem' }}>
            <div style={{ padding: '8px 10px', background: 'rgba(255, 255, 255, 0.03)', borderRadius: '6px' }}>
              <span style={{ color: 'var(--text-muted)', display: 'block', fontSize: '0.75rem' }}>Avg CPU Load</span>
              <span style={{ fontWeight: 600 }}>{selectedSegment.avgCpuLoad != null ? `${selectedSegment.avgCpuLoad}%` : 'N/A'}</span>
            </div>
            <div style={{ padding: '8px 10px', background: 'rgba(255, 255, 255, 0.03)', borderRadius: '6px' }}>
              <span style={{ color: 'var(--text-muted)', display: 'block', fontSize: '0.75rem' }}>Busiest Core</span>
              <span style={{ fontWeight: 600 }}>{selectedSegment.avgCpuMaxThread != null ? `${selectedSegment.avgCpuMaxThread}%` : 'N/A'}</span>
            </div>
            <div style={{ padding: '8px 10px', background: 'rgba(255, 255, 255, 0.03)', borderRadius: '6px' }}>
              <span style={{ color: 'var(--text-muted)', display: 'block', fontSize: '0.75rem' }}>Avg GPU Load</span>
              <span style={{ fontWeight: 600 }}>{selectedSegment.avgGpuLoad != null ? `${selectedSegment.avgGpuLoad}%` : 'N/A'}</span>
            </div>
            <div style={{ padding: '8px 10px', background: 'rgba(255, 255, 255, 0.03)', borderRadius: '6px' }}>
              <span style={{ color: 'var(--text-muted)', display: 'block', fontSize: '0.75rem' }}>Peak CPU Temp</span>
              <span style={{ fontWeight: 600 }}>{selectedSegment.peakCpuTempC != null ? `${selectedSegment.peakCpuTempC}°C` : 'N/A'}</span>
            </div>
            <div style={{ padding: '8px 10px', background: 'rgba(255, 255, 255, 0.03)', borderRadius: '6px' }}>
              <span style={{ color: 'var(--text-muted)', display: 'block', fontSize: '0.75rem' }}>Peak GPU Temp</span>
              <span style={{ fontWeight: 600 }}>{selectedSegment.peakGpuTempC != null ? `${selectedSegment.peakGpuTempC}°C` : 'N/A'}</span>
            </div>
            <div style={{ padding: '8px 10px', background: 'rgba(255, 255, 255, 0.03)', borderRadius: '6px' }}>
              <span style={{ color: 'var(--text-muted)', display: 'block', fontSize: '0.75rem' }}>Avg CPU Clock</span>
              <span style={{ fontWeight: 600 }}>{selectedSegment.avgCpuClockMhz != null ? `${Math.round(selectedSegment.avgCpuClockMhz)} MHz` : 'N/A'}</span>
            </div>
            <div style={{ padding: '8px 10px', background: 'rgba(255, 255, 255, 0.03)', borderRadius: '6px' }}>
              <span style={{ color: 'var(--text-muted)', display: 'block', fontSize: '0.75rem' }}>Avg GPU Clock</span>
              <span style={{ fontWeight: 600 }}>{selectedSegment.avgGpuClockMhz != null ? `${Math.round(selectedSegment.avgGpuClockMhz)} MHz` : 'N/A'}</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
