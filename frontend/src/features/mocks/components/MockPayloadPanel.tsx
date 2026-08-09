import { useAtom, useAtomValue, useSetAtom } from 'jotai';
import { useMemo, useState } from 'react';
import {
  api,
  type AiStatus,
  type MockContent,
  type MockSummary,
  type Operation,
  type PayloadGeneration,
  type Service,
  type ValidationResult,
} from '@/api';
import { useAsync } from '@/hooks/useAsync';
import { formatBytes } from '@/lib/format';
import {
  Button,
  CodeEditor,
  Dialog,
  EmptyState,
  Icon,
  MetaTag,
  Panel,
  Tag,
  type CodeMarker,
} from '@/ui';
import { storeNonceAtom } from '@/state/store';
import {
  draftsAtom,
  provenanceAtom,
  reloadNonceAtom,
  requestProbeAtom,
  selectedMockIdAtom,
} from '../atoms';
import { CasePicker } from './CasePicker';
import { ContractStrip } from './ContractStrip';
import { GenerateDialog } from './GenerateDialog';
import { IssueList } from './IssueList';
import { MockStateTag } from './MockStateTag';
import styles from './MockPayloadPanel.module.css';

/**
 * The stored payload, and the actions that change it.
 *
 * Not called "Response", and not paired with the panel above it. Those are the two halves of one
 * exchange, and naming them so promised a relationship this page does not have — the dry run above
 * answers about whatever is typed into it, routinely a different operation from the file open
 * here. What this shows is a file in the library.
 *
 * The component holding the draft owns the buttons that act on it — splitting
 * them would mean lifting editor state into the page for no benefit.
 */
export type MockPayloadPanelProps = {
  mockId: string | null;
  /** Every file of the operation the open one belongs to, for the choosers. */
  siblings: MockSummary[];
  onSelect: (mockId: string) => void;
};

export function MockPayloadPanel({ mockId, siblings, onSelect }: MockPayloadPanelProps) {
  /*
   * Both of these are fetched *here*, outside the keyed subtree below, because neither describes
   * the open file and neither should be re-fetched when it changes.
   *
   * The contract belongs to the operation, so it is re-read only when the service does — switching
   * between files of one operation asks for nothing. The capability is a property of the running
   * sandbox, so it is asked once for the life of the page, which is what its own note always
   * claimed and could not deliver from inside a component that remounts on every selection.
   */
  const { serviceId, operationId } = coordinatesOf(mockId ?? '');

  const catalog = useAsync<Service | null>(
    () => (serviceId === '' ? Promise.resolve(null) : api.getService(serviceId)),
    [serviceId],
  );
  const operation =
    catalog.status === 'ready'
      ? (catalog.data?.operations.find((candidate) => candidate.id === operationId) ?? null)
      : null;

  // Asked once, and never retried on failure: a sandbox with no AI module answers 404 here, which
  // is a normal deployment rather than an error worth reporting on a page about mock files. Null
  // covers both "not asked yet" and "no such module", and both mean the same thing on screen.
  const ai = useAsync<AiStatus | null>(() => api.getAiStatus().catch(() => null), []);

  return (
    <Panel title="Mock payload" flush footer={null}>
      {mockId ? (
        // Keyed by the file, so selecting another one starts clean. Everything held here describes
        // a specific payload — a verdict, a save error, where the text came from — and carrying any
        // of it to the next file would vouch for bytes nobody checked. Drafts survive regardless:
        // they live in an atom precisely so switching away does not discard unsaved work.
        <Loaded
          key={mockId}
          mockId={mockId}
          siblings={siblings}
          onSelect={onSelect}
          operation={operation}
          ai={ai.status === 'ready' ? ai.data : null}
        />
      ) : (
        <EmptyState title="No file selected">
          Pick a file on the left, or try a request above and open the file it resolves to.
        </EmptyState>
      )}
    </Panel>
  );
}

