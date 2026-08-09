import { useAtom, useSetAtom } from 'jotai';
import { useMemo, useState } from 'react';
import { PageHeader } from '@/app/layout/PageHeader';
import { countFiles, filterTree } from './filterTree';
import { Button, EmptyState, Icon, MetaTag, Panel, TextInput } from '@/ui';
import { selectedMockIdAtom, viewedScenarioAtom } from './atoms';
import { MockTree } from './components/MockTree';
import { NewMockDialog } from './components/NewMockDialog';
import { RequestProbe } from './components/RequestProbe';
import { MockPayloadPanel } from './components/MockPayloadPanel';
import { ScenarioPicker } from './components/ScenarioPicker';
import { useStoreReload } from '@/hooks/useStoreReload';
import { mockHandoffAtom } from '@/state/handoff';
import { useMockHandoff } from './hooks/useMockHandoff';
import { useMockTree } from './hooks/useMockTree';
import styles from './MocksPage.module.css';

/**
 * Files on the left; the dry run above the payload on the right.
 *
 * Deliberately *not* labelled request and response. Those are the two halves of one exchange, and
 * stacking two panels under those names promises a pairing this page does not have: the dry run
 * answers about whatever request is typed into it, which is routinely a different operation
 * entirely from the file selected below. The relationship is one-way and only on demand — a
 * resolved file can be opened in the editor, and nothing flows back.
 *
 * Stacked rather than side by side because they are not the same size of thing: a request is a
 * handful of identifying fields, a payload can run to megabytes. Stacking gives both the full
 * width, which is what long XML lines actually need.
 */
export function MocksPage() {
  const [viewedScenario, setViewedScenario] = useAtom(viewedScenarioAtom);
  const [selectedId, setSelectedId] = useAtom(selectedMockIdAtom);
  const store = useStoreReload();
  const tree = useMockTree(viewedScenario);
  const setHandoff = useSetAtom(mockHandoffAtom);
  const [creating, setCreating] = useState(false);
  const [filter, setFilter] = useState('');

  useMockHandoff();

  // Filtering is a view, not a fetch: the library is already in memory, and asking the server on
  // every keystroke would make a local narrowing depend on a round trip. Derived from `tree`
  // rather than from an unwrapped array, which would be a new reference every render and defeat
  // the memo entirely.
  const shown = useMemo(
    () => (tree.status === 'ready' ? filterTree(tree.data, filter) : []),
    [tree, filter],
  );

  /**
   * The files of whichever operation is open, for the tab strip beside the payload.
   *
   * Read from the unfiltered tree on purpose: a filter narrows what is being *browsed*, and
   * hiding an operation's other files because the query happened to name only one of them would
   * make the tabs disagree with the file that is open.
   */
  const siblings = useMemo(() => {
    if (tree.status !== 'ready' || !selectedId) return [];

    const [, serviceId, operationId] = selectedId.split('/');
    return (
      tree.data
        .find((node) => node.service.id === serviceId)
        ?.operations.find((operation) => operation.id === operationId)?.mocks ?? []
    );
  }, [tree, selectedId]);

  const all = tree.status === 'ready' ? tree.data : [];
  const fileCount = tree.status === 'ready' ? countFiles(all) : null;
  const shownCount = countFiles(shown);
  const filtering = filter.trim() !== '';

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
        <Panel
          title="Files"
          flush
          actions={
            filtering && tree.status === 'ready' ? (
              <span className={styles.filterCount}>
                {shownCount} of {fileCount}
              </span>
            ) : null
          }
        >
          {/* A library of any size is unreadable as a fully expanded tree, and this one expands
              everything by default. Narrowing it is the cheapest thing that keeps it usable. */}
          <div className={styles.filter}>
            <TextInput
              mono
              value={filter}
              placeholder="Filter by service, operation or file name"
              onChange={(event) => setFilter(event.target.value)}
            />
          </div>

          {tree.status === 'loading' && <p className="pad-4 muted">Loading…</p>}
          {tree.status === 'error' && <p className="pad-4 muted">{tree.error.message}</p>}
          {tree.status === 'ready' &&
            (all.length === 0 ? (
              <EmptyState title="No files in this scenario" />
            ) : shown.length === 0 ? (
              <EmptyState title="Nothing matches">
                No service, operation or file name contains “{filter.trim()}”.
              </EmptyState>
            ) : (
              <MockTree
                nodes={shown}
                selectedId={selectedId}
                onSelect={setSelectedId}
                filtering={filtering}
              />
            ))}
        </Panel>

        <div className={styles.editor}>
          {/* No panel chrome: a title bar over a single toolbar would double its resting height,
              and the placeholder already says what the row is for. */}
          <RequestProbe />

          <MockPayloadPanel mockId={selectedId} siblings={siblings} onSelect={setSelectedId} />
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
