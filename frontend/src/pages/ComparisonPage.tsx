import React, { useState, useEffect } from 'react';
import { AnalysisRun, RunComparison, formatWorkloadType } from '../types';
import { api } from '../services/api';
import { DiagnosticDisclaimer } from '../components/DiagnosticDisclaimer';
import {
  GitCompare,
  ArrowRight,
  AlertTriangle,
  FileText,
  Clock,
  ArrowUpRight,
  ArrowDownRight,
  Minus,
  CheckCircle2,
  HelpCircle,
} from 'lucide-react';

interface Props {
  initialRunIdA?: number;
  initialRunIdB?: number;
  onRequireAuth: () => void;
  isAuthenticated: boolean;
}

export const ComparisonPage: React.FC<Props> = ({
  initialRunIdA,
  initialRunIdB,
  onRequireAuth,
  isAuthenticated,
}) => {
  const [runs, setRuns] = useState<AnalysisRun[]>([]);
  const [runIdA, setRunIdA] = useState<number | ''>(initialRunIdA || '');
  const [runIdB, setRunIdB] = useState<number | ''>(initialRunIdB || '');
  const [comparison, setComparison] = useState<RunComparison | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isAuthenticated) return;
    const loadRunsList = async () => {
      try {
        const res = await api.listRuns(0, 100);
        const completed = res.content.filter((r) => r.status === 'COMPLETED');
        setRuns(completed);

        if (!runIdA && completed.length > 0) {
          setRunIdA(completed[0].id);
        }
        if (!runIdB && completed.length > 1) {
          setRunIdB(completed[1].id);
        }
      } catch (err: any) {
        console.error('Failed to load runs for comparison:', err);
      }
    };
    loadRunsList();
  }, [isAuthenticated]);

  const handleCompare = async () => {
    if (!runIdA || !runIdB) {
      setError('Please select both Run A and Run B to compare.');
      return;
    }
    if (runIdA === runIdB) {
      setError('Cannot compare a run with itself. Please select two distinct runs.');
      return;
    }

    setLoading(true);
    setError(null);
    try {
      const data = await api.compareRuns(Number(runIdA), Number(runIdB));
      setComparison(data);
    } catch (err: any) {
      setError(err.message || 'Comparison failed.');
      setComparison(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (runIdA && runIdB && runIdA !== runIdB) {
      handleCompare();
    }
  }, [runIdA, runIdB]);

  if (!isAuthenticated) {
    return (
      <div style={{ maxWidth: '1380px', margin: '40px auto', padding: '0 24px', textAlign: 'center' }}>
        <p style={{ color: 'var(--text-muted)', marginBottom: '14px' }}>Please sign in to compare telemetry runs.</p>
        <button onClick={onRequireAuth} className="btn btn-primary">Sign In / Register</button>
      </div>
    );
  }

  return (
    <div style={{ maxWidth: '1380px', margin: '0 auto', padding: '32px 24px', display: 'flex', flexDirection: 'column', gap: '24px' }}>
      {/* Title */}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <GitCompare size={24} style={{ color: 'var(--accent-blue)' }} />
          <h1 style={{ fontSize: '1.375rem', fontWeight: 700, color: '#ffffff' }}>
            Telemetry Run Comparison
          </h1>
        </div>
        <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginTop: '4px' }}>
          Side-by-side analysis of two workload recordings with shift metrics, sensor parity, and comparability warnings.
        </p>
      </div>

      {/* Selector Controls */}
      <div className="glass-panel" style={{ padding: '20px' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr auto 1fr', gap: '16px', alignItems: 'center' }}>
          {/* Run A Selector */}
          <div>
            <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '6px' }}>
              Baseline Run (Run A)
            </label>
            <select
              value={runIdA}
              onChange={(e) => setRunIdA(e.target.value ? Number(e.target.value) : '')}
              className="input-field"
              style={{ cursor: 'pointer' }}
            >
              <option value="">Select Run A...</option>
              {runs.map((r) => (
                <option key={r.id} value={r.id}>
                  #{r.id} — {r.fileName} · {formatWorkloadType(r.workloadType)} ({r.description || 'No description'})
                </option>
              ))}
            </select>
          </div>

          <div style={{ padding: '0 8px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center' }}>
            <ArrowRight size={20} />
          </div>

          {/* Run B Selector */}
          <div>
            <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '6px' }}>
              Comparison Run (Run B)
            </label>
            <select
              value={runIdB}
              onChange={(e) => setRunIdB(e.target.value ? Number(e.target.value) : '')}
              className="input-field"
              style={{ cursor: 'pointer' }}
            >
              <option value="">Select Run B...</option>
              {runs.map((r) => (
                <option key={r.id} value={r.id}>
                  #{r.id} — {r.fileName} · {formatWorkloadType(r.workloadType)} ({r.description || 'No description'})
                </option>
              ))}
            </select>
          </div>
        </div>

        {error && (
          <div
            style={{
              padding: '10px 14px',
              backgroundColor: 'rgba(244, 63, 94, 0.15)',
              border: '1px solid rgba(244, 63, 94, 0.3)',
              borderRadius: 'var(--radius-md)',
              color: '#fda4af',
              fontSize: '0.8125rem',
              marginTop: '14px',
            }}
          >
            {error}
          </div>
        )}
      </div>

      {loading && (
        <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>
          Computing side-by-side telemetry comparison...
        </div>
      )}

      {comparison && !loading && (
        <>
          {/* Side-by-Side Run Overview Cards */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '16px' }}>
            <div className="glass-panel" style={{ padding: '18px 22px', borderLeft: '4px solid var(--cpu-color)' }}>
              <span className="badge badge-cpu" style={{ marginBottom: '8px' }}>Run A (Baseline)</span>
              <h3 style={{ fontSize: '1.125rem', fontWeight: 700, color: '#ffffff' }}>
                {comparison.runA.fileName}
              </h3>
              <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginTop: '4px' }}>
                {comparison.runA.description || 'No description provided'}
              </p>
              <p style={{ fontSize: '0.75rem', color: 'var(--accent-cyan)', marginTop: '4px' }}>
                Declared workload: {formatWorkloadType(comparison.runA.workloadType)}
              </p>
              <div style={{ display: 'flex', gap: '16px', marginTop: '12px', fontSize: '0.8125rem', color: 'var(--text-secondary)' }}>
                <span>Duration: <strong>{comparison.runA.totalDurationSeconds?.toFixed(0)}s</strong></span>
                <span>CPU-Bound: <strong>{comparison.runA.cpuBoundActivePercent != null ? `${comparison.runA.cpuBoundActivePercent}%` : 'N/A'}</strong></span>
                <span>GPU-Bound: <strong>{comparison.runA.gpuBoundActivePercent != null ? `${comparison.runA.gpuBoundActivePercent}%` : 'N/A'}</strong></span>
              </div>
            </div>

            <div className="glass-panel" style={{ padding: '18px 22px', borderLeft: '4px solid var(--gpu-color)' }}>
              <span className="badge badge-gpu" style={{ marginBottom: '8px' }}>Run B (Comparison)</span>
              <h3 style={{ fontSize: '1.125rem', fontWeight: 700, color: '#ffffff' }}>
                {comparison.runB.fileName}
              </h3>
              <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginTop: '4px' }}>
                {comparison.runB.description || 'No description provided'}
              </p>
              <p style={{ fontSize: '0.75rem', color: 'var(--accent-cyan)', marginTop: '4px' }}>
                Declared workload: {formatWorkloadType(comparison.runB.workloadType)}
              </p>
              <div style={{ display: 'flex', gap: '16px', marginTop: '12px', fontSize: '0.8125rem', color: 'var(--text-secondary)' }}>
                <span>Duration: <strong>{comparison.runB.totalDurationSeconds?.toFixed(0)}s</strong></span>
                <span>CPU-Bound: <strong>{comparison.runB.cpuBoundActivePercent != null ? `${comparison.runB.cpuBoundActivePercent}%` : 'N/A'}</strong></span>
                <span>GPU-Bound: <strong>{comparison.runB.gpuBoundActivePercent != null ? `${comparison.runB.gpuBoundActivePercent}%` : 'N/A'}</strong></span>
              </div>
            </div>
          </div>

          {/* Comparability Warnings */}
          {comparison.comparabilityWarnings.length > 0 && (
            <div
              className="glass-panel"
              style={{
                padding: '16px 20px',
                borderLeft: '4px solid var(--accent-amber)',
                background: 'rgba(245, 158, 11, 0.08)',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
                <AlertTriangle size={18} style={{ color: 'var(--accent-amber)' }} />
                <h4 style={{ fontSize: '0.875rem', fontWeight: 600, color: '#ffffff' }}>
                  Workload Comparability Advisory
                </h4>
              </div>
              <ul style={{ paddingLeft: '20px', fontSize: '0.8125rem', color: 'var(--text-secondary)', display: 'flex', flexDirection: 'column', gap: '4px' }}>
                {comparison.comparabilityWarnings.map((w, idx) => (
                  <li key={idx}>{w}</li>
                ))}
              </ul>
            </div>
          )}

          {/* Sensor Coverage Diffs */}
          {comparison.sensorCoverageDiffs.length > 0 && (
            <div
              className="glass-panel"
              style={{
                padding: '14px 20px',
                borderLeft: '4px solid var(--accent-cyan)',
                background: 'rgba(6, 182, 212, 0.08)',
                fontSize: '0.8125rem',
              }}
            >
              <strong style={{ color: 'var(--accent-cyan)' }}>Sensor Discrepancies:</strong>
              <ul style={{ paddingLeft: '20px', marginTop: '4px', color: 'var(--text-secondary)' }}>
                {comparison.sensorCoverageDiffs.map((diff, idx) => (
                  <li key={idx}>{diff}</li>
                ))}
              </ul>
            </div>
          )}

          {/* Detailed Metric Comparisons Table */}
          <div className="glass-panel" style={{ padding: '24px' }}>
            <h3 style={{ fontSize: '1rem', fontWeight: 600, color: '#ffffff', marginBottom: '4px' }}>
              Metric Differences & Shift Breakdown
            </h3>
            <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginBottom: '18px' }}>
              Comparative breakdown showing absolute shift (&Delta;) and percentage change from Run A to Run B.
            </p>

            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.875rem' }}>
                <thead>
                  <tr style={{ borderBottom: '1px solid var(--border-color)', color: 'var(--text-muted)', textAlign: 'left' }}>
                    <th style={{ padding: '10px 14px' }}>Metric</th>
                    <th style={{ padding: '10px 14px' }}>Category</th>
                    <th style={{ padding: '10px 14px' }}>Run A</th>
                    <th style={{ padding: '10px 14px' }}>Run B</th>
                    <th style={{ padding: '10px 14px' }}>Delta (&Delta;)</th>
                    <th style={{ padding: '10px 14px' }}>Shift %</th>
                    <th style={{ padding: '10px 14px' }}>Diagnostic Context</th>
                  </tr>
                </thead>
                <tbody>
                  {comparison.metrics.map((m, idx) => {
                    const hasDiff = m.difference != null;
                    const isPositive = (m.difference || 0) > 0;
                    const isZero = m.difference === 0;

                    return (
                      <tr
                        key={idx}
                        style={{ borderBottom: '1px solid rgba(255, 255, 255, 0.04)' }}
                      >
                        <td style={{ padding: '12px 14px', fontWeight: 600, color: 'var(--text-primary)' }}>
                          {m.name}
                        </td>

                        <td style={{ padding: '12px 14px', color: 'var(--text-muted)', fontSize: '0.75rem' }}>
                          <span className="badge" style={{ background: 'rgba(255, 255, 255, 0.05)', color: 'var(--text-secondary)' }}>
                            {m.category}
                          </span>
                        </td>

                        <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)' }}>
                          {m.valueA != null ? `${m.valueA} ${m.unit}` : 'N/A'}
                        </td>

                        <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)' }}>
                          {m.valueB != null ? `${m.valueB} ${m.unit}` : 'N/A'}
                        </td>

                        <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)' }}>
                          {hasDiff ? (
                            <span
                              style={{
                                color: isZero ? 'var(--text-muted)' : (isPositive ? 'var(--accent-blue)' : 'var(--accent-purple)'),
                                fontWeight: 600,
                              }}
                            >
                              {isPositive ? `+${m.difference}` : m.difference} {m.unit}
                            </span>
                          ) : (
                            '—'
                          )}
                        </td>

                        <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)' }}>
                          {m.percentChange != null ? (
                            <span
                              style={{
                                display: 'inline-flex',
                                alignItems: 'center',
                                gap: '2px',
                                color: m.percentChange > 0 ? 'var(--accent-blue)' : 'var(--accent-purple)',
                                fontWeight: 600,
                              }}
                            >
                              {m.percentChange > 0 ? <ArrowUpRight size={13} /> : <ArrowDownRight size={13} />}
                              {m.percentChange > 0 ? `+${m.percentChange}%` : `${m.percentChange}%`}
                            </span>
                          ) : (
                            <span style={{ color: 'var(--text-muted)' }}>—</span>
                          )}
                        </td>

                        <td style={{ padding: '12px 14px', fontSize: '0.8125rem', color: 'var(--text-secondary)', maxWidth: '300px' }}>
                          {m.interpretation}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>

          {/* Diagnostic Limitation Note */}
          <div
            className="glass-panel"
            style={{
              padding: '16px 20px',
              borderLeft: '4px solid var(--accent-purple)',
              background: 'rgba(139, 92, 246, 0.08)',
              fontSize: '0.8125rem',
              color: 'var(--text-secondary)',
              lineHeight: '1.6',
            }}
          >
            <strong style={{ color: 'var(--accent-purple)' }}>Analytical Disclaimer: </strong>
            {comparison.conclusionNote}
          </div>

          <DiagnosticDisclaimer />
        </>
      )}
    </div>
  );
};
