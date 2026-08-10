import { useState } from 'react';
import type { PlaygroundResult } from '@/api';
import { languageOf, prettify } from '@/lib/prettify';
import { CodeEditor, Icon, Tag } from '@/ui';
import styles from './ResponsePanel.module.css';

/**
 * What came back, verbatim.
 *
 * The bytes first and the explanation second, which is the opposite of how the dry run is arranged
 * and deliberately so. Someone who opened the playground has already decided the question is "what
 * does my client get" — the trace is for when the answer surprises them.
 *
 * Formatting is applied on the way to the screen only. A SOAP stack sends and receives on one line,
 * and one line is unreadable exactly when somebody is checking which field came back; but what is
 * reported as the response length and content type describes what actually arrived.
 */
export function ResponsePanel({ result }: { result: PlaygroundResult }) {
  const [raw, setRaw] = useState(false);

  const formatted = prettify(result.body);
  const reformatted = formatted !== result.body;

  return (
    <div className={styles.root}>
      <div className={styles.status}>
        <StatusTag status={result.status} />
        <span className={styles.took}>{result.tookMillis} ms</span>
        <span className={styles.bytes}>{new Blob([result.body]).size} bytes</span>

        <span className={styles.spacer} />

        {/* Offered only when it would change anything, so it never invites a pointless click. */}
        {reformatted && (
          <button type="button" className={styles.toggle} onClick={() => setRaw(!raw)}>
            {raw ? 'Formatted' : 'As received'}
          </button>
        )}
      </div>

      <div className={styles.body}>
        <CodeEditor value={raw ? result.body : formatted} language={languageOf(result.body)} readOnly />
      </div>

      <details className={styles.headers}>
        <summary className={styles.summary}>
          Headers <span className={styles.count}>{Object.keys(result.headers).length}</span>
        </summary>
        <dl className={styles.list}>
          {Object.entries(result.headers).map(([name, value]) => (
            <div key={name} className={styles.header}>
              <dt>{name}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      </details>

      {/*
        The one part of the trace that is not in the log entry, because the serving path never
        enumerates a request's fields. Shown here rather than with the rest of the trace since it is
        about the request that was sent, and it is the line that most often explains a surprise.

        Every field, never a subset — a list that stopped at ten would be silent about the eleventh,
        which by then is the one somebody is looking for. What changes with length is the shape, not
        the contents: the names are a wrapping row of their own, so the sentence explaining them
        stays one line whether there is one field or forty. Drafted bodies are schema-shaped, so a
        large contract routinely puts a dozen names here that nobody typed.
      */}
      {result.discarded.length > 0 && (
        <div className={styles.discarded}>
          <p className={styles.discardedSays}>
            <Icon name="warn" size={12} />
            <span>
              {result.discarded.length === 1 ? 'This field was' : `These ${result.discarded.length} fields were`}{' '}
              carried but read by nothing — no declared key looks at
              {result.discarded.length === 1 ? ' it' : ' them'}, so
              {result.discarded.length === 1 ? ' it' : ' they'} did not affect which mock answered.
            </span>
          </p>
          <ul className={styles.discardedFields}>
            {result.discarded.map((field) => (
              <li key={field} className={styles.discardedField}>
                {field}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

/** 2xx is unremarkable; anything else is usually why somebody is looking. */
function StatusTag({ status }: { status: number }) {
  if (status >= 500) return <Tag tone="error">{status}</Tag>;
  if (status >= 400) return <Tag tone="warn">{status}</Tag>;
  return <Tag tone="ok">{status}</Tag>;
}
