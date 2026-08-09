import { useAtom, useSetAtom } from 'jotai';
import { useState } from 'react';
import { PageHeader } from '@/app/layout/PageHeader';
import { Button, EmptyState, Icon, MetaTag, Panel } from '@/ui';
import { selectedMockIdAtom, viewedScenarioAtom } from './atoms';
import { MockTree } from './components/MockTree';
import { NewMockDialog } from './components/NewMockDialog';
import { RequestProbe } from './components/RequestProbe';
import { ResponsePanel } from './components/ResponsePanel';
import { ScenarioPicker } from './components/ScenarioPicker';
import { useStoreReload } from '@/hooks/useStoreReload';
import { mockHandoffAtom } from '@/state/handoff';
import { useMockHandoff } from './hooks/useMockHandoff';
import { useMockTree } from './hooks/useMockTree';
import styles from './MocksPage.module.css';

/**
 * Files on the left; request above response on the right.
 *
 * Request and response are stacked rather than side by side because they are
 * not the same size of thing: a request is a handful of identifying fields,
 * a response can run to megabytes. Stacking gives both the full width, which
 * is what long XML lines actually need.
 */
export function MocksPage() {
  const [viewedScenario, setViewedScenario] = useAtom(viewedScenarioAtom);
  const [selectedId, setSelectedId] = useAtom(selectedMockIdAtom);
  const store = useStoreReload();
  const tree = useMockTree(viewedScenario);
  const setHandoff = useSetAtom(mockHandoffAtom);
  const [creating, setCreating] = useState(false);

  useMockHandoff();

  const fileCount =
    tree.status === 'ready' ? tree.data.reduce((total, node) => total + node.mockCount, 0) : null;

  return (
    <>
      <PageHeader
        title="Mocks"
        meta={
          <>
            <ScenarioPicker value={viewedScenario} onChange={setViewedScenario} />
            {fileCount !== null && <MetaTag label="Files" value={fileCount} />}
          </>
        }
        actions={
          <>
            {/* Re-reads the library from disk, not merely this page's copy of it: the server
                answers from an index, so a file edited outside the dashboard is invisible until
                it does. */}
            <Button
              emphasis="secondary"
              icon={<Icon name="reload" size={14} />}
              disabled={store.reloading}
              onClick={() => void store.reload()}
            >
              {store.reloading ? 'Reading…' : 'Reload'}
            </Button>
            <Button
              emphasis="accent"
              icon={<Icon name="create" size={14} />}
              disabled={tree.status !== 'ready'}
              onClick={() => setCreating(true)}
            >
              New Mock
            </Button>
          </>
        }
      />

      <div className={styles.layout}>
        <Panel title="Files" flush>
          {tree.status === 'loading' && <p className="pad-4 muted">Loading…</p>}
          {tree.status === 'error' && <p className="pad-4 muted">{tree.error.message}</p>}
          {tree.status === 'ready' &&
            (tree.data.length === 0 ? (
              <EmptyState title="No files in this scenario" />
            ) : (
              <MockTree nodes={tree.data} selectedId={selectedId} onSelect={setSelectedId} />
            ))}
        </Panel>

        <div className={styles.editor}>
          <Panel title="Request" flush>
            <RequestProbe />
          </Panel>

          <ResponsePanel mockId={selectedId} />
        </div>
      </div>

      {/* Mounted only while open, so its pickers start from what is loaded now. */}
      {creating && (
        <NewMockDialog
          onClose={() => setCreating(false)}
          scenarioId={viewedScenario}
          onDrafted={(draft) => {
            // The same handover the request log uses: one path into the editor for a file that
            // does not exist yet, however the author got there.
            setHandoff({ mockId: draft.mockId, scenarioId: viewedScenario, body: draft.body });
          }}
        />
      )}
    </>
  );
}
