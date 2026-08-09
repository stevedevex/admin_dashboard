import { useState } from 'react';
import { PageHeader } from '@/app/layout/PageHeader';
import { Button, EmptyState, Icon, MetaTag, Panel, Tag } from '@/ui';
import { RequestDetailPanel } from './components/RequestDetailPanel';
import { RequestTable } from './components/RequestTable';
import { useRequestLog } from './hooks/useRequestLog';
import styles from './RequestsPage.module.css';

/**
 * What the application under test actually called.
 *
 * The list is the log; the panel below is one call in full. Misses are the reason this page
 * exists — a mock that did not match names the file that would have answered, which turns "why is
 * my mock being ignored" into a file to create.
 */
export function RequestsPage() {
  const log = useRequestLog();
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const misses = log.entries.filter((entry) => entry.matched === null).length;

  return (
    <>
      <PageHeader
        title="Requests"
        meta={
          <>
            <MetaTag label="Calls" value={log.entries.length} />
            {misses > 0 && (
              <Tag tone="warn" icon={<Icon name="warn" size={11} />}>
                {misses} unmatched
              </Tag>
            )}
            {log.live ? (
              <Tag tone="ok">live</Tag>
            ) : (
              <Tag tone="neutral">paused</Tag>
            )}
          </>
        }
        actions={
          <>
            <Button emphasis="secondary" onClick={() => log.setLive(!log.live)}>
              {log.live ? 'Pause' : 'Resume'}
            </Button>
            <Button emphasis="muted" onClick={log.clear} disabled={log.entries.length === 0}>
              Clear view
            </Button>
          </>
        }
      />

      {log.sampled && (
        <div className={styles.notice}>
          <Tag tone="warn" icon={<Icon name="warn" size={11} />}>
            sampled
          </Tag>
          <span>
            The server&rsquo;s buffer wrapped past this view, so some calls were never shown. Raise{' '}
            <code>tao.sandbox.requestLog.capacity</code> to retain more.
          </span>
        </div>
      )}

      {log.error && (
        <div className={styles.notice}>
          <Tag tone="error" icon={<Icon name="error" size={11} />}>
            not polling
          </Tag>
          <span>{log.error.message}</span>
        </div>
      )}

      <div className={styles.layout}>
        <Panel title="Calls" flush>
          {log.entries.length === 0 ? (
            <EmptyState title="Nothing called yet">
              Point an application at the sandbox and its calls appear here, matched or not.
            </EmptyState>
          ) : (
            <RequestTable entries={log.entries} selectedId={selectedId} onSelect={setSelectedId} />
          )}
        </Panel>

        <RequestDetailPanel entryId={selectedId} />
      </div>
    </>
  );
}
