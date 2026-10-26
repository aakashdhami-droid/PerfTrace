import React from 'react';
import { Activity, BarChart2, GitCompare, LogOut, User as UserIcon } from 'lucide-react';
import { api } from '../services/api';

interface Props {
  activeTab: 'dashboard' | 'compare' | 'guide';
  setActiveTab: (tab: 'dashboard' | 'compare' | 'guide') => void;
  onOpenAuth: () => void;
  isAuthenticated: boolean;
}

export const Navbar: React.FC<Props> = ({
  activeTab,
  setActiveTab,
  onOpenAuth,
  isAuthenticated,
}) => {
  const user = api.getCurrentUser();

  return (
    <header
      style={{
        borderBottom: '1px solid var(--border-color)',
        background: 'rgba(10, 14, 23, 0.8)',
        backdropFilter: 'blur(16px)',
        position: 'sticky',
        top: 0,
        zIndex: 50,
      }}
    >
      <div
        style={{
          maxWidth: '1380px',
          margin: '0 auto',
          padding: '0 24px',
          height: '64px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
        }}
      >
        {/* Brand */}
        <div
          onClick={() => setActiveTab('dashboard')}
          style={{ display: 'flex', alignItems: 'center', gap: '10px', cursor: 'pointer' }}
        >
          <div
            style={{
              width: '36px',
              height: '36px',
              borderRadius: 'var(--radius-md)',
              background: 'linear-gradient(135deg, var(--accent-blue), var(--accent-purple))',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#ffffff',
              boxShadow: 'var(--shadow-glow)',
            }}
          >
            <Activity size={20} />
          </div>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <span style={{ fontSize: '1.125rem', fontWeight: 700, letterSpacing: '-0.02em', color: '#ffffff' }}>
                PerfTrace
              </span>
              <span
                style={{
                  fontSize: '0.6875rem',
                  fontWeight: 600,
                  padding: '1px 6px',
                  borderRadius: '4px',
                  background: 'rgba(59, 130, 246, 0.15)',
                  color: 'var(--accent-blue)',
                  border: '1px solid rgba(59, 130, 246, 0.3)',
                }}
              >
                Telemetry Diagnostics
              </span>
            </div>
            <span style={{ fontSize: '0.6875rem', color: 'var(--text-muted)' }}>
              HWiNFO64 Hardware Analysis Aid
            </span>
          </div>
        </div>

        {/* Navigation Tabs */}
        <nav style={{ display: 'flex', gap: '6px' }}>
          <button
            onClick={() => setActiveTab('dashboard')}
            className={`btn ${activeTab === 'dashboard' ? 'btn-primary' : 'btn-secondary'}`}
            style={{ padding: '6px 14px', fontSize: '0.8125rem' }}
          >
            <BarChart2 size={15} />
            Dashboard
          </button>

          <button
            onClick={() => setActiveTab('compare')}
            className={`btn ${activeTab === 'compare' ? 'btn-primary' : 'btn-secondary'}`}
            style={{ padding: '6px 14px', fontSize: '0.8125rem' }}
          >
            <GitCompare size={15} />
            Run Comparison
          </button>
        </nav>

        {/* User / Auth State */}
        <div>
          {isAuthenticated && user ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <div
                  style={{
                    width: '32px',
                    height: '32px',
                    borderRadius: '50%',
                    background: 'rgba(255, 255, 255, 0.08)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    color: 'var(--accent-blue)',
                    border: '1px solid var(--border-color)',
                  }}
                >
                  <UserIcon size={16} />
                </div>
                <div style={{ lineHeight: '1.2' }}>
                  <div style={{ fontSize: '0.8125rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                    {user.username}
                  </div>
                  <div style={{ fontSize: '0.6875rem', color: 'var(--text-muted)' }}>
                    {user.email}
                  </div>
                </div>
              </div>

              <button
                onClick={() => api.logout()}
                className="btn btn-secondary"
                style={{ padding: '6px 10px', fontSize: '0.75rem' }}
                title="Log out"
              >
                <LogOut size={14} />
              </button>
            </div>
          ) : (
            <button
              onClick={onOpenAuth}
              className="btn btn-primary"
              style={{ padding: '7px 16px', fontSize: '0.8125rem' }}
            >
              Sign In / Register
            </button>
          )}
        </div>
      </div>
    </header>
  );
};
