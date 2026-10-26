import React, { useState, useEffect } from 'react';
import { AnalysisRun } from '../types';
import { api } from '../services/api';
import { ClassificationBadge } from '../components/ClassificationBadge';
import { MetricCard } from '../components/MetricCard';
import {
  UploadCloud,
  FileText,
  Clock,
  Trash2,
  GitCompare,
  ArrowRight,
  CheckCircle,
  AlertCircle,
  Cpu,
  Zap,
  Flame,
  Sparkles,
  RefreshCw,
} from 'lucide-react';

interface Props {
  onSelectRun: (runId: number) => void;
  onCompareRuns: (runIdA: number, runIdB?: number) => void;
  onRequireAuth: () => void;
  isAuthenticated: boolean;
}

export const DashboardPage: React.FC<Props> = ({
  onSelectRun,
  onCompareRuns,
  onRequireAuth,
  isAuthenticated,
}) => {
  const [runs, setRuns] = useState<AnalysisRun[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [description, setDescription] = useState('');
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploadMessage, setUploadMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const loadRuns = async () => {
    if (!isAuthenticated) {
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const data = await api.listRuns(0, 50);
      setRuns(data.content);
    } catch (err: any) {
      console.error('Failed to load runs:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadRuns();
  }, [isAuthenticated]);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      setSelectedFile(e.target.files[0]);
      setUploadMessage(null);
    }
  };

  const handleUpload = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isAuthenticated) {
      onRequireAuth();
      return;
    }
    if (!selectedFile) return;

    setUploading(true);
    setUploadMessage(null);

    try {
      const res = await api.uploadRun(selectedFile, description);
      setUploadMessage({ type: 'success', text: `Upload successful! ${res.validRows || 0} samples analyzed.` });
      setSelectedFile(null);
      setDescription('');
      loadRuns();
    } catch (err: any) {
      setUploadMessage({ type: 'error', text: err.message || 'CSV ingestion failed.' });
    } finally {
      setUploading(false);
    }
  };

  const handleDelete = async (id: number, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!window.confirm(`Delete analysis run #${id}? This cannot be undone.`)) return;

    try {
      await api.deleteRun(id);
      setRuns(runs.filter((r) => r.id !== id));
    } catch (err: any) {
      alert(`Failed to delete run: ${err.message}`);
    }
  };

  const handleLoadSynthetic = async (type: 'cpu' | 'gpu' | 'throttle') => {
    if (!isAuthenticated) {
      onRequireAuth();
      return;
    }

    setUploading(true);
    setUploadMessage(null);

    let csvContent = '';
    let fileName = '';
    let desc = '';

    if (type === 'cpu') {
      fileName = 'synthetic_cpu_bound_demo.csv';
      desc = '[Synthetic Demo] Heavy CPU workload (compilation/physics simulation)';
      csvContent = `Date,Time,"Total CPU Usage [%]","Core Max [%]","CPU Package [°C]","Core Clocks (avg) [MHz]","GPU Core Load [%]","GPU Temperature [°C]","GPU Clock [MHz]","Core Thermal Throttling [Yes/No]"
26.10.2026,14:00:00,93.5,96.8,78.5,4650.0,60.2,64.0,1850.0,No
26.10.2026,14:00:02,91.2,95.0,79.0,4650.0,63.5,64.5,1850.0,No
26.10.2026,14:00:04,94.0,97.5,80.2,4625.0,59.0,65.0,1845.0,No
26.10.2026,14:00:06,89.5,94.8,79.8,4650.0,61.2,64.8,1850.0,No
26.10.2026,14:00:08,94.8,98.2,81.0,4600.0,64.0,65.2,1840.0,No
26.10.2026,14:00:10,92.5,96.1,80.5,4625.0,62.5,65.0,1845.0,No
26.10.2026,14:00:12,90.4,95.5,79.5,4650.0,60.5,64.5,1850.0,No
26.10.2026,14:00:14,93.5,97.0,81.2,4600.0,62.8,65.5,1845.0,No`;
    } else if (type === 'gpu') {
      fileName = 'synthetic_gpu_bound_demo.csv';
      desc = '[Synthetic Demo] High graphics settings 3D rasterization';
      csvContent = `Date,Time,"Total CPU Usage [%]","Core Max [%]","CPU Package [°C]","Core Clocks (avg) [MHz]","GPU Core Load [%]","GPU Temperature [°C]","GPU Clock [MHz]","Core Thermal Throttling [Yes/No]"
26.10.2026,14:10:00,45.0,62.0,65.0,4700.0,98.5,72.0,2100.0,No
26.10.2026,14:10:02,46.5,60.5,65.5,4700.0,99.0,72.5,2100.0,No
26.10.2026,14:10:04,44.2,63.1,65.8,4700.0,97.8,73.0,2095.0,No
26.10.2026,14:10:06,45.8,59.8,66.0,4700.0,98.2,73.2,2100.0,No
26.10.2026,14:10:08,47.0,64.0,66.5,4700.0,99.5,73.8,2090.0,No
26.10.2026,14:10:10,46.0,61.5,66.2,4700.0,98.0,73.5,2100.0,No
26.10.2026,14:10:12,43.5,58.5,65.8,4700.0,97.5,73.0,2100.0,No
26.10.2026,14:10:14,48.0,65.0,67.0,4700.0,99.2,74.0,2085.0,No`;
    } else {
      fileName = 'synthetic_throttling_demo.csv';
      desc = '[Synthetic Demo] Thermal throttling with depressed core clock';
      csvContent = `Date,Time,"Total CPU Usage [%]","Core Max [%]","CPU Package [°C]","Core Clocks (avg) [MHz]","GPU Core Load [%]","GPU Temperature [°C]","GPU Clock [MHz]","Core Thermal Throttling [Yes/No]"
26.10.2026,14:20:00,88.0,92.0,98.5,3100.0,75.0,78.0,1800.0,Yes
26.10.2026,14:20:02,89.5,93.5,99.0,3050.0,76.5,78.5,1800.0,Yes
26.10.2026,14:20:04,91.2,95.0,100.2,2950.0,74.0,79.0,1790.0,Yes
26.10.2026,14:20:06,87.0,91.0,98.8,3150.0,75.2,78.8,1800.0,Yes
26.10.2026,14:20:08,90.5,94.0,99.5,3050.0,77.0,79.2,1795.0,Yes
26.10.2026,14:20:10,88.5,92.5,99.0,3100.0,75.0,79.0,1800.0,Yes
26.10.2026,14:20:12,86.0,90.5,98.0,3200.0,73.5,78.5,1800.0,Yes
26.10.2026,14:20:14,92.0,96.0,100.5,2900.0,76.8,79.5,1785.0,Yes`;
    }

    const blob = new Blob([csvContent], { type: 'text/csv' });
    const demoFile = new File([blob], fileName, { type: 'text/csv' });

    try {
      const res = await api.uploadRun(demoFile, desc);
      setUploadMessage({ type: 'success', text: `Demo loaded! Synthetic run #${res.runId} analyzed successfully.` });
      loadRuns();
    } catch (err: any) {
      setUploadMessage({ type: 'error', text: err.message || 'Demo upload failed.' });
    } finally {
      setUploading(false);
    }
  };

  const completedRuns = runs.filter((r) => r.status === 'COMPLETED');
  const throttledCount = completedRuns.filter((r) => (r.throttleDurationSeconds || 0) > 0).length;

  return (
    <div style={{ maxWidth: '1380px', margin: '0 auto', padding: '32px 24px', display: 'flex', flexDirection: 'column', gap: '28px' }}>
      {/* Top Banner / Metrics */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '16px' }}>
        <MetricCard
          title="Analyzed Runs"
          value={completedRuns.length}
          subtitle="Completed sessions in database"
          icon={<FileText size={20} />}
          highlight="blue"
        />
        <MetricCard
          title="CPU-Limited Sessions"
          value={completedRuns.filter((r) => (r.cpuBoundActivePercent || 0) > 50).length}
          subtitle="Workloads primarily CPU-constrained"
          icon={<Cpu size={20} />}
          highlight="blue"
        />
        <MetricCard
          title="GPU-Limited Sessions"
          value={completedRuns.filter((r) => (r.gpuBoundActivePercent || 0) > 50).length}
          subtitle="Workloads primarily GPU-saturated"
          icon={<Zap size={20} />}
          highlight="emerald"
        />
        <MetricCard
          title="Throttled Runs"
          value={throttledCount}
          subtitle="Sessions with thermal events detected"
          icon={<Flame size={20} />}
          highlight="rose"
        />
      </div>

      {/* CSV Ingestion & Demo Upload Section */}
      <div className="glass-panel" style={{ padding: '24px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '18px', flexWrap: 'wrap', gap: '12px' }}>
          <div>
            <h2 style={{ fontSize: '1.125rem', fontWeight: 700, color: '#ffffff' }}>
              Upload HWiNFO64 Telemetry Log
            </h2>
            <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
              Upload an exported CSV file recording hardware sensor readings over time.
            </p>
          </div>

          {/* Synthetic Demo Buttons */}
          <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', alignSelf: 'center', marginRight: '4px' }}>
              Synthetic Demos:
            </span>
            <button
              onClick={() => handleLoadSynthetic('cpu')}
              disabled={uploading}
              className="btn btn-secondary"
              style={{ fontSize: '0.75rem', padding: '5px 10px' }}
            >
              <Cpu size={13} style={{ color: 'var(--cpu-color)' }} />
              + Synthetic CPU Demo
            </button>
            <button
              onClick={() => handleLoadSynthetic('gpu')}
              disabled={uploading}
              className="btn btn-secondary"
              style={{ fontSize: '0.75rem', padding: '5px 10px' }}
            >
              <Zap size={13} style={{ color: 'var(--gpu-color)' }} />
              + Synthetic GPU Demo
            </button>
            <button
              onClick={() => handleLoadSynthetic('throttle')}
              disabled={uploading}
              className="btn btn-secondary"
              style={{ fontSize: '0.75rem', padding: '5px 10px' }}
            >
              <Flame size={13} style={{ color: 'var(--accent-rose)' }} />
              + Synthetic Throttle Demo
            </button>
          </div>
        </div>

        {uploadMessage && (
          <div
            style={{
              padding: '12px 16px',
              borderRadius: 'var(--radius-md)',
              fontSize: '0.875rem',
              marginBottom: '16px',
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
              background: uploadMessage.type === 'success' ? 'rgba(16, 185, 129, 0.15)' : 'rgba(244, 63, 94, 0.15)',
              border: `1px solid ${uploadMessage.type === 'success' ? 'rgba(16, 185, 129, 0.3)' : 'rgba(244, 63, 94, 0.3)'}`,
              color: uploadMessage.type === 'success' ? '#6ee7b7' : '#fda4af',
            }}
          >
            {uploadMessage.type === 'success' ? <CheckCircle size={16} /> : <AlertCircle size={16} />}
            {uploadMessage.text}
          </div>
        )}

        <form onSubmit={handleUpload} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '14px' }}>
            <div>
              <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '6px' }}>
                CSV File (.csv)
              </label>
              <input
                type="file"
                accept=".csv"
                onChange={handleFileChange}
                className="input-field"
                style={{ cursor: 'pointer' }}
              />
            </div>

            <div>
              <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '6px' }}>
                Workload Description (Optional)
              </label>
              <input
                type="text"
                placeholder="e.g. Cyberpunk 2077 Ultra 1440p, Blender Render, Maven Build"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                className="input-field"
              />
            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '6px' }}>
            <button
              type="submit"
              disabled={!selectedFile || uploading}
              className="btn btn-primary"
              style={{ padding: '9px 20px' }}
            >
              <UploadCloud size={16} />
              {uploading ? 'Processing & Analyzing...' : 'Upload & Run Analysis'}
            </button>
          </div>
        </form>
      </div>

      {/* Previous Runs Table */}
      <div className="glass-panel" style={{ padding: '24px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '18px' }}>
          <div>
            <h2 style={{ fontSize: '1.125rem', fontWeight: 700, color: '#ffffff' }}>
              Telemetry Analysis History
            </h2>
            <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
              Click any run to view timelines, evidence, and downsampled time-series charts.
            </p>
          </div>

          <button onClick={loadRuns} className="btn btn-secondary" style={{ padding: '6px 12px', fontSize: '0.75rem' }}>
            <RefreshCw size={13} />
            Refresh
          </button>
        </div>

        {loading ? (
          <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>
            Loading telemetry runs from database...
          </div>
        ) : !isAuthenticated ? (
          <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>
            <p style={{ marginBottom: '14px' }}>Please sign in to view and manage your analysis runs.</p>
            <button onClick={onRequireAuth} className="btn btn-primary">Sign In / Register</button>
          </div>
        ) : runs.length === 0 ? (
          <div style={{ padding: '48px 20px', textAlign: 'center', color: 'var(--text-muted)' }}>
            <FileText size={36} style={{ margin: '0 auto 12px', opacity: 0.4 }} />
            <p style={{ fontSize: '1rem', fontWeight: 500, color: 'var(--text-secondary)' }}>No telemetry runs recorded yet</p>
            <p style={{ fontSize: '0.8125rem', marginTop: '4px' }}>
              Upload an HWiNFO64 CSV export above or click one of the synthetic demo buttons to inspect a pre-packaged run.
            </p>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.875rem' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--border-color)', color: 'var(--text-muted)', textAlign: 'left' }}>
                  <th style={{ padding: '10px 14px' }}>Run / Workload</th>
                  <th style={{ padding: '10px 14px' }}>Status</th>
                  <th style={{ padding: '10px 14px' }}>Duration</th>
                  <th style={{ padding: '10px 14px' }}>Active Classification %</th>
                  <th style={{ padding: '10px 14px' }}>Thermal Status</th>
                  <th style={{ padding: '10px 14px' }}>Peak Temps</th>
                  <th style={{ padding: '10px 14px', textAlign: 'right' }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => {
                  const isCompleted = run.status === 'COMPLETED';
                  const durSec = run.totalDurationSeconds || 0;
                  const durFormatted = `${Math.floor(durSec / 60)}m ${Math.round(durSec % 60)}s`;

                  return (
                    <tr
                      key={run.id}
                      onClick={() => isCompleted && onSelectRun(run.id)}
                      style={{
                        borderBottom: '1px solid rgba(255, 255, 255, 0.04)',
                        cursor: isCompleted ? 'pointer' : 'default',
                        transition: 'background 0.15s ease',
                      }}
                      onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = 'rgba(255, 255, 255, 0.03)')}
                      onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = 'transparent')}
                    >
                      <td style={{ padding: '12px 14px' }}>
                        <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>
                          {run.fileName}
                        </div>
                        {run.description && (
                          <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '2px' }}>
                            {run.description}
                          </div>
                        )}
                      </td>

                      <td style={{ padding: '12px 14px' }}>
                        <span
                          className="badge"
                          style={{
                            background: run.status === 'COMPLETED' ? 'rgba(16, 185, 129, 0.15)' : (run.status === 'FAILED' ? 'rgba(244, 63, 94, 0.15)' : 'rgba(245, 158, 11, 0.15)'),
                            color: run.status === 'COMPLETED' ? '#34d399' : (run.status === 'FAILED' ? '#fb7185' : '#fbbf24'),
                            border: `1px solid ${run.status === 'COMPLETED' ? 'rgba(16, 185, 129, 0.3)' : (run.status === 'FAILED' ? 'rgba(244, 63, 94, 0.3)' : 'rgba(245, 158, 11, 0.3)')}`,
                          }}
                        >
                          {run.status}
                        </span>
                      </td>

                      <td style={{ padding: '12px 14px', color: 'var(--text-secondary)', fontFamily: 'var(--font-mono)' }}>
                        {isCompleted ? durFormatted : '—'}
                      </td>

                      <td style={{ padding: '12px 14px' }}>
                        {isCompleted ? (
                          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                            <span style={{ color: 'var(--cpu-color)', fontSize: '0.75rem', fontWeight: 600 }}>
                              CPU: {run.cpuBoundActivePercent ?? 0}%
                            </span>
                            <span style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>|</span>
                            <span style={{ color: 'var(--gpu-color)', fontSize: '0.75rem', fontWeight: 600 }}>
                              GPU: {run.gpuBoundActivePercent ?? 0}%
                            </span>
                            <span style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>|</span>
                            <span style={{ color: 'var(--balanced-color)', fontSize: '0.75rem', fontWeight: 600 }}>
                              Bal: {run.balancedActivePercent ?? 0}%
                            </span>
                          </div>
                        ) : (
                          <span style={{ color: 'var(--text-muted)' }}>—</span>
                        )}
                      </td>

                      <td style={{ padding: '12px 14px' }}>
                        {isCompleted ? (
                          (run.throttleDurationSeconds || 0) > 0 ? (
                            <span className="badge badge-throttle">
                              <Flame size={12} />
                              {run.throttleDurationSeconds?.toFixed(0)}s Throttled
                            </span>
                          ) : (
                            <span className="badge badge-balanced">Normal</span>
                          )
                        ) : (
                          '—'
                        )}
                      </td>

                      <td style={{ padding: '12px 14px', color: 'var(--text-secondary)', fontSize: '0.8125rem' }}>
                        {isCompleted && (run.peakCpuTempC || run.peakGpuTempC) ? (
                          <span>
                            CPU: {run.peakCpuTempC ? `${run.peakCpuTempC}°C` : 'N/A'}, GPU: {run.peakGpuTempC ? `${run.peakGpuTempC}°C` : 'N/A'}
                          </span>
                        ) : (
                          '—'
                        )}
                      </td>

                      <td style={{ padding: '12px 14px', textAlign: 'right' }}>
                        <div style={{ display: 'inline-flex', gap: '6px' }} onClick={(e) => e.stopPropagation()}>
                          {isCompleted && (
                            <>
                              <button
                                onClick={() => onSelectRun(run.id)}
                                className="btn btn-secondary"
                                style={{ padding: '5px 10px', fontSize: '0.75rem' }}
                                title="Inspect Run"
                              >
                                View
                                <ArrowRight size={13} />
                              </button>
                              <button
                                onClick={() => onCompareRuns(run.id)}
                                className="btn btn-secondary"
                                style={{ padding: '5px 10px', fontSize: '0.75rem' }}
                                title="Compare against another run"
                              >
                                <GitCompare size={13} />
                                Compare
                              </button>
                            </>
                          )}
                          <button
                            onClick={(e) => handleDelete(run.id, e)}
                            className="btn btn-danger"
                            style={{ padding: '5px 8px', fontSize: '0.75rem' }}
                            title="Delete Run"
                          >
                            <Trash2 size={13} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};
