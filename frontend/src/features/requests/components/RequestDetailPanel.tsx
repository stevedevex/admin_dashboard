import { useSetAtom } from 'jotai';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { api, type RequestDetail } from '@/api';
import { useAsync } from '@/hooks/useAsync';
import { mockHandoffAtom } from '@/state/handoff';
import { Button, CodeEditor, EmptyState, Icon, MetaTag, Panel, Tag } from '@/ui';
import { languageOf, prettify } from '../prettify';
import styles from './RequestDetailPanel.module.css';

/**
 * Turns a recorded call into the mock it was asking for.
 *
 * The draft is fetched here and handed to the mocks page rather than written: nothing is created
 * until an author fills the payload in and saves it, because a mock created empty would serve a
 * well-formed nothing — the upstream behaviour this sandbox exists to eliminate.
 */
function useCreateMock() {
  const navigate = useNavigate();
  const setHandoff = useSetAtom(mockHandoffAtom);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const create = async (entry: RequestDetail) => {
    setBusy(true);
    setError(null);
    try {
      const draft = await api.draftFromRequest(entry.id);

      // Already written — this call resolves to it, or would once it is reached. Opening it with a
      // skeleton would show an author their own mock apparently replaced by an empty one.
      setHandoff(
        draft.exists
          ? { mockId: draft.mockId, scenarioId: draft.scenarioId }
          : {
              mockId: draft.mockId,
              scenarioId: draft.scenarioId,
              body: draft.skeleton ?? '',
              ...(draft.requestBody === null ? {} : { request: draft.requestBody }),
            },
      );

      await navigate('/mock-data/mocks');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setBusy(false);
    }
  };

  const open = async (entry: RequestDetail) => {
    if (entry.matched === null) return;
    const [scenarioId = ''] = entry.matched.split('/');
    setHandoff({ mockId: entry.matched, scenarioId });
    await navigate('/mock-data/mocks');
  };

  return { create, open, busy, error };
}

/** One call in full: what identified it, what was tried, and both payloads. */
export function RequestDetailPanel({ entryId }: { entryId: string | null }) {
  return (
    <Panel title="Call" flush>
      {entryId ? (
        <Loaded entryId={entryId} />
      ) : (
        <EmptyState title="No call selected">
          Select a call to see the keys it carried and every file the resolver tried.
        </EmptyState>
      )}
    </Panel>
  );
}

