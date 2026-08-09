import { useState } from 'react';
import { api, type MockName, type Operation, type Service } from '@/api';
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
        <div className={styles.keys}>
          {operation.keys.map((key) => (
            <Field
              key={key.name}
              label={key.name}
              hint={
                <span className={styles.source}>
                  {key.source.toLowerCase()} · {key.expression}
                </span>
              }
            >
              <TextInput
                mono
                value={values[key.name] ?? ''}
                placeholder="leave blank for the operation's default"
                onChange={(event) => setValues({ ...values, [key.name]: event.target.value })}
              />
            </Field>
          ))}
        </div>
      ) : (
        <p className={styles.note}>
          This operation declares no keys, so it has one mock: the default every request to it
          resolves to.
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

      {createError && <p className={styles.error}>{createError}</p>}
    </Dialog>
  );
}