function Loaded({
  mockId,
  siblings,
  onSelect,
  operation,
  ai,
}: { mockId: string; operation: Operation | null; ai: AiStatus | null } & Omit<
  MockPayloadPanelProps,
  'mockId'
>) {
  const nonce = useAtomValue(reloadNonceAtom);
  const storeNonce = useAtomValue(storeNonceAtom);
  const bumpReload = useSetAtom(reloadNonceAtom);
  const [drafts, setDrafts] = useAtom(draftsAtom);
  const [provenance, setProvenance] = useAtom(provenanceAtom);
  const setProbe = useSetAtom(requestProbeAtom);
  const setSelected = useSetAtom(selectedMockIdAtom);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [validating, setValidating] = useState(false);
  const [validation, setValidation] = useState<ValidationResult | null>(null);
  const [generating, setGenerating] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);

  /** Where the text on screen came from, when it came from a model rather than from a person. */
  const [generated, setGenerated] = useState<PayloadGeneration | null>(null);

  const state = useAsync<MockContent | null>(() => api.getMock(mockId), [mockId, nonce, storeNonce]);

  /*
   * Coordinates come from the id, which is `scenario/service/operation/file` by construction and
   * is the only thing available for a file that does not exist yet — exactly the case where
   * generating a payload is most useful, so it must work without a stored record.
   */
  const { serviceId, operationId } = coordinatesOf(mockId);

  // The contract's key list, for rendering a file name as the key values it was built from.
  const keys = operation?.keys ?? [];

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
    setGenerated(null);
  };

  /**
   * The generated payload becomes an ordinary draft, and its verdict is shown without anyone
   * pressing Validate — it was checked on the way here, against the same contract, by the same
   * validator, so asking again would only re-run it.
   */
  const accept = (result: PayloadGeneration) => {
    setDraft(result.body);
    setValidation(result.validation);
    setGenerated(result);
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

  const remove = async () => {
    setDeleting(true);
    setSaveError(null);
    try {
      await api.deleteMock(mockId);

      // Selection first, so nothing re-fetches a file that is gone and renders "not found" at
      // somebody who just deleted it deliberately.
      setSelected(null);
      discard();
      bumpReload((n) => n + 1);
    } catch (cause) {
      setConfirmingDelete(false);
      setSaveError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setDeleting(false);
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
        {/*
          The file's identity and the way to change it are one control. It reads as the label it
          replaced until it is hovered, which is the point: switching file is frequent and should
          cost nothing, so it takes the line the name was already spending.
        */}
        <CasePicker
          files={siblings}
          selectedId={mockId}
          fileName={mock?.fileName ?? fileNameOf(mockId)}
          keys={keys}
          onSelect={onSelect}
        />
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
        {/*
          The one place a genuine request/response pair exists on this page: a mock saved from a
          recorded call keeps that call beside it. Announcing it and hiding it — which is what a
          bare tag did — is the least useful of the three options, so it is offered to the panel
          above, where a request is something you can actually run.
        */}
        {mock?.request && (
          <button
            type="button"
            className={styles.provenance}
            title="Load the call this mock was written for into the panel above"
            onClick={() => setProbe(mock.request ?? '')}
          >
            <Icon name="requests" size={11} /> written from a recorded call — try it
          </button>
        )}
        {dirty && !unwritten && <Tag tone="warn">unsaved</Tag>}

        {/*
          Stated on the text itself, for as long as it is on screen. Nobody can tell a generated
          payload from an authored one by reading it, and that is worth knowing before trusting it.
        */}
        {generated && <Tag tone="info">generated by {generated.generator}</Tag>}
        {generated && generated.attempts > 1 && (
          <Tag tone="neutral">repaired after {generated.attempts - 1}</Tag>
        )}

        {/*
          Up here beside the file it acts on, and deliberately far from Save. Grouping a
          destructive action with the commit actions puts it under a cursor already travelling
          towards Save, which is how people delete things they meant to keep.

          Offered only for a file that exists: there is nothing to remove from a draft, and
          Discard already abandons one.
        */}
        {mock && (
          <button
            type="button"
            className={styles.destructive}
            onClick={() => setConfirmingDelete(true)}
            disabled={saving || deleting}
          >
            <Icon name="delete" size={11} /> Delete
          </button>
        )}
      </div>

      <ContractStrip
        serviceId={serviceId}
        operationId={operationId}
        operation={operation}
        effective={mock?.effective ?? null}
      />

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
        {/*
          Left: what helps write the payload. Shown even when it cannot be used, disabled with the
          reason on hover — a hidden feature is an undiscoverable one, and whoever notices it
          missing here is usually whoever can configure it.
        */}
        <div className={styles.assist}>
          {ai && (
            <span title={ai.reason ?? undefined}>
              <Button
                emphasis="secondary"
                onClick={() => setGenerating(true)}
                disabled={saving || !ai.available}
                icon={<Icon name="ai" size={14} />}
              >
                AI Assist
              </Button>
            </span>
          )}
        </div>

        {/* Right: what acts on the draft, ordered abandon → check → commit. */}
        <div className={styles.actions}>
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

      {confirmingDelete && mock && (
        <Dialog
          open
          onOpenChange={(next) => {
            if (!next && !deleting) setConfirmingDelete(false);
          }}
          title="Delete this mock?"
          description="The payload and its sidecars are removed from the store. This cannot be undone from here."
          footer={
            <>
              <Button emphasis="muted" onClick={() => setConfirmingDelete(false)} disabled={deleting}>
                Cancel
              </Button>
              <Button emphasis="accent" onClick={() => void remove()} disabled={deleting}>
                {deleting ? 'Deleting…' : 'Delete'}
              </Button>
            </>
          }
        >
          {/*
            The id in full, not the file name. Two operations having a `_default` is the normal
            case here, so a bare file name is not enough to know which one is about to go.
          */}
          <p className={styles.confirm}>
            <code>{mockId}</code>
          </p>
          <p className={styles.confirmNote}>
            Any request that resolved to this file will fall back to the next candidate — an
            inherited file, the operation&rsquo;s default, or a miss.
          </p>
        </Dialog>
      )}

      {generating && ai && (
        <GenerateDialog
          serviceId={serviceId}
          operationId={operationId}
          status={ai}
          current={value}
          onClose={() => setGenerating(false)}
          onGenerated={accept}
        />
      )}
    </div>
  );
}

/** A mock id is `scenario/service/operation/file`, so the file name is simply its tail. */
function fileNameOf(mockId: string): string {
  return mockId.split('/').pop() ?? mockId;
}

/** The operation the same id names, for a file that has no stored record to read it from. */
function coordinatesOf(mockId: string): { serviceId: string; operationId: string } {
  const [, serviceId = '', operationId = ''] = mockId.split('/');
  return { serviceId, operationId };
}

/** Highlighting for a file that does not exist yet: its extension is all there is to go on. */
function formatOf(mockId: string): 'json' | 'xml' | 'text' {
  if (mockId.endsWith('.json')) return 'json';
  if (mockId.endsWith('.xml')) return 'xml';
  return 'text';
}
