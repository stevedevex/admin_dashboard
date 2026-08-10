import { useState } from 'react';
import { api, type KeyStrategy, type MockName, type Operation, type Service } from '@/api';
import { useAsync } from '@/hooks/useAsync';
import { Button, Dialog, Field, Select, Tag, TextInput } from '@/ui';
import styles from './NewMockDialog.module.css';

export type NewMockDialogProps = {
  onClose: () => void;
  /** The scenario being browsed: a new mock belongs where the author is looking. */
  scenarioId: string;
  /** Hands the drafted file to the editor, exactly as arriving from the request log does. */
  onDrafted: (draft: { mockId: string; body: string }) => void;
};

/** What filling these boxes in means, said once above them rather than implied by each. */
const GUIDANCE: Record<KeyStrategy, string> = {
  ALL: 'Every declared key goes in the name. Fill them all, or leave all of them blank to write the operation’s default.',
  FIRST_PRESENT:
    'The first key you fill names the file; the rest are never read. Leave all of them blank to write the operation’s default.',
  BEST_MATCH:
    'Fill only the keys that decide this response. Whatever you leave blank the mock matches on any value, and a file naming more keys wins over it.',
};

const PLACEHOLDER: Record<KeyStrategy, string> = {
  ALL: 'part of the name',
  FIRST_PRESENT: 'names the file',
  BEST_MATCH: 'blank matches any value',
};

/**
 * Which requests the named file will actually answer.
 *
 * Only under BEST_MATCH, because only there does a name understate itself: `name=laptop.json`
 * says nothing about the three other keys the request carries, and whether it answers depends on
 * what sits beside it in the directory. Under the other two the file name is the whole rule and a
 * sentence repeating it is noise.
 */
function reachOf(operation: Operation | undefined, named: string[]): string | null {
  if (!operation || operation.strategy !== 'BEST_MATCH') return null;

  const rest = operation.keys.map((key) => key.name).filter((key) => !named.includes(key));

  if (named.length === 0) {
    return 'Names no keys, so it is the operation’s default: it answers every request no more specific file matches.';
  }

  if (rest.length === 0) {
    return `Names every declared key, so it answers only a request carrying all of ${and(named)}.`;
  }

  return `Answers any request whose ${and(named)} ${verb(named, 'matches', 'match')}, whatever ${and(rest)} ${verb(rest, 'is', 'are')}.`;
}

/** `a`, `a and b`, `a, b and c` — a list read aloud rather than a comma-separated dump. */
function and(names: string[]): string {
  if (names.length <= 1) return names.join('');
  return `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`;
}

function verb(names: string[], singular: string, plural: string): string {
  return names.length === 1 ? singular : plural;
}

/**
 * Writing a mock from nothing.
 *
 * The file name is never composed here. It is asked for, on every change, because normalisation
 * decides whether a mock is reachable at all — `00005678` is stored as `5678`, `IBM` as `ibm` —
 * and a second implementation on this side would drift into saving files no request can resolve
 * to. Showing the server's answer as it is typed also makes the rule visible instead of a
 * surprise discovered later.
 *
 * The catalog is its own call rather than the mock tree's list, because the tree holds only
 * services that already have files — and a service with none is exactly the one somebody is here
 * to write for.
 *
 * What a blank key means is the operation's `strategy`, and the three answers are different enough
 * that one wording cannot serve them: under ALL a blank is an omission the server will refuse,
 * under FIRST_PRESENT everything past the first filled key is ignored, and under BEST_MATCH a
 * blank is the whole point — it is how a file says "whatever this field happens to be". Told only
 * the strictest of those, an author has no reason to try the subset the strategy exists for.
 */
