import { useAtom, useAtomValue, useSetAtom } from 'jotai';
import { useMemo, useState } from 'react';
import { api, type MockContent, type ValidationResult } from '@/api';
import { useAsync } from '@/hooks/useAsync';
import { formatBytes } from '@/lib/format';
import { Button, CodeEditor, EmptyState, Icon, MetaTag, Panel, Tag, type CodeMarker } from '@/ui';
import { storeNonceAtom } from '@/state/store';
import { draftsAtom, provenanceAtom, reloadNonceAtom } from '../atoms';
import { IssueList } from './IssueList';
import { MockStateTag } from './MockStateTag';
import styles from './ResponsePanel.module.css';

/**
 * The stored response, and the actions that change it.
 *
 * The component holding the draft owns the buttons that act on it — splitting
 * them would mean lifting editor state into the page for no benefit.
 */
export function ResponsePanel({ mockId }: { mockId: string | null }) {
  return (
    <Panel title="Response" flush footer={null}>
      {mockId ? (
        <Loaded mockId={mockId} />
      ) : (
        <EmptyState title="No file selected">Select a file, or paste a request to create one.</EmptyState>
      )}
    </Panel>
  );
}

function Loaded({ mockId }: { mockId: string }) {
  const nonce = useAtomValue(reloadNonceAtom);
  const storeNonce = useAtomValue(storeNonceAtom);
  const bumpReload = useSetAtom(reloadNonceAtom);
  const [drafts, setDrafts] = useAtom(draftsAtom);
  const [provenance, setProvenance] = useAtom(provenanceAtom);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [validating, setValidating] = useState(false);
  const [validation, setValidation] = useState<ValidationResult | null>(null);

  const state = useAsync<MockContent | null>(() => api.getMock(mockId), [mockId, nonce, storeNonce]);

  // Results describe a specific payload, so they must not outlive an edit —
  // a stale green tick beside changed text is worse than no result at all.
  const markers = useMemo<CodeMarker[]>(
    () => validation?.issues.map((issue) => ({ line: issue.line, message: issue.message })) ?? [],
    [validation],
  );

  if (state.status === 'loading') return <p className="pad-4 muted">Loading…</p>;
  if (state.status === 'error') return <p className="pad-4 muted">{state.error.message}</p>;

  const draft = drafts[mockId];

  // A mock that does not exist yet, opened with a starting point — arrived at from the request
  // log, where a call named the file that would have answered it. Nothing has been written; the
  // editor holds a draft, and saving is what creates it.
  const unwritten = state.data === null;
  if (unwritten && draft === undefined) return <EmptyState title="File not found" />;

  const mock = state.data;
  const value = draft ?? mock?.body ?? '';
  const dirty = unwritten || (draft !== undefined && draft !== mock?.body);

  const setDraft = (next: string) => {
    setDrafts({ ...drafts, [mockId]: next });
    setValidation(null);
  };

  const discard = () => {
    const { [mockId]: _dropped, ...rest } = drafts;
    setDrafts(rest);
    setSaveError(null);
    setValidation(null);
  };

  const validate = async () => {
    setValidating(true);
    try {
      // A verdict sticks to the file only when the file is what was checked. For a draft the
      // answer is still shown here — that is what the button is for — but the tree must keep
      // saying `unchecked`, because nothing has vouched for the stored bytes.
      setValidation(await api.validateMock(mockId, value, { remember: !dirty }));

      // Refetch so the tree and the panel agree: without it the panel reads valid while the file
      // beside it still reads unchecked.
      if (!dirty) bumpReload((n) => n + 1);
    } finally {
      setValidating(false);
    }
  };

  const save = async () => {
    setSaving(true);
    setSaveError(null);
    try {
      const request = provenance[mockId];
      await api.saveMock(mockId, value, request === undefined ? {} : { request });

      // Stored now, so it must not be re-sent on the next save — the server would take a second
      // copy as an update, and the record belongs to the call that created the mock.
      if (request !== undefined) {
        const { [mockId]: _stored, ...rest } = provenance;
        setProvenance(rest);
      }

      discard();
      // Size, timestamp and validation state are all recomputed server-side,
      // so refetch rather than patching them in from the response — the tree
      // shows the same numbers and must not drift from the panel.
      bumpReload((n) => n + 1);
    } catch (cause) {
      setSaveError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className={styles.wrap}>
      <div className={styles.meta}>
        <MetaTag label="File" value={mock?.fileName ?? fileNameOf(mockId)} />
        {mock ? (
          <>
            <MetaTag label="Size" value={formatBytes(mock.sizeBytes)} />
            {mock.completeness !== null && (
              <MetaTag label="Complete" value={`${mock.completeness}%`} />
            )}
            <MockStateTag state={mock.state} />
            {mock.inherited && (
              <Tag tone="info" icon={<Icon name="scenarios" size={11} />}>
                inherited from {mock.scenarioId}
              </Tag>
            )}
          </>
        ) : (
          // Said plainly, because the difference matters: this file is not in the library, and
          // nothing answers the call it was drafted for until it is saved.
          <Tag tone="warn" icon={<Icon name="create" size={11} />}>
            new — not saved yet
          </Tag>
        )}
        {mock?.request && <Tag tone="info">written from a recorded call</Tag>}
        {dirty && !unwritten && <Tag tone="warn">unsaved</Tag>}
      </div>

      <div className={styles.editor}>
        <CodeEditor
          value={value}
          language={mock?.format ?? formatOf(mockId)}
          markers={markers}
          onChange={setDraft}
        />
      </div>

      {validation && <IssueList result={validation} />}

      <div className={styles.footer}>
        {saveError && <span className={styles.error}>{saveError}</span>}
        <Button emphasis="muted" onClick={discard} disabled={!dirty || saving}>
          Discard
        </Button>
        <Button
          emphasis="secondary"
          onClick={validate}
          disabled={validating}
          icon={<Icon name="validate" size={14} />}
        >
          {validating ? 'Checking…' : 'Validate'}
        </Button>
        <Button
          emphasis="primary"
          onClick={save}
          disabled={!dirty || saving}
          icon={<Icon name="save" size={14} />}
        >
          {saving ? 'Saving…' : 'Save'}
        </Button>
      </div>
    </div>
  );
}

/** A mock id is `scenario/service/operation/file`, so the file name is simply its tail. */
function fileNameOf(mockId: string): string {
  return mockId.split('/').pop() ?? mockId;
}

/** Highlighting for a file that does not exist yet: its extension is all there is to go on. */
function formatOf(mockId: string): 'json' | 'xml' | 'text' {
  if (mockId.endsWith('.json')) return 'json';
  if (mockId.endsWith('.xml')) return 'xml';
  return 'text';
}
