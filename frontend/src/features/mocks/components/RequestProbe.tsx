import { useAtom, useAtomValue, useSetAtom } from 'jotai';
import { useState } from 'react';
import { api, type ResolutionTrace } from '@/api';
import { mockHandoffAtom } from '@/state/handoff';
import { Button, CodeEditor, Icon, Tag } from '@/ui';
import { requestProbeAtom, selectedMockIdAtom, viewedScenarioAtom } from '../atoms';
import styles from './RequestProbe.module.css';

/**
 * The dry run: describe a request, find out what it would get.
 *
 * Nothing is sent and nothing is stored. It calls the server's own resolution pipeline rather than
 * reimplementing the matching rules here — a dry run answering from its own copy of them would
 * agree with the server right up until the two drifted, which is exactly when someone would be
 * using it to find out why a request did not match.
 */
export function RequestProbe() {
  const [body, setBody] = useAtom(requestProbeAtom);
  const scenarioId = useAtomValue(viewedScenarioAtom);
  const setSelected = useSetAtom(selectedMockIdAtom);
  const setHandoff = useSetAtom(mockHandoffAtom);

  const [method, setMethod] = useState('GET');
  const [path, setPath] = useState('');
  const [trace, setTrace] = useState<ResolutionTrace | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);

  // A pasted envelope identifies its own operation, so a method and path are neither needed nor
  // meaningful. Read from the text rather than asked for: the shape is already unambiguous.
  const soap = body.trimStart().startsWith('<');
  const runnable = soap ? body.trim() !== '' : path.trim() !== '';

  const run = async () => {
    setRunning(true);
    setError(null);
    setTrace(null);
    try {
      setTrace(
        await api.resolve(
          soap ? { scenarioId, body } : { scenarioId, method, path, ...(body ? { body } : {}) },
        ),
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setRunning(false);
    }
  };

  const open = (mockId: string) => {
    const [found = scenarioId] = mockId.split('/');
    setHandoff({ mockId, scenarioId: found });
    setSelected(mockId);
  };

  return (
    <div className={styles.wrap}>
      <div className={styles.controls}>
        {soap ? (
          <Tag tone="info">SOAP envelope — the body names the operation</Tag>
        ) : (
          <>
            <select
              className={styles.method}
              value={method}
              aria-label="Method"
              onChange={(event) => setMethod(event.target.value)}
            >
              {['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((verb) => (
                <option key={verb} value={verb}>
                  {verb}
                </option>
              ))}
            </select>
            <input
              className={styles.path}
              value={path}
              aria-label="Path"
              placeholder="/petstore/v1/pets/1"
              onChange={(event) => setPath(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && runnable && !running) void run();
              }}
            />
          </>
        )}

        <Button
          emphasis="secondary"
          icon={<Icon name="validate" size={14} />}
          disabled={!runnable || running}
          onClick={() => void run()}
        >
          {running ? 'Resolving…' : 'Resolve'}
        </Button>
      </div>

      <div className={styles.editor}>
        <CodeEditor
          value={body}
          language={soap ? 'xml' : 'json'}
          placeholder="Paste a SOAP envelope, or a JSON body for a REST request…"
          onChange={(next) => {
            setBody(next);
            // The trace described the previous text; leaving it up answers a question no longer
            // being asked.
            setTrace(null);
          }}
        />
      </div>

      {error && <p className={styles.error}>{error}</p>}

      {trace && (
        <div className={styles.trace}>
          <div className={styles.line}>
            <span className={styles.label}>Resolves to</span>
            <span className={styles.mono}>
              {trace.serviceId}/{trace.operationId}
            </span>
            <span className={styles.label}>in</span>
            <span className={styles.mono}>{trace.scenarioId}</span>
          </div>

          <div className={styles.line}>
            <span className={styles.label}>Identified by</span>
            {Object.entries(trace.extracted).length === 0 ? (
              <span className={styles.muted}>nothing — no declared key was present</span>
            ) : (
              Object.entries(trace.extracted).map(([name, value]) => (
                <span key={name} className={styles.mono}>
                  {name}={String(value)}
                </span>
              ))
            )}
          </div>

          {/* Why the endpoint exists: seeing a correlation id listed here turns "it did not match"
              into "of course, that is not what identifies it". */}
          {trace.discarded.length > 0 && (
            <div className={styles.line}>
              <span className={styles.label}>Ignored</span>
              <span className={styles.muted}>{trace.discarded.join(', ')}</span>
            </div>
          )}

          <div className={styles.line}>
            <span className={styles.label}>Answered by</span>
            {trace.matched === null ? (
              <Tag tone="warn" icon={<Icon name="warn" size={11} />}>
                nothing — this request would fail
              </Tag>
            ) : (
              <>
                <button type="button" className={styles.link} onClick={() => open(trace.matched!)}>
                  {trace.matched}
                </button>
                {trace.inherited && (
                  <Tag tone="info" icon={<Icon name="scenarios" size={11} />}>
                    inherited
                  </Tag>
                )}
              </>
            )}
          </div>

          <ol className={styles.attempts}>
            {trace.attempted.map((candidate) => (
              <li key={candidate} className={styles.attempt}>
                {candidate}
              </li>
            ))}
          </ol>
        </div>
      )}
    </div>
  );
}
