import { describe, expect, it } from 'vitest';
import type { MockSummary, Service } from '@/api';
import type { TreeNode } from './hooks/useMockTree';
import { countFiles, filterTree } from './filterTree';

const service = (id: string, name: string): Service => ({
  id,
  name,
  protocol: 'REST',
  endpoint: `/${id}`,
  format: 'json',
  hasSchema: true,
  keyFields: [],
  operations: [],
  mockCount: 0,
});

const mock = (fileName: string): MockSummary => ({
  id: `baseline/svc/op/${fileName}`,
  fileName,
  serviceId: 'svc',
  operationId: 'op',
  scenarioId: 'baseline',
  format: 'json',
  sizeBytes: 10,
  state: 'unchecked',
  completeness: null,
  inherited: false,
  modifiedAt: '2026-01-01T00:00:00Z',
});

const tree: TreeNode[] = [
  {
    service: service('petstore', 'Pet Store'),
    operations: [
      { id: 'showPetById', mocks: [mock('petid=1.json'), mock('petid=2.json')] },
      { id: 'listPets', mocks: [mock('_default.json')] },
    ],
    mockCount: 3,
  },
  {
    service: service('calculator', 'Calculator'),
    operations: [{ id: 'Add', mocks: [mock('inta=2&intb=3.xml')] }],
    mockCount: 1,
  },
];

describe('filterTree', () => {
  it('returns everything for an empty query', () => {
    expect(filterTree(tree, '')).toEqual(tree);
    expect(filterTree(tree, '   ')).toEqual(tree);
  });

  it('keeps a whole service when the service matches', () => {
    const found = filterTree(tree, 'calculator');

    expect(found).toHaveLength(1);
    expect(found[0]?.operations[0]?.mocks).toHaveLength(1);
  });

  it('matches a service by display name as well as by id', () => {
    expect(filterTree(tree, 'Pet Store')).toHaveLength(1);
  });

  it('keeps every file of a matching operation', () => {
    const found = filterTree(tree, 'showpetbyid');

    expect(found).toHaveLength(1);
    expect(found[0]?.operations).toHaveLength(1);
    expect(found[0]?.operations[0]?.mocks).toHaveLength(2);
  });

  it('keeps only the files that match when nothing above them does', () => {
    const found = filterTree(tree, 'petid=2');

    expect(found).toHaveLength(1);
    expect(found[0]?.operations[0]?.mocks.map((m) => m.fileName)).toEqual(['petid=2.json']);
  });

  it('ignores case', () => {
    expect(filterTree(tree, 'PETID=1')).toHaveLength(1);
  });

  it('drops branches with nothing left in them', () => {
    expect(filterTree(tree, 'nothing-matches-this')).toEqual([]);
  });

  it('recounts a filtered service, so the count describes what is on screen', () => {
    const found = filterTree(tree, 'petid=1');

    expect(found[0]?.mockCount).toBe(1);
  });

  it('does not mutate the tree it was given', () => {
    const before = JSON.stringify(tree);
    filterTree(tree, 'petid=1');

    expect(JSON.stringify(tree)).toBe(before);
  });
});

describe('countFiles', () => {
  it('totals what is on screen', () => {
    expect(countFiles(tree)).toBe(4);
    expect(countFiles(filterTree(tree, 'petid'))).toBe(2);
    expect(countFiles([])).toBe(0);
  });
});
