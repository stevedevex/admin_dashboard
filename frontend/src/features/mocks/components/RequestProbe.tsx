import { useAtom, useAtomValue, useSetAtom } from 'jotai';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { api, type ResolutionTrace } from '@/api';
import { mockHandoffAtom, playgroundHandoffAtom } from '@/state/handoff';
import { Button, CodeEditor, Icon, Tag } from '@/ui';
import { requestProbeAtom, viewedScenarioAtom } from '../atoms';
import styles from './RequestProbe.module.css';

/**
 * The dry run: describe a request, find out what it would get.
 *
 * Nothing is sent and nothing is stored. It calls the server's own resolution pipeline rather than
 * reimplementing the matching rules here — a dry run answering from its own copy of them would
 * agree with the server right up until the two drifted, which is exactly when someone would be
 * using it to find out why a request did not match.
 *
 * <h3>Why it is one row until it is not</h3>
 *
 * This is a diagnostic, reached for occasionally, sharing a column with the payload editor, which
 * is the actual work surface. Holding a permanent multi-line box open for it cost the editor a
 * third of the screen to display nothing most of the time. So it rests as a single toolbar and
 * grows only when the request being described genuinely needs more: an envelope pasted in, or a
 * body a declared key is read from. The editor is lazily loaded, so a probe never opened does not
 * even fetch it.
 */
export function RequestProbe() {
  const [body, setBody] = useAtom(requestProbeAtom);
  const scenarioId = useAtomValue(viewedScenarioAtom);
  const setHandoff = useSetAtom(mockHandoffAtom);
  const setPlayground = useSetAtom(playgroundHandoffAtom);
  const navigate = useNavigate();

  const [method, setMethod] = useState('GET');
  const [path, setPath] = useState('');
  const [trace, setTrace] = useState<ResolutionTrace | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [wantsBody, setWantsBody] = useState(false);
  const [explaining, setExplaining] = useState(false);

  /**
   * A pasted envelope identifies its own operation, so a method and path are neither needed nor
   * meaningful. Read from the text rather than asked for: the shape is already unambiguous.
   */
  const soap = body.trimStart().startsWith('<');
  const showBody = soap || wantsBody;
  const runnable = soap ? body.trim() !== '' : path.trim() !== '';

  /**
   * Paste an envelope anywhere and it lands where envelopes go.
   *
   * The path field is where a cursor already is, and an envelope pasted into it is unambiguous —
   * so it is moved rather than rejected. Making somebody first find the right box to paste into is
   * exactly the friction that had this panel holding one open at all times.
   */
  const typePath = (next: string) => {
    if (next.trimStart().startsWith('<')) {
      setBody(next);
      setPath('');
      setTrace(null);
      return;
    }
    setPath(next);
  };

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

  /**
   * The same request, actually sent.
   *
   * Offered because the two questions arrive in that order far more often than not: somebody
   * establishes that a request reaches the file they expected, and immediately wants to know what
   * that file turns into on the wire. Handed over rather than answered here — this panel is a
   * diagnostic sharing a column with the payload editor, and a response belongs where there is room
   * to read one.
   */
  const handOver = async () => {
    setPlayground({
      ...(soap ? { body } : { method, path, ...(body ? { body } : {}) }),
      scenarioId,
      // Not sent on arrival: this is a thought in progress, not a decision somebody has made.
      send: false,
    });
    await navigate('/mock-data/playground');
  };

  const open = (mockId: string) => {
    const [found = scenarioId] = mockId.split('/');
    // Selecting the file — which means putting it in the URL, see `../url.ts` — is
    // `useMockHandoff`'s job once this lands; it also has to switch the browsed scenario when
    // the trace resolved somewhere other than here, which a bare navigate would not do.
    setHandoff({ mockId, scenarioId: found });
    // The trace has done its job once the file it named is open; keeping it expanded holds the
    // editor down to re-state what the reader has just acted on.
    setTrace(null);
  };

  const clear = () => {
    setBody('');
    setPath('');
    setTrace(null);
    setError(null);
    setWantsBody(false);
  };

  return (
    <div className={styles.root}>
      <div className={styles.wrap}>
        <div className={styles.bar}>
          {soap ? (
            <Tag tone="info">
              <Icon name="requests" size={11} /> SOAP envelope — the body names the operation
            </Tag>
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
                aria-label="Try a request"
                placeholder="Try a request — a path a client would call, or paste a SOAP envelope"
                onChange={(event) => typePath(event.target.value)}
                onFocus={() => setExplaining(true)}
                onBlur={() => setExplaining(false)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && runnable && !running) void run();
                }}
              />
            </>
          )}

          {/* Only offered where it can matter: a body is read at all only when a key declares it. */}
          {!soap && !wantsBody && (
            <button type="button" className={styles.subtle} onClick={() => setWantsBody(true)}>
              + body
            </button>
          )}

          {(soap || wantsBody || path) && (
            <button type="button" className={styles.subtle} onClick={clear}>
              Clear
            </button>
          )}

          <Button
            emphasis="secondary"
            icon={<Icon name="validate" size={14} />}
            disabled={!runnable || running}
            onClick={() => void run()}
          >
            {running ? 'Resolving…' : 'Resolve'}
          </Button>

          {/* Only once there is a request to carry over — an empty handover would land on the
              playground with nothing in it, which is worse than not offering the trip. */}
          {runnable && (
            <Button
              emphasis="muted"
              icon={<Icon name="playground" size={14} />}
              onClick={() => void handOver()}
            >
              Send it
            </Button>
          )}
        </div>

        {showBody && (
          <div className={styles.editor}>
            <CodeEditor
              value={body}
              language={soap ? 'xml' : 'json'}
              placeholder={
                soap
                  ? 'The envelope names its own operation.'
                  : 'A JSON body, for an operation whose key is read from one.'
              }
              onChange={(next) => {
                setBody(next);
                // The trace described the previous text; leaving it up answers a question no longer
                // being asked.
                setTrace(null);
              }}
            />
          </div>
        )}

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

      {/*
        Floated rather than inserted. Shown while the field has focus and nothing has been asked
        yet — the explanation is worth having when somebody is about to use this, and worth nothing
        the rest of the time. Positioned over the payload instead of above it, because a hint that
        shoves the editor down 60px every time the field is clicked is its own kind of annoying.
      */}
      {explaining && !showBody && !trace && !error && (
        <p className={styles.hint}>
          See what a call would get: which operation it reaches, what identifies it, every file tried, and
          which one answers. Nothing is sent and nothing is saved. Independent of the file below — resolve,
          then open the file it finds.
        </p>
      )}
    </div>
  );
}
