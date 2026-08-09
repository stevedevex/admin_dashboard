import { useAtomValue, useSetAtom } from 'jotai';
import { useState } from 'react';
import { api, type MockDataSummary, type Scenario } from '@/api';
import { PageHeader } from '@/app/layout/PageHeader';
import { useAsync } from '@/hooks/useAsync';
import { storeNonceAtom } from '@/state/store';
import { Button, Panel, Tag } from '@/ui';
import { NewScenarioDialog } from './components/NewScenarioDialog';
import { ScenarioTable } from './components/ScenarioTable';
import styles from './ScenariosPage.module.css';

/**
 * The scenarios the library holds, and which one the sandbox serves.
 *
 * Serving is the only thing on this page that changes what the application under test receives.
 * It lives here rather than beside the browse picker on the Mocks page precisely so it cannot be
 * changed by accident while looking around: on a shared instance it affects every caller at once.
 */
export function ScenariosPage() {
  const storeNonce = useAtomValue(storeNonceAtom);
  const bumpStore = useSetAtom(storeNonceAtom);

  const scenarios = useAsync<Scenario[]>(() => api.listScenarios(), [storeNonce]);
  const summary = useAsync<MockDataSummary>(() => api.getMockDataSummary(), [storeNonce]);

  const [switching, setSwitching] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const activeId = summary.status === 'ready' ? summary.data.activeScenarioId : null;

  const serve = async (scenarioId: string) => {
    setSwitching(scenarioId);
    setError(null);
    try {
      await api.setActiveScenario(scenarioId);
      // Every page's numbers move with this: a service's mock count is counted in the served
      // scenario, and the data plane now answers from it.
      bumpStore((n) => n + 1);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSwitching(null);
    }
  };

  return (
    <>
      <PageHeader
        title="Scenarios"
        meta={
          <>
            {scenarios.status === 'ready' && <Tag tone="info">{scenarios.data.length} defined</Tag>}
            {activeId && <Tag tone="ok">serving {activeId}</Tag>}
          </>
        }
        actions={
          <Button emphasis="accent" onClick={() => setCreating(true)}>
            New Scenario
          </Button>
        }
      />

      {/* Stated plainly, because the action in this table is the one control on any page here
          with consequences beyond the screen. */}
      <p className={styles.note}>
        The served scenario is what every caller of the sandbox receives. Browsing a different one
        on the Mocks page changes nothing for them.
      </p>

      {error && <p className={styles.error}>{error}</p>}

      <Panel title="Scenarios" flush>
        {scenarios.status === 'loading' && <p className="pad-4 muted">Loading…</p>}
        {scenarios.status === 'error' && <p className="pad-4 muted">{scenarios.error.message}</p>}
        {scenarios.status === 'ready' && (
          <ScenarioTable
            scenarios={scenarios.data}
            activeId={activeId}
            switching={switching}
            onServe={(id) => void serve(id)}
          />
        )}
      </Panel>

      {/* Mounted only while open: the form's defaults must be read when it opens, not when the
          page first rendered and the server had not yet said what it serves. */}
      {creating && (
        <NewScenarioDialog
          onClose={() => setCreating(false)}
          scenarios={scenarios.status === 'ready' ? scenarios.data : []}
          defaultParent={activeId}
          onCreated={() => {
            // Refetch rather than splice the new row in: mock counts and coverage are the
            // server's to state. Deliberately not served — that stays a separate, explicit act.
            bumpStore((n) => n + 1);
          }}
        />
      )}
    </>
  );
}
