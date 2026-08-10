import { describe, expect, it } from 'vitest';
import type { MockState, MockSummary } from '@/api';
import { worstState } from './operationState';

const mock = (state: MockState): MockSummary => ({
  id: `baseline/svc/op/${state}.json`,
  fileName: `${state}.json`,
  serviceId: 'svc',
  operationId: 'op',
  scenarioId: 'baseline',
  format: 'json',
  sizeBytes: 1,
  state,
  completeness: null,
  inherited: false,
  reachable: true,
  unreachableReason: null,
  modifiedAt: '2026-01-01T00:00:00Z',
});

describe('worstState', () => {
  it('reports valid only when every file is valid', () => {
    expect(worstState([mock('valid'), mock('valid')])).toBe('valid');
  });

  it('lets one invalid file speak for the operation', () => {
    expect(worstState([mock('valid'), mock('invalid'), mock('valid')])).toBe('invalid');
  });

  it('ranks invalid above incomplete', () => {
    expect(worstState([mock('incomplete'), mock('invalid')])).toBe('invalid');
  });

  it('ranks incomplete above unchecked', () => {
    expect(worstState([mock('unchecked'), mock('incomplete')])).toBe('incomplete');
  });

  it('ranks unchecked above valid, because unknown is not clean', () => {
    expect(worstState([mock('valid'), mock('unchecked')])).toBe('unchecked');
  });

  it('calls an empty operation unchecked rather than valid', () => {
    expect(worstState([])).toBe('unchecked');
  });
});
