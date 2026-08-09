import { useState } from 'react';
import { api, type MockContent, type OperationSchema, type Service } from '@/api';
import { useAsync } from '@/hooks/useAsync';
import { Button, CodeEditor, Dialog, Icon, Tag } from '@/ui';
import styles from './ContractStrip.module.css';

export type ContractStripProps = {
  serviceId: string;
  operationId: string;
  /** Present once the file is stored; absent for a draft that has never been saved. */
  effective: MockContent['effective'] | null;
};

/**
 * What the contract says about the operation this payload answers.
 *
 * Authoring a payload against a schema while the schema is invisible is the central awkwardness
 * of this page: everything needed — the keys that identify a request, the status a client
 * actually receives, the declared response shape — is already known to the server and was already
 * being fetched. It was simply never shown.
 *
 * `effective` in particular has no other route to a reader: a mock answering 201 with nothing of
 * its own got that from the contract, and nothing else on screen says so.
 *
 * The schema itself opens on demand rather than inline — it can run to hundreds of lines, and
 * this sits above an editor whose whole purpose is vertical room.
 */
export function ContractStrip({ serviceId, operationId, effective }: ContractStripProps) {
  const [showing, setShowing] = useState(false);

  const catalog = useAsync<Service | null>(() => api.getService(serviceId), [serviceId]);
  const operation =
    catalog.status === 'ready'
      ? (catalog.data?.operations.find((candidate) => candidate.id === operationId) ?? null)
      : null;

  // Fetched only once asked for, so the common case of never opening it costs nothing.
  const schema = useAsync<OperationSchema | null>(
    () => (showing ? api.getSchema(serviceId, operationId) : Promise.resolve(null)),
    [serviceId, operationId, showing],
  );

  return (
    <div className={styles.strip}>
      {operation && (
        <span className={styles.route}>
          <span className={styles.method}>{operation.method}</span>
          <span className={styles.path}>{operation.path}</span>
        </span>
      )}

      {/* What identifies a request here — the same fields that decide this file's name. */}
      {operation && operation.keys.length > 0 && (
        <span className={styles.keys} title="The fields the resolver reads to choose a mock">
          <Icon name="unknown" size={11} />
          {operation.keys.map((key) => key.name).join(' · ')}
        </span>
      )}

      {effective && (
        <span
          className={styles.effective}
          title="What a client actually receives, once the contract's defaults are applied over anything this mock overrides"
        >
          answers <strong>{effective.status}</strong> {effective.contentType}
        </span>
      )}

      <button type="button" className={styles.link} onClick={() => setShowing(true)}>
        View schema
      </button>

      {showing && (
        <Dialog
          open
          onOpenChange={(next) => {
            if (!next) setShowing(false);
          }}
          title={`Schema — ${serviceId}/${operationId}`}
          description="The response shape this operation declares. Read-only; the contract is the source, not this."
          footer={
            <Button emphasis="muted" onClick={() => setShowing(false)}>
              Close
            </Button>
          }
        >
          {schema.status === 'loading' && <p className={styles.note}>Loading…</p>}
          {schema.status === 'error' && <p className={styles.note}>{schema.error.message}</p>}

          {schema.status === 'ready' && schema.data && !schema.data.available && (
            <p className={styles.note}>
              <Tag tone="neutral">no schema</Tag> {schema.data.reason ?? 'This operation declares none.'}
            </p>
          )}

          {schema.status === 'ready' && schema.data?.schema && (
            <div className={styles.schema}>
              <CodeEditor
                value={schema.data.schema}
                language={schema.data.format === 'xml' ? 'xml' : 'json'}
                readOnly
                onChange={() => {}}
              />
            </div>
          )}
        </Dialog>
      )}
    </div>
  );
}
