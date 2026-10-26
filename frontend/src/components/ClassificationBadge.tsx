import React from 'react';
import { Classification } from '../types';
import { Cpu, Zap, Scale, Moon, HelpCircle } from 'lucide-react';

interface Props {
  classification: Classification;
  showIcon?: boolean;
}

export const ClassificationBadge: React.FC<Props> = ({ classification, showIcon = true }) => {
  switch (classification) {
    case 'CPU_BOUND':
      return (
        <span className="badge badge-cpu" title="CPU execution time constrained throughput while GPU had headroom">
          {showIcon && <Cpu size={12} />}
          CPU-Limited
        </span>
      );
    case 'GPU_BOUND':
      return (
        <span className="badge badge-gpu" title="Graphics processor was saturated (>= 95% load)">
          {showIcon && <Zap size={12} />}
          GPU-Limited
        </span>
      );
    case 'BALANCED':
      return (
        <span className="badge badge-balanced" title="Active workload with no single bottleneck threshold crossed (does not prove optimal)">
          {showIcon && <Scale size={12} />}
          Balanced / Other
        </span>
      );
    case 'IDLE':
      return (
        <span className="badge badge-idle" title="Both CPU and GPU below 15% utilization">
          {showIcon && <Moon size={12} />}
          Idle
        </span>
      );
    case 'UNKNOWN':
    default:
      return (
        <span className="badge badge-unknown" title="Insufficient sensor readings to classify">
          {showIcon && <HelpCircle size={12} />}
          Unknown Data
        </span>
      );
  }
};
