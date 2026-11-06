import React from 'react';
import { AlertCircle, HelpCircle } from 'lucide-react';

export const DiagnosticDisclaimer: React.FC = () => {
  return (
    <div
      className="glass-panel"
      style={{
        padding: '18px 22px',
        borderLeft: '4px solid var(--accent-blue)',
        background: 'rgba(15, 23, 42, 0.65)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
        <AlertCircle size={18} style={{ color: 'var(--accent-blue)' }} />
        <h4 style={{ fontSize: '0.9375rem', fontWeight: 600, color: 'var(--text-primary)' }}>
          Methodological Scope & Explainability Guidelines
        </h4>
      </div>

      <div style={{ fontSize: '0.8125rem', color: 'var(--text-secondary)', lineHeight: '1.6' }}>
        <p style={{ marginBottom: '8px' }}>
          <strong>PerfTrace is a rule-based diagnostic aid, not a definitive proof tool.</strong> High utilization alone does not indicate a system fault or bottleneck—modern processors and graphics cards are designed to maximize throughput under heavy workloads.
        </p>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '12px', marginTop: '10px' }}>
          <div style={{ padding: '10px 12px', background: 'rgba(0, 0, 0, 0.2)', borderRadius: '6px' }}>
            <span style={{ color: 'var(--cpu-color)', fontWeight: 600 }}>CPU-Limited:</span>
            <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '2px' }}>
              GPU load &lt; 90% while single thread or total CPU ≥ 90%. Suggests the GPU was ready to render but waiting on game logic, draw submission, or CPU execution.
            </p>
          </div>

          <div style={{ padding: '10px 12px', background: 'rgba(0, 0, 0, 0.2)', borderRadius: '6px' }}>
            <span style={{ color: 'var(--gpu-color)', fontWeight: 600 }}>GPU-Limited:</span>
            <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '2px' }}>
              GPU load &ge; 95% while CPU thread load remains &lt; 85%. Indicates GPU compute/fillrate was the primary throughput delimiter.
            </p>
          </div>

          <div style={{ padding: '10px 12px', background: 'rgba(0, 0, 0, 0.2)', borderRadius: '6px' }}>
            <span style={{ color: 'var(--balanced-color)', fontWeight: 600 }}>Balanced / Other:</span>
            <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '2px' }}>
              Active load where neither threshold was crossed. Requires a busiest-thread reading; without it the window is reported as Unknown. Does <em>not</em> prove optimal efficiency, but confirms no single resource bottleneck rule was triggered.
            </p>
          </div>

          <div style={{ padding: '10px 12px', background: 'rgba(0, 0, 0, 0.2)', borderRadius: '6px' }}>
            <span style={{ color: 'var(--accent-rose)', fontWeight: 600 }}>Thermal Throttling:</span>
            <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '2px' }}>
              Requires explicit hardware register flags OR high temperature coupled with active workload and significant sustained clock frequency drops. Temperature alone never proves throttling.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};
