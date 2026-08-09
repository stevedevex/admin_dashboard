import { describe, expect, it } from 'vitest';
import { formatBytes } from './format';

describe('formatBytes', () => {
  it('shows whole bytes without decimals', () => {
    expect(formatBytes(918)).toBe('918 B');
  });

  it('steps up through units', () => {
    expect(formatBytes(4_918)).toBe('4.8 kB');
    expect(formatBytes(1_284_330)).toBe('1.2 MB');
  });

  it('drops decimals once the value is large enough not to need them', () => {
    expect(formatBytes(862_004)).toBe('842 kB');
  });

  it('handles zero', () => {
    expect(formatBytes(0)).toBe('0 B');
  });
});