function Loaded({ entryId }: { entryId: string }) {
  const state = useAsync<RequestDetail | null>(() => api.getRequest(entryId), [entryId]);
  const mock = useCreateMock();

  if (state.status === 'loading') return <p className="pad-4 muted">Loading…</p>;
  if (state.status === 'error') return <p className="pad-4 muted">{state.error.message}</p>;

  if (!state.data) {
    return (
      <EmptyState title="Call is no longer retained">
        The log is bounded and this entry has aged out.
      </EmptyState>
    );
  }

  const entry = state.data;
  const extracted = Object.entries(entry.extracted).map(
    ([name, value]) => [name, String(value)] as const,
  );

  return (
    <div className={styles.wrap}>
      <div className={styles.meta}>
        <MetaTag label="Service" value={entry.serviceId ?? '—'} />
        <MetaTag label="Operation" value={entry.operationId ?? '—'} />
        <MetaTag label="Scenario" value={entry.scenarioId ?? '—'} />
        <MetaTag label="Status" value={entry.status} />
        <MetaTag label="Took" value={`${entry.tookMillis} ms`} />
        {entry.bodiesTruncated && (
          <Tag tone="warn" icon={<Icon name="warn" size={11} />}>
            bodies truncated
          </Tag>
        )}

        <div className={styles.actions}>
          {mock.error && <span className={styles.error}>{mock.error}</span>}
          {/* Offered whatever happened, because falling through to an operation's default is the
              same request as a miss: this call did not get an answer written for it. The draft
              names the specific file that would serve it. */}
          <Button
            emphasis="accent"
            icon={<Icon name="create" size={14} />}
            disabled={mock.busy || entry.operationId === null}
            onClick={() => void mock.create(entry)}
          >
            {mock.busy ? 'Drafting…' : 'Mock this call'}
          </Button>

          {/* What actually answered, which on a fall-through is not the file above. */}
          {entry.matched !== null && (
            <Button
              emphasis="secondary"
              icon={<Icon name="mocks" size={14} />}
              onClick={() => void mock.open(entry)}
            >
              Open {entry.matched.split('/').pop()}
            </Button>
          )}
        </div>
      </div>

      <div className={styles.trace}>
        <section>
          <h3 className={styles.heading}>Keys extracted</h3>
          {extracted.length === 0 ? (
            <p className={styles.none}>
              None. Either the request carried nothing the operation declares, or it was rejected
              before resolution.
            </p>
          ) : (
            <dl className={styles.keys}>
              {extracted.map(([name, value]) => (
                <div key={name} className={styles.key}>
                  <dt>{name}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
          )}
        </section>

        <section>
          <h3 className={styles.heading}>
            Files tried
            {entry.matched === null && (
              <Tag tone="warn" icon={<Icon name="warn" size={11} />}>
                none matched
              </Tag>
            )}
          </h3>

          {entry.attempted.length === 0 ? (
            <p className={styles.none}>Nothing was tried — the request never reached resolution.</p>
          ) : (
            <ol className={styles.attempts}>
              {entry.attempted.map((path: string) => {
                // The trace lists store paths; `matched` is a mock id. Comparing on the tail is
                // what identifies the winner without either side inventing the other's form.
                const hit = entry.matched !== null && sameFile(path, entry.matched);
                return (
                  <li key={path} className={hit ? styles.attemptHit : styles.attempt}>
                    {hit && <Icon name="ok" size={12} />}
                    <span>{path}</span>
                  </li>
                );
              })}
            </ol>
          )}

          {/* A miss is the case this page exists for, so it gets the instruction, not just a tag. */}
          {entry.matched === null && entry.attempted.length > 0 && (
            <p className={styles.hint}>
              Create one of these files, or check the keys the operation declares.
            </p>
          )}
        </section>
      </div>

      <section className={styles.request}>
        <h3 className={styles.heading}>
          Request
          <span className={styles.answer}>
            {/* What came back, in one line. On a hit the response *is* the mock, and it is one
                click away on the Mocks page — repeating a payload here would bury the request,
                which is the half nobody can read anywhere else. */}
            answered {entry.status} {reasonFor(entry.status)}
            {entry.matched !== null && <> from {entry.matched.split('/').pop()}</>}
          </span>
        </h3>

        {entry.requestBody === null || entry.requestBody === '' ? (
          <p className={styles.none}>
            No body. A GET carries its identity in the path and query, not in a payload.
          </p>
        ) : (
          <CodeEditor
            value={prettify(entry.requestBody)}
            language={languageOf(entry.requestBody)}
            readOnly
          />
        )}

        {/* When nothing answered, whatever the sandbox said back is the explanation — short, and
            the only place it appears. When a mock answered, this is empty and stays out of the way. */}
        {entry.matched === null && entry.responseBody && (
          <p className={styles.rejected}>{entry.responseBody}</p>
        )}
      </section>
    </div>
  );
}

/** The few statuses this sandbox actually answers with, named so the number reads as an outcome. */
function reasonFor(status: number): string {
  const reasons: Record<number, string> = {
    200: 'OK',
    201: 'Created',
    204: 'No Content',
    400: 'Bad Request',
    404: 'Not Found',
    405: 'Method Not Allowed',
    500: 'Server Error',
    501: 'Not Implemented',
    503: 'Service Unavailable',
  };
  return reasons[status] ?? '';
}

/**
 * Whether an attempted store path and a matched mock id name the same file.
 *
 * The two are written differently — `scenarios/baseline/petstore/showPetById/petid=1` against
 * `baseline/petstore/showPetById/petid=1.json` — because one is a candidate the resolver formed
 * and the other is an address. Extension included on one side only, since resolution matches on
 * the stem: any sibling with the right name is the mock.
 */
function sameFile(attempted: string, matched: string): boolean {
  const stem = (path: string) => {
    const file = path.split('/').pop() ?? path;
    const dot = file.lastIndexOf('.');
    return dot > 0 ? file.slice(0, dot) : file;
  };

  const scenarioOf = (path: string) => path.replace(/^scenarios\//, '').split('/')[0];

  return stem(attempted) === stem(matched) && scenarioOf(attempted) === scenarioOf(matched);
}
