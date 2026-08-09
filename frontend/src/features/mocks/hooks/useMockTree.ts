import { useAtomValue } from 'jotai';
import { useMemo } from 'react';
import { api, type MockSummary, type Service } from '@/api';
import { useAsync, type AsyncState } from '@/hooks/useAsync';
import { storeNonceAtom } from '@/state/store';
import { reloadNonceAtom } from '../atoms';

export type OperationNode = {
  id: string;
  mocks: MockSummary[];
};

export type TreeNode = {
  service: Service;
  operations: OperationNode[];
  mockCount: number;
};

type Loaded = { services: Service[]; mocks: MockSummary[] };

/**
 * The file tree for one scenario: services that have at least one mock, each grouped by
 * operation.
 *
 * The operation level is not decoration — it is the storage hierarchy, and without it a service
 * with three operations shows three files called `_default.json` with no way to tell which
 * answers what.
 *
 * Grouping happens here rather than in the component so the tree stays a rendering concern, and
 * so this is testable without a DOM.
 */
export function useMockTree(scenarioId: string): AsyncState<TreeNode[]> {
  const nonce = useAtomValue(reloadNonceAtom);
  const storeNonce = useAtomValue(storeNonceAtom);

  const state = useAsync<Loaded>(async () => {
    const [services, mocks] = await Promise.all([api.listServices(), api.listMocks(scenarioId)]);
    return { services, mocks };
  }, [scenarioId, nonce, storeNonce]);

  return useMemo((): AsyncState<TreeNode[]> => {
    if (state.status !== 'ready') return state;

    const nodes = state.data.services
      .map((service) => {
        const mine = state.data.mocks.filter((mock) => mock.serviceId === service.id);

        const byOperation = new Map<string, MockSummary[]>();
        for (const mock of mine) {
          const existing = byOperation.get(mock.operationId);
          if (existing) existing.push(mock);
          else byOperation.set(mock.operationId, [mock]);
        }

        const operations = [...byOperation.entries()]
          .map(([id, mocks]) => ({
            id,
            mocks: mocks.sort((a, b) => a.fileName.localeCompare(b.fileName)),
          }))
          .sort((a, b) => a.id.localeCompare(b.id));

        return { service, operations, mockCount: mine.length };
      })
      .filter((node) => node.mockCount > 0);

    return { status: 'ready', data: nodes, error: null };
  }, [state]);
}
