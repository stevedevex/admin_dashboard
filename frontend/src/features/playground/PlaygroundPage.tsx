import { useAtom } from 'jotai';
import { useState } from 'react';
import { PageHeader } from '@/app/layout/PageHeader';
import { api, type PlaygroundResult } from '@/api';
import type { PlaygroundHandoff } from '@/state/handoff';
import { Button, CodeEditor, EmptyState, Icon, Panel, Tag } from '@/ui';
import { bodyAtom, methodAtom, pathAtom, scenarioAtom } from './atoms';
import { OperationPicker, type Drafted } from './components/OperationPicker';
import { ResponsePanel } from './components/ResponsePanel';
import { ScenarioChoice } from './components/ScenarioChoice';
import { TracePanel } from './components/TracePanel';
import { usePlaygroundHandoff } from './hooks/usePlaygroundHandoff';
import { isEnvelope, isSendable } from '@/lib/protocol';
import styles from './PlaygroundPage.module.css';

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

/** What to send, resolved from the fields or from a handoff that supplied its own. */
type Attempt = { method: string; path: string; body: string; scenarioId: string };

/**
 * Send a request to the sandbox, and see exactly what a client would get back.
 *
 * The question next door to the dry run, and a different one. The probe beside the mock editor
 * answers "which file would answer this, and why", and says nothing about the bytes — but a mock
 * file holds a payload, and what reaches a client is that payload wrapped in an envelope, given a
 * status a sidecar or the contract chose, carrying headers written in neither place. Until this page
 * there was no way to see any of that without leaving for curl, at exactly the moment somebody is
 * least sure the sandbox works.
 *
 * <h3>Request beside response, not above it</h3>
 *
 * Unlike the mocks page, which stacks its two panels because they are unrelated and unequal. Here
 * they are one exchange and roughly the same shape — an envelope in, an envelope out — and
 * comparing them is the whole activity. Side by side keeps both readable at once, which is what
 * somebody checking a request field against the response it produced actually needs.
 */
export function PlaygroundPage() {
  const [method, setMethod] = useAtom(methodAtom);
  const [path, setPath] = useAtom(pathAtom);
  const [body, setBody] = useAtom(bodyAtom);
  const [scenario, setScenario] = useAtom(scenarioAtom);

  const [result, setResult] = useState<PlaygroundResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  const soap = isEnvelope(body);
  const sendable = isSendable(path, body);

  /**
   * @param attempt what to send, when the caller has values the fields do not hold yet. A replayed
   *   call is loaded and sent in one commit, and reading the fields would send what they held
   *   before that commit rather than what was just handed over.
   */
  const send = async (attempt?: Attempt) => {
    const sent = attempt ?? { method, path, body, scenarioId: scenario };
    const envelope = isEnvelope(sent.body);

    setSending(true);
    setError(null);
    try {
      setResult(
        await api.sendToPlayground({
          ...(sent.scenarioId ? { scenarioId: sent.scenarioId } : {}),
          ...(envelope
            ? { body: sent.body }
            : { method: sent.method, path: sent.path, ...(sent.body ? { body: sent.body } : {}) }),
        }),
      );
    } catch (cause) {
      setResult(null);
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSending(false);
    }
  };

  const load = (draft: Drafted) => {
    setMethod(draft.method ?? 'POST');
    setPath(draft.path);
    setBody(draft.body ?? '');
    setNote(draft.note);
    setError(null);
    // The previous answer described the previous request; leaving it up beside a new one invites
    // reading the two as a pair.
    setResult(null);
  };

  usePlaygroundHandoff((handoff: PlaygroundHandoff) => {
    const arrived: Attempt = {
      method: handoff.method ?? 'POST',
      path: handoff.path ?? '',
      body: handoff.body ?? '',
      scenarioId: handoff.scenarioId ?? scenario,
    };

    setMethod(arrived.method);
    setPath(arrived.path);
    setBody(arrived.body);
    setScenario(arrived.scenarioId);
    setNote(null);
    setError(null);
    setResult(null);

    if (handoff.send) void send(arrived);
  });

  return (
    <>
      <PageHeader
        title="Playground"
        meta={
          <>
            <ScenarioChoice value={scenario} onChange={setScenario} />
            <Tag tone="neutral">calls are real, and appear in Requests</Tag>
          </>
        }
      />

      <div className={styles.layout}>
        <Panel title="Request" flush>
          <div className={styles.request}>
            <OperationPicker onDraft={load} />

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
                    {METHODS.map((verb) => (
                      <option key={verb} value={verb}>
                        {verb}
                      </option>
                    ))}
                  </select>
                  <input
                    className={styles.path}
                    value={path}
                    aria-label="Path"
                    placeholder="A path a client would call, or paste a SOAP envelope below"
                    onChange={(event) => setPath(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' && sendable && !sending) void send();
                    }}
                  />
                </>
              )}

              <Button
                emphasis="accent"
                icon={<Icon name="send" size={14} />}
                disabled={!sendable || sending}
                onClick={() => void send()}
              >
                {sending ? 'Sending…' : 'Send'}
              </Button>
            </div>

            {/* What the draft could not fill in. Without it, a request that fell through to the
                operation's default reads as a miss somebody has to work out for themselves. */}
            {note && (
              <p className={styles.note}>
                <Icon name="warn" size={12} />
                <span>{note}</span>
              </p>
            )}

            <div className={styles.editor}>
              <CodeEditor
                value={body}
                language={soap ? 'xml' : 'json'}
                placeholder="A SOAP envelope, or a JSON body for an operation that reads one. Draft one above to start."
                onChange={setBody}
              />
            </div>
          </div>
        </Panel>

        <Panel title="Response" flush>
          {error ? (
            <div className={styles.error}>
              <Tag tone="error" icon={<Icon name="error" size={11} />}>
                not sent
              </Tag>
              <p>{error}</p>
            </div>
          ) : result ? (
            <div className={styles.response}>
              <ResponsePanel result={result} />
              {result.requestId && <TracePanel requestId={result.requestId} />}
            </div>
          ) : (
            <EmptyState title="Nothing sent yet">
              Draft a request from an operation, or write one, and send it. What comes back is what
              your application would receive — envelope, status and headers included.
            </EmptyState>
          )}
        </Panel>
      </div>
    </>
  );
}
