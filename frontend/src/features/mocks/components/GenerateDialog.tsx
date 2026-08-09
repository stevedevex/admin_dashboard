import { useState } from 'react';
import { api, type AiStatus, type PayloadGeneration } from '@/api';
import { Button, Dialog, Field, Tag, TextArea } from '@/ui';
import styles from './GenerateDialog.module.css';

export type GenerateDialogProps = {
  serviceId: string;
  operationId: string;
  status: AiStatus;
  /** What the editor holds now. Sent along so an adjustment can stay an adjustment. */
  current: string;
  onClose: () => void;
  /** Hands the payload to the editor as a draft. Nothing is saved by generating. */
  onGenerated: (result: PayloadGeneration) => void;
};

/**
 * Asking for a payload in words.
 *
 * The prompt is optional and the placeholder says what happens without one, because the common
 * case is not a particular story — it is wanting the shape filled in at all, which is the tedious
 * part of writing a mock by hand.
 *
 * What comes back is a draft in the editor beside its verdict, never a saved file. That keeps one
 * way for a payload to enter the library, and keeps the author between the model and the store.
 */
export function GenerateDialog({
  serviceId,
  operationId,
  status,
  current,
  onClose,
  onGenerated,
}: GenerateDialogProps) {
  const [prompt, setPrompt] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const hasCurrent = current.trim().length > 0;

  const generate = async () => {
    setBusy(true);
    setError(null);
    try {
      onGenerated(
        await api.generatePayload(serviceId, operationId, prompt.trim() || undefined, current),
      );
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open
      onOpenChange={(next) => {
        if (!next && !busy) onClose();
      }}
      title="AI assist"
      description="Say what you want. It is written against this operation's schema, then checked against it."
      footer={
        <>
          <Button emphasis="muted" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button emphasis="accent" onClick={() => void generate()} disabled={busy}>
            {busy ? 'Working…' : 'Generate'}
          </Button>
        </>
      }
    >
      <div className={styles.target}>
        <span className={styles.targetLabel}>For</span>
        <code className={styles.operation}>
          {serviceId}/{operationId}
        </code>
      </div>

      <Field label="What do you want?">
        <TextArea
          rows={4}
          value={prompt}
          /*
           * Deliberately says nothing about any particular contract. This dialog opens over
           * whatever operation is selected — a list of records, a single entity, a SOAP
           * calculation — and an example drawn from one of them reads as an instruction
           * everywhere else, which is worse than no example at all.
           */
          placeholder={
            hasCurrent
              ? 'What to change — a few values, a missing field, more or fewer records. Or ask for something different and it will be replaced.'
              : 'How many records, which fields matter, any values to pin down. Leave blank for a representative, fully populated response.'
          }
          onChange={(event) => setPrompt(event.target.value)}
        />
      </Field>

      {/*
        Said plainly, because it changes what a prompt means. Someone who does not know the current
        payload is going along will write "add a tag to the ones missing it" expecting an edit and
        read a wholesale replacement as the feature ignoring them.
      */}
      {hasCurrent && (
        <p className={styles.context}>
          The payload already in the editor is sent along, so an adjustment can stay an adjustment.
          Ask for something different and it will be replaced.
        </p>
      )}

      {/*
        Which provider will answer, said before anything is generated rather than discovered
        afterwards — it is the one thing about the result nobody can check by reading it.
      */}
      <div className={styles.provenance}>
        <Tag tone="info">{status.generator}</Tag>
        <span className={styles.note}>
          {status.model} · the payload is checked against this operation&rsquo;s schema before you
          see it, and nothing is saved until you save it.
        </span>
      </div>

      {error && <p className={styles.error}>{error}</p>}
    </Dialog>
  );
}
