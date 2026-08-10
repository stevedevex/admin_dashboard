import { useAtomValue } from 'jotai';
import { api, type Service } from '@/api';
import { PageHeader } from '@/app/layout/PageHeader';
import { useAsync } from '@/hooks/useAsync';
import { useStoreReload } from '@/hooks/useStoreReload';
import { storeNonceAtom } from '@/state/store';
import { Button, Icon, Panel, Tag } from '@/ui';
import { ServiceTable } from './components/ServiceTable';
import { StatTiles } from './components/StatTiles';

/**
 * Every service the sandbox stands in for, with how each is addressed.
 *
 * Reference rather than summary — the dashboard carries the headline numbers,
 * this is where you come to check a service's endpoint, key fields or schema.
 */
export function ServicesPage() {
  const storeNonce = useAtomValue(storeNonceAtom);
  const store = useStoreReload();
  const state = useAsync<Service[]>(() => api.listServices(), [storeNonce]);

  return (
    <>
      <PageHeader
        title="Services"
        meta={state.status === 'ready' ? <Tag tone="info">{state.data.length} configured</Tag> : null}
        actions={
          <Button
            emphasis="secondary"
            icon={<Icon name="reload" size={14} />}
            disabled={store.reloading}
            onClick={() => void store.reload()}
          >
            {store.reloading ? 'Reading…' : 'Reload'}
          </Button>
        }
      />

      <div className="measure">
        <StatTiles services={state.status === 'ready' ? state.data : []} />

        <Panel title="Services" flush>
          {state.status === 'loading' && <p className="pad-4 muted">Loading…</p>}
          {state.status === 'error' && <p className="pad-4 muted">{state.error.message}</p>}
          {state.status === 'ready' && <ServiceTable services={state.data} />}
        </Panel>
      </div>
    </>
  );
}
