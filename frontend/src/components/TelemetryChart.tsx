import React, { useState } from 'react';
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend,
  Filler,
  ChartOptions,
} from 'chart.js';
import { Line } from 'react-chartjs-2';
import { TelemetrySample } from '../types';
import { Activity, Thermometer, Gauge } from 'lucide-react';

ChartJS.register(
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend,
  Filler
);

interface Props {
  samples: TelemetrySample[];
}

type ChartMode = 'load' | 'temp' | 'clock';

export const TelemetryChart: React.FC<Props> = ({ samples }) => {
  const [mode, setMode] = useState<ChartMode>('load');

  if (!samples || samples.length === 0) {
    return (
      <div className="glass-panel" style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>
        No telemetry samples available for chart rendering.
      </div>
    );
  }

  const formatOffset = (seconds: number) => {
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m}:${s < 10 ? '0' : ''}${s}`;
  };

  const labels = samples.map((s) => formatOffset(s.timeOffsetSeconds));

  const getDatasets = () => {
    if (mode === 'load') {
      return [
        {
          label: 'CPU Total Load (%)',
          data: samples.map((s) => s.cpuTotalLoad ?? null),
          borderColor: '#3b82f6',
          backgroundColor: 'rgba(59, 130, 246, 0.08)',
          borderWidth: 2,
          pointRadius: 0,
          pointHoverRadius: 4,
          fill: true,
          tension: 0.2,
        },
        {
          label: 'CPU Busiest Core (%)',
          data: samples.map((s) => s.cpuMaxThreadLoad ?? null),
          borderColor: '#8b5cf6',
          backgroundColor: 'transparent',
          borderWidth: 1.5,
          borderDash: [4, 4],
          pointRadius: 0,
          pointHoverRadius: 4,
          tension: 0.2,
        },
        {
          label: 'GPU Core Load (%)',
          data: samples.map((s) => s.gpuLoad ?? null),
          borderColor: '#10b981',
          backgroundColor: 'rgba(16, 185, 129, 0.08)',
          borderWidth: 2,
          pointRadius: 0,
          pointHoverRadius: 4,
          fill: true,
          tension: 0.2,
        },
      ];
    } else if (mode === 'temp') {
      return [
        {
          label: 'CPU Temp (°C)',
          data: samples.map((s) => s.cpuTempC ?? null),
          borderColor: '#f43f5e',
          backgroundColor: 'rgba(244, 63, 94, 0.08)',
          borderWidth: 2,
          pointRadius: 0,
          pointHoverRadius: 4,
          tension: 0.2,
        },
        {
          label: 'GPU Temp (°C)',
          data: samples.map((s) => s.gpuTempC ?? null),
          borderColor: '#f59e0b',
          backgroundColor: 'rgba(245, 158, 11, 0.08)',
          borderWidth: 2,
          pointRadius: 0,
          pointHoverRadius: 4,
          tension: 0.2,
        },
      ];
    } else {
      return [
        {
          label: 'CPU Clock (MHz)',
          data: samples.map((s) => s.cpuClockMhz ?? null),
          borderColor: '#06b6d4',
          backgroundColor: 'transparent',
          borderWidth: 2,
          pointRadius: 0,
          pointHoverRadius: 4,
          tension: 0.2,
        },
        {
          label: 'GPU Clock (MHz)',
          data: samples.map((s) => s.gpuClockMhz ?? null),
          borderColor: '#10b981',
          backgroundColor: 'transparent',
          borderWidth: 2,
          pointRadius: 0,
          pointHoverRadius: 4,
          tension: 0.2,
        },
      ];
    }
  };

  const chartData = {
    labels,
    datasets: getDatasets(),
  };

  const options: ChartOptions<'line'> = {
    responsive: true,
    maintainAspectRatio: false,
    interaction: {
      mode: 'index',
      intersect: false,
    },
    plugins: {
      legend: {
        position: 'top',
        labels: {
          color: '#9ca3af',
          font: { family: 'Inter', size: 12 },
          usePointStyle: true,
          boxWidth: 8,
        },
      },
      tooltip: {
        backgroundColor: 'rgba(17, 24, 39, 0.95)',
        titleColor: '#f3f4f6',
        bodyColor: '#e5e7eb',
        borderColor: 'rgba(255, 255, 255, 0.1)',
        borderWidth: 1,
        padding: 10,
        boxPadding: 4,
        callbacks: {
          label: (ctx) => {
            const unit = mode === 'load' ? '%' : (mode === 'temp' ? '°C' : ' MHz');
            return `${ctx.dataset.label}: ${ctx.parsed.y != null ? ctx.parsed.y : 'N/A'}${unit}`;
          },
        },
      },
    },
    scales: {
      x: {
        grid: { color: 'rgba(255, 255, 255, 0.05)' },
        ticks: {
          color: '#6b7280',
          font: { family: 'JetBrains Mono', size: 11 },
          maxTicksLimit: 12,
        },
      },
      y: {
        grid: { color: 'rgba(255, 255, 255, 0.05)' },
        ticks: {
          color: '#6b7280',
          font: { family: 'JetBrains Mono', size: 11 },
        },
        min: mode === 'load' ? 0 : undefined,
        max: mode === 'load' ? 100 : undefined,
      },
    },
  };

  return (
    <div className="glass-panel" style={{ padding: '20px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
        <div>
          <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)' }}>
            Hardware Telemetry Time Series
          </h3>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            Downsampled time series observations across recording session
          </p>
        </div>

        <div style={{ display: 'flex', gap: '6px', background: 'rgba(0, 0, 0, 0.3)', padding: '3px', borderRadius: 'var(--radius-md)' }}>
          <button
            onClick={() => setMode('load')}
            className={`btn ${mode === 'load' ? 'btn-primary' : 'btn-secondary'}`}
            style={{ padding: '5px 12px', fontSize: '0.75rem' }}
          >
            <Activity size={13} />
            Utilization (%)
          </button>
          <button
            onClick={() => setMode('temp')}
            className={`btn ${mode === 'temp' ? 'btn-primary' : 'btn-secondary'}`}
            style={{ padding: '5px 12px', fontSize: '0.75rem' }}
          >
            <Thermometer size={13} />
            Temperatures (°C)
          </button>
          <button
            onClick={() => setMode('clock')}
            className={`btn ${mode === 'clock' ? 'btn-primary' : 'btn-secondary'}`}
            style={{ padding: '5px 12px', fontSize: '0.75rem' }}
          >
            <Gauge size={13} />
            Clocks (MHz)
          </button>
        </div>
      </div>

      <div style={{ height: '320px', width: '100%', position: 'relative' }}>
        <Line data={chartData} options={options} />
      </div>
    </div>
  );
};
