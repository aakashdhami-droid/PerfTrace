import React, { useState, useEffect } from 'react';
import { Navbar } from './components/Navbar';
import { AuthModal } from './components/AuthModal';
import { DashboardPage } from './pages/DashboardPage';
import { RunDetailPage } from './pages/RunDetailPage';
import { ComparisonPage } from './pages/ComparisonPage';
import { api } from './services/api';
import {
  BookOpen,
  Cpu,
  Layers,
  ShieldAlert,
  GitCompare,
  Server,
  FileSpreadsheet,
  CheckCircle2,
} from 'lucide-react';

export const App: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'dashboard' | 'compare' | 'guide'>('dashboard');
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null);
  const [compareRunA, setCompareRunA] = useState<number | undefined>(undefined);
  const [compareRunB, setCompareRunB] = useState<number | undefined>(undefined);
  const [isAuthModalOpen, setIsAuthModalOpen] = useState(false);
  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(api.isAuthenticated());

  useEffect(() => {
    const handleAuthChange = () => {
      setIsAuthenticated(api.isAuthenticated());
    };

    window.addEventListener('perftrace_auth_change', handleAuthChange);
    return () => {
      window.removeEventListener('perftrace_auth_change', handleAuthChange);
    };
  }, []);

  const handleSelectRun = (runId: number) => {
    setSelectedRunId(runId);
  };

  const handleBackToDashboard = () => {
    setSelectedRunId(null);
  };

  const handleNavigateToCompare = (runIdA: number, runIdB?: number) => {
    setCompareRunA(runIdA);
    setCompareRunB(runIdB);
    setSelectedRunId(null);
    setActiveTab('compare');
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <Navbar
        activeTab={activeTab}
        setActiveTab={(tab) => {
          setSelectedRunId(null);
          setActiveTab(tab);
        }}
        onOpenAuth={() => setIsAuthModalOpen(true)}
        isAuthenticated={isAuthenticated}
      />

      <main style={{ flex: 1 }}>
        {selectedRunId !== null ? (
          <RunDetailPage
            runId={selectedRunId}
            onBack={handleBackToDashboard}
            onCompare={(runId) => handleNavigateToCompare(runId)}
          />
        ) : activeTab === 'dashboard' ? (
          <DashboardPage
            onSelectRun={handleSelectRun}
            onCompareRuns={handleNavigateToCompare}
            onRequireAuth={() => setIsAuthModalOpen(true)}
            isAuthenticated={isAuthenticated}
          />
        ) : activeTab === 'compare' ? (
          <ComparisonPage
            initialRunIdA={compareRunA}
            initialRunIdB={compareRunB}
            onRequireAuth={() => setIsAuthModalOpen(true)}
            isAuthenticated={isAuthenticated}
          />
        ) : (
          /* MCA Placement Architecture & Diagnostics Guide */
          <div style={{ maxWidth: '1100px', margin: '0 auto', padding: '40px 24px', display: 'flex', flexDirection: 'column', gap: '32px' }}>
            <div>
              <span
                style={{
                  fontSize: '0.75rem',
                  fontWeight: 600,
                  textTransform: 'uppercase',
                  letterSpacing: '0.08em',
                  color: 'var(--accent-blue)',
                }}
              >
                Architecture & Telemetry Guide
              </span>
              <h1 style={{ fontSize: '2rem', fontWeight: 700, marginTop: '6px', color: 'var(--text-primary)' }}>
                PerfTrace Hardware Telemetry Engine
              </h1>
              <p style={{ color: 'var(--text-secondary)', marginTop: '8px', lineHeight: 1.6, fontSize: '0.9375rem' }}>
                PerfTrace ingests high-frequency CSV logs exported by HWiNFO64, aggregates telemetry into 10-second statistical windows,
                and applies conservative heuristics to classify compute bottlenecks and identify thermal throttling.
              </p>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: '20px' }}>
              <div className="glass-card" style={{ padding: '24px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '12px' }}>
                  <div style={{ color: 'var(--accent-blue)' }}>
                    <FileSpreadsheet size={24} />
                  </div>
                  <h3 style={{ fontSize: '1.125rem', fontWeight: 600 }}>1. Resilient CSV Parsing</h3>
                </div>
                <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                  HWiNFO64 exports vary in character encoding (UTF-8, UTF-16, BOM), column naming, and localization.
                  Our streaming OpenCSV pipeline detects BOMs, discards second unit header rows, maps canonical headers flexibly,
                  and parses missing readings strictly as <code>null</code> rather than defaulting to zero.
                </p>
              </div>

              <div className="glass-card" style={{ padding: '24px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '12px' }}>
                  <div style={{ color: 'var(--accent-purple)' }}>
                    <Layers size={24} />
                  </div>
                  <h3 style={{ fontSize: '1.125rem', fontWeight: 600 }}>2. 10s Window Aggregation</h3>
                </div>
                <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                  Sensor data arrives at sub-second intervals (typically 500ms–2000ms).
                  To eliminate micro-spikes and transient scheduling noise, readings are bucketed into ~10-second non-overlapping windows.
                  Time gaps exceeding 15s are marked as data gaps and retained as explicit unknown periods rather than interpolated across system sleep or pauses.
                </p>
              </div>

              <div className="glass-card" style={{ padding: '24px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '12px' }}>
                  <div style={{ color: 'var(--accent-orange)' }}>
                    <Cpu size={24} />
                  </div>
                  <h3 style={{ fontSize: '1.125rem', fontWeight: 600 }}>3. Explainable Classification</h3>
                </div>
                <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                  Windows are evaluated using transparent rules:
                  <br />• <strong>CPU-Bound:</strong> CPU total or busiest thread ≥ 90% while GPU &lt; 90%.
                  <br />• <strong>GPU-Bound:</strong> GPU load ≥ 95% with busiest CPU thread &lt; 85%.
                  <br />• <strong>Idle:</strong> CPU total &lt; 15% and GPU load &lt; 15%.
                  <br />• <strong>Balanced:</strong> Active workload, but no bottleneck rule fired and busiest-thread data exists.
                  <br />• <strong>Unknown:</strong> Missing load/thread evidence — never guessed as Balanced.
                </p>
              </div>

              <div className="glass-card" style={{ padding: '24px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '12px' }}>
                  <div style={{ color: 'var(--accent-red)' }}>
                    <ShieldAlert size={24} />
                  </div>
                  <h3 style={{ fontSize: '1.125rem', fontWeight: 600 }}>4. Hardware vs Heuristic Throttling</h3>
                </div>
                <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                  PerfTrace differentiates between <em>explicit hardware thermal throttle flags</em> (PROCHOT / register flag in HWiNFO)
                  and <em>heuristic throttling</em> (CPU ≥ 90°C or GPU ≥ 83°C, load ≥ 50%, and a &gt;12% clock
                  regression below the 90th-percentile non-idle baseline). High temperature alone never triggers a
                  throttling verdict.
                </p>
              </div>

              <div className="glass-card" style={{ padding: '24px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '12px' }}>
                  <div style={{ color: 'var(--accent-emerald)' }}>
                    <GitCompare size={24} />
                  </div>
                  <h3 style={{ fontSize: '1.125rem', fontWeight: 600 }}>5. Meaningful Run Comparison</h3>
                </div>
                <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                  Compares two diagnostic sessions with workload disparity checks (&gt;30% duration disparity, idle mismatch).
                  Enforces diagnostic humility: lower operating temperatures do not prove superior performance if clock frequencies dropped.
                </p>
              </div>

              <div className="glass-card" style={{ padding: '24px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '12px' }}>
                  <div style={{ color: 'var(--text-primary)' }}>
                    <Server size={24} />
                  </div>
                  <h3 style={{ fontSize: '1.125rem', fontWeight: 600 }}>6. Production Enterprise Architecture</h3>
                </div>
                <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                  Built with Spring Boot 4.1, Spring Security 7 stateless JWT authentication, PostgreSQL 16 with indexed
                  foreign keys, Flyway schema migrations, and batched JDBC inserts for streaming scalability.
                </p>
              </div>
            </div>

            <div className="glass-panel" style={{ padding: '24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div>
                <h4 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                  Ready to test with synthetic or real data?
                </h4>
                <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', marginTop: '4px' }}>
                  Switch to the Dashboard to upload your HWiNFO64 CSV log or load 1-click synthetic benchmark runs.
                </p>
              </div>
              <button
                onClick={() => setActiveTab('dashboard')}
                className="btn btn-primary"
                style={{ padding: '8px 20px', whiteSpace: 'nowrap' }}
              >
                Go to Dashboard
              </button>
            </div>
          </div>
        )}
      </main>

      <footer
        style={{
          borderTop: '1px solid var(--border-color)',
          padding: '24px',
          textAlign: 'center',
          color: 'var(--text-muted)',
          fontSize: '0.8125rem',
          background: 'rgba(10, 14, 23, 0.6)',
        }}
      >
        <p>
          PerfTrace Telemetry Diagnostics • Designed for Hardware Analysis & MCA Technical Placement Evaluation
        </p>
      </footer>

      <AuthModal
        isOpen={isAuthModalOpen}
        onClose={() => setIsAuthModalOpen(false)}
        onSuccess={() => setIsAuthenticated(true)}
      />
    </div>
  );
};
