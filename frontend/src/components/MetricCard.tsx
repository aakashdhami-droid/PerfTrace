import React from 'react';

interface Props {
  title: string;
  value: string | number | null | undefined;
  unit?: string;
  subtitle?: string;
  icon?: React.ReactNode;
  highlight?: 'blue' | 'emerald' | 'amber' | 'rose' | 'neutral';
}

export const MetricCard: React.FC<Props> = ({
  title,
  value,
  unit,
  subtitle,
  icon,
  highlight = 'neutral',
}) => {
  const getHighlightColor = () => {
    switch (highlight) {
      case 'blue': return 'var(--accent-blue)';
      case 'emerald': return 'var(--accent-emerald)';
      case 'amber': return 'var(--accent-amber)';
      case 'rose': return 'var(--accent-rose)';
      default: return 'var(--text-secondary)';
    }
  };

  const displayVal = (value === null || value === undefined || value === '') ? 'N/A' : value;

  return (
    <div className="glass-panel" style={{ padding: '16px 20px', position: 'relative', overflow: 'hidden' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '8px' }}>
        <span style={{ fontSize: '0.8125rem', fontWeight: 500, color: 'var(--text-secondary)' }}>
          {title}
        </span>
        {icon && (
          <div style={{ color: getHighlightColor(), opacity: 0.85 }}>
            {icon}
          </div>
        )}
      </div>

      <div style={{ display: 'flex', alignItems: 'baseline', gap: '6px' }}>
        <span style={{ fontSize: '1.75rem', fontWeight: 700, letterSpacing: '-0.02em', color: 'var(--text-primary)' }}>
          {displayVal}
        </span>
        {unit && displayVal !== 'N/A' && (
          <span style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-muted)' }}>
            {unit}
          </span>
        )}
      </div>

      {subtitle && (
        <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '4px' }}>
          {subtitle}
        </div>
      )}
    </div>
  );
};
