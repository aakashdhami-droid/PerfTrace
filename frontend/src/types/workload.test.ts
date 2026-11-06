import { describe, expect, it } from 'vitest';
import { WORKLOAD_TYPE_OPTIONS, formatWorkloadType } from './index';

describe('workload metadata presentation', () => {
  it('offers UNSPECIFIED as the explicit default rather than a guessed type', () => {
    expect(WORKLOAD_TYPE_OPTIONS[0]).toEqual({ value: 'UNSPECIFIED', label: 'Not specified' });
  });

  it('formats known workload types with human-readable labels', () => {
    expect(formatWorkloadType('GAMING')).toBe('Gaming');
    expect(formatWorkloadType('CPU_RENDER')).toBe('CPU render / compile');
    expect(formatWorkloadType('SYNTHETIC_BENCHMARK')).toBe('Synthetic benchmark');
  });

  it('shows missing or unspecified metadata as not specified', () => {
    expect(formatWorkloadType(undefined)).toBe('Not specified');
    expect(formatWorkloadType(null)).toBe('Not specified');
    expect(formatWorkloadType('UNSPECIFIED')).toBe('Not specified');
  });
});
