import { useState } from 'react';
import { api, type Operation, type Service } from '@/api';
import { useAsync } from '@/hooks/useAsync';
import { Button, Icon } from '@/ui';
import styles from './OperationPicker.module.css';

export type Drafted = {
  method: string | null;
  path: string;
  body: string | null;
  note: string | null;
};

/**
 * Picks an operation and asks the server for a request that would reach it.
 *
 * The alternative — an empty box and a cursor — makes the first thing anybody sees a fault, and a
 * tool whose first answer is a failure gets read as broken rather than as empty. So the opening move
 * is choosing an operation, and what comes back is already addressed to it.
 *
 * The draft is composed server-side and never here. Every value has to land where that key's own
 * declaration reads it from — a path variable, a query parameter, a JSON pointer, an XPath into an
 * envelope — and the declarations are the server's. Assembling a request on this side would be a
 * second implementation of the one rule that decides whether a request resolves at all.
 */
export function OperationPicker({ onDraft }: { onDraft: (draft: Drafted) => void }) {
  const services = useAsync<Service[]>(() => api.listServices(), []);
  const [serviceId, setServiceId] = useState('');
  const [operationId, setOperationId] = useState('');
  const [keys, setKeys] = useState<Record<string, string>>({});
  const [drafting, setDrafting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const all = services.status === 'ready' ? services.data : [];
  const service = all.find((candidate) => candidate.id === serviceId);
  const operation = service?.operations.find((candidate) => candidate.id === operationId);

  const pickService = (id: string) => {
    setServiceId(id);
    setKeys({});

    // One operation is not a choice. Selecting it saves a click that could only ever have one
    // outcome, and the field below shows what was selected either way.
    const [first, ...rest] = all.find((candidate) => candidate.id === id)?.operations ?? [];
    setOperationId(first && rest.length === 0 ? first.id : '');
  };

  const pickOperation = (id: string) => {
    setOperationId(id);
    setKeys({});
  };

  const draft = async (target: Operation) => {
    setDrafting(true);
    setError(null);
    try {
      const drafted = await api.draftRequest(serviceId, target.id, keys);
      onDraft(drafted);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setDrafting(false);
    }
  };

  return (
    <div className={styles.root}>
      <div className={styles.row}>
        <label className={styles.field}>
          <span className={styles.label}>Service</span>
          <select
            className={styles.select}
            value={serviceId}
            disabled={services.status !== 'ready'}
            onChange={(event) => pickService(event.target.value)}
          >
            <option value="">Choose…</option>
            {all.map((candidate) => (
              <option key={candidate.id} value={candidate.id}>
                {candidate.name}
              </option>
            ))}
          </select>
        </label>

        <label className={styles.field}>
          <span className={styles.label}>Operation</span>
          <select
            className={styles.select}
            value={operationId}
            disabled={!service}
            onChange={(event) => pickOperation(event.target.value)}
          >
            <option value="">Choose…</option>
            {(service?.operations ?? []).map((candidate) => (
              <option key={candidate.id} value={candidate.id}>
                {candidate.id}
              </option>
            ))}
          </select>
        </label>

      </div>

      {/*
        The identifying values, asked for by name. Filling them is what makes the drafted request
        reach a specific mock rather than the operation's default — so the fields are offered here
        rather than left to be hunted for in the envelope afterwards.
      */}
      {operation && operation.keys.length > 0 && (
        <div className={styles.keys}>
          <span className={styles.label}>Identified by</span>
          {operation.keys.map((key) => (
            <label key={key.name} className={styles.key}>
              <span className={styles.keyName} title={`${key.source.toLowerCase()}: ${key.expression}`}>
                {key.name}
                {key.aliasOf && <em className={styles.alias}> ({key.aliasOf})</em>}
              </span>
              <input
                className={styles.keyValue}
                value={keys[key.name] ?? ''}
                placeholder="any"
                onChange={(event) => setKeys({ ...keys, [key.name]: event.target.value })}
              />
            </label>
          ))}
        </div>
      )}

      {/*
        Its own row, and always the last one. Sharing the line with the two selects put it at a
        different place on the panel depending on how long a service was named, and drafting is the
        one action here — it should be where the eye already went last, not wherever there was room.
      */}
      <div className={styles.actions}>
        <Button
          emphasis="secondary"
          icon={<Icon name="create" size={14} />}
          disabled={!operation || drafting}
          onClick={() => operation && void draft(operation)}
        >
          {drafting ? 'Drafting…' : 'Draft request'}
        </Button>
      </div>

      {error && <p className={styles.error}>{error}</p>}
    </div>
  );
}
