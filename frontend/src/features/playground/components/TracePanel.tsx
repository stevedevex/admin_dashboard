import { useSetAtom } from 'jotai';
import { useNavigate } from 'react-router';
import { api, type RequestDetail } from '@/api';
import { useAsync } from '@/hooks/useAsync';
import { fileOf, sameFile, scenarioIn } from '@/lib/mockPath';
import { mockHandoffAtom } from '@/state/handoff';
import { Icon, Tag } from '@/ui';
import styles from './TracePanel.module.css';

/**
 * Why that was the answer.
 *
 * Read from the log entry the call was recorded under, not computed here and not asked for a second
 * time. The server resolved this request exactly once; fetching the trace by the id the response
 * carried means what is shown is that resolution, rather than a second one that agrees with it
 * today and might not tomorrow.
 *
 * Subordinate to the response on purpose. The dry run exists for the times the question is "why did
 * that not match" — this is here for the times the answer to "what do I get" turns out to be
 * surprising, which is a smaller share of the visits.
 */
export function TracePanel({ requestId }: { requestId: string }) {
  const state = useAsync<RequestDetail | null>(() => api.getRequest(requestId), [requestId]);
  const setHandoff = useSetAtom(mockHandoffAtom);
  const navigate = useNavigate();

  if (state.status !== 'ready' || !state.data) return null;

  const entry = state.data;
  const extracted = Object.entries(entry.extracted);

  const open = async (mockId: string) => {
    setHandoff({ mockId, scenarioId: scenarioIn(mockId) });
    await navigate('/mock-data/mocks');
  };

  return (
    <details className={styles.root}>
      <summary className={styles.summary}>
        <span>Why</span>
        {entry.matched ? (
          <span className={styles.matched}>{fileOf(entry.matched)}</span>
        ) : (
          <Tag tone="warn" icon={<Icon name="warn" size={11} />}>
            nothing matched
          </Tag>
        )}
        {entry.inherited && (
          <Tag tone="info" icon={<Icon name="scenarios" size={11} />}>
            inherited
          </Tag>
        )}
      </summary>

      <div className={styles.detail}>
        <div className={styles.line}>
          <span className={styles.label}>Identified by</span>
          {extracted.length === 0 ? (
            <span className={styles.muted}>nothing — no declared key was present</span>
          ) : (
            extracted.map(([name, value]) => (
              <span key={name} className={styles.mono}>
                {name}={String(value)}
              </span>
            ))
          )}
        </div>

        <div className={styles.line}>
          <span className={styles.label}>Files tried</span>
        </div>

        <ol className={styles.attempts}>
          {entry.attempted.map((path) => {
            const hit = entry.matched !== null && sameFile(path, entry.matched);
            return (
              <li key={path} className={hit ? styles.attemptHit : styles.attempt}>
                {hit && <Icon name="ok" size={12} />}
                <span>{path}</span>
              </li>
            );
          })}
        </ol>

        {entry.matched !== null && (
          <button type="button" className={styles.link} onClick={() => void open(entry.matched!)}>
            Open {fileOf(entry.matched)} in Mocks
          </button>
        )}
      </div>
    </details>
  );
}
