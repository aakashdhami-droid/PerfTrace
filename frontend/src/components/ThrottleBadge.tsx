import React from 'react';
import { ThrottleEvidence } from '../types';
import { Flame, AlertTriangle, ShieldCheck, HelpCircle } from 'lucide-react';

interface Props {
  evidence: ThrottleEvidence;
  showIcon?: boolean;
}

export const ThrottleBadge: React.FC<Props> = ({ evidence, showIcon = true }) => {
  switch (evidence) {
    case 'HARDWARE_FLAG':
      return (
        <span className="badge badge-throttle" title="Confirmed: Explicit hardware register flagged thermal throttling">
          {showIcon && <Flame size={12} />}
          Hardware Flag Throttle
        </span>
      );
    case 'HEURISTIC_DETECTED':
      return (
        <span className="badge badge-throttle" style={{ borderStyle: 'dashed' }} title="Inferred: High temperature combined with active load and clock speed depression">
          {showIcon && <AlertTriangle size={12} />}
          Heuristic Throttle
        </span>
      );
    case 'INSUFFICIENT_DATA':
      return (
        <span className="badge badge-unknown" title="Temperature or clock sensors missing from log">
          {showIcon && <HelpCircle size={12} />}
          Thermal Unknown
        </span>
      );
    case 'NONE':
    default:
      return (
        <span className="badge badge-balanced" title="No thermal throttling evidence detected">
          {showIcon && <ShieldCheck size={12} />}
          No Throttling
        </span>
      );
  }
};