export function NewMockDialog({ onClose, scenarioId, onDrafted }: NewMockDialogProps) {
  const catalog = useAsync<Service[]>(() => api.listServices(), []);
  const services = catalog.status === 'ready' ? catalog.data : [];

  // Selections are *derived* until something is picked, rather than initialised from data that has
  // not arrived. Storing a default at first render would freeze it at "nothing"; rendering a
  // second Dialog once the data lands would be worse still — unmounting the first is reported by
  // Radix as a dismissal, which closed the whole thing.
  const [pickedService, setPickedService] = useState('');
  const [pickedOperation, setPickedOperation] = useState('');

  const serviceId = pickedService || (services[0]?.id ?? '');
  const service = services.find((candidate) => candidate.id === serviceId);

  const operationId = pickedOperation || (service?.operations[0]?.id ?? '');
  const operation: Operation | undefined = service?.operations.find(
    (candidate) => candidate.id === operationId,
  );

  const [values, setValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Asked again whenever the operation or any value changes. The failure it can return is a real
  // answer, not a fault: the server refuses to name a file that could never be reached — a subset
  // of the keys an all-keys operation requires — and its wording explains that better than a
  // field-level error would.
  const name = useAsync<MockName>(
    () =>
      serviceId && operationId
        ? api.nameMock(serviceId, operationId, values)
        : Promise.reject(new Error('Choose an operation')),
    [serviceId, operationId, values],
  );

  const ready = name.status === 'ready';

  // Declaration order decides which key FIRST_PRESENT reads, so the box that matters is the first
  // one with anything in it — not the one most recently typed into.
  const firstFilled = (operation?.keys ?? []).findIndex((key) => (values[key.name] ?? '').trim() !== '');

  // The server's answer, not a second reading of the values: normalisation drops a key whose value
  // is blank or all zeros, and the file is named from what survived that.
  const reach = ready ? reachOf(operation, Object.keys(name.data.normalised)) : null;

  const pickService = (next: string) => {
    setPickedService(next);
    // Operations belong to a service; keeping the old one would name something that does not
    // exist. Cleared rather than reassigned, so the derived default takes over.
    setPickedOperation('');
    setValues({});
  };

  const pickOperation = (next: string) => {
    setPickedOperation(next);
    // Keys are declared per operation, so values collected for another one mean nothing here.
    setValues({});
  };

  const create = async () => {
    if (name.status !== 'ready' || !service) return;
    setBusy(true);
    setCreateError(null);
    try {
      const schema = await api.getSchema(serviceId, operationId);
      onDrafted({
        mockId: `${scenarioId}/${serviceId}/${operationId}/${name.data.fileName}`,
        body: schema.skeleton ?? '',
      });
      onClose();
    } catch (cause) {
      setCreateError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open
      onOpenChange={(next) => {
        if (!next) onClose();
      }}
      title="New mock"
      description="Choose an operation and say what identifies the request. The file name follows from that."
      footer={
        <>
          <Button emphasis="muted" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button emphasis="accent" onClick={() => void create()} disabled={!ready || busy}>
            {busy ? 'Opening…' : 'Write it'}
          </Button>
        </>
      }
    >
      {catalog.status === 'error' && <p className={styles.error}>{catalog.error.message}</p>}

      <Field label="Service">
        <Select
          value={serviceId}
          disabled={catalog.status !== 'ready'}
          onChange={(event) => pickService(event.target.value)}
        >
          {services.map((candidate) => (
            <option key={candidate.id} value={candidate.id}>
              {candidate.name} ({candidate.protocol})
            </option>
          ))}
        </Select>
      </Field>

      <Field label="Operation">
        <Select
          value={operationId}
          disabled={catalog.status !== 'ready'}
          onChange={(event) => pickOperation(event.target.value)}
        >
          {(service?.operations ?? []).map((candidate) => (
            <option key={candidate.id} value={candidate.id}>
              {candidate.id}
            </option>
          ))}
        </Select>
      </Field>

      {operation && operation.keys.length > 0 ? (
        <>
          <p className={styles.note}>{GUIDANCE[operation.strategy]}</p>
          <div className={styles.keys}>
            {operation.keys.map((key, index) => {
              const ignored = operation.strategy === 'FIRST_PRESENT' && index !== firstFilled;

              return (
                <Field
                  key={key.name}
                  label={key.name}
                  hint={
                    <span className={styles.source}>
                      {key.source.toLowerCase()} · {key.expression}
                      {key.aliasOf ? ` — the ${key.aliasOf} field` : ''}
                    </span>
                  }
                >
                  <TextInput
                    mono
                    value={values[key.name] ?? ''}
                    placeholder={
                      ignored ? 'not read — an earlier key answers first' : PLACEHOLDER[operation.strategy]
                    }
                    onChange={(event) => setValues({ ...values, [key.name]: event.target.value })}
                  />
                </Field>
              );
            })}
          </div>
        </>
      ) : (
        <p className={styles.note}>
          This operation declares no keys, so it has one mock: the default every request to it resolves to.
        </p>
      )}

      <div className={styles.result}>
        <span className={styles.resultLabel}>Saves as</span>
        {name.status === 'error' ? (
          <span className={styles.error}>{name.error.message}</span>
        ) : name.status === 'ready' ? (
          <>
            <code className={styles.fileName}>
              {scenarioId}/{serviceId}/{operationId}/{name.data.fileName}
            </code>
            {/* Where the rule becomes visible: what was typed against what will be stored. */}
            {Object.entries(name.data.normalised)
              .filter(([key, value]) => (values[key] ?? '').trim() !== value)
              .map(([key, value]) => (
                <Tag key={key} tone="info">
                  {key}: {values[key]} → {value}
                </Tag>
              ))}
          </>
        ) : (
          <span className={styles.muted}>…</span>
        )}
      </div>

      {/* Under BEST_MATCH the name understates the file: which requests it answers depends on the
          keys it leaves out as much as the ones it names, and on what else is stored beside it. */}
      {reach && <p className={styles.reach}>{reach}</p>}

      {createError && <p className={styles.error}>{createError}</p>}
    </Dialog>
  );
}
