import { useAtomValue } from 'jotai';
import { api, type MockDataSummary } from '@/api';
import { PageHeader } from '@/app/layout/PageHeader';
import { useAsync } from '@/hooks/useAsync';
import { storeNonceAtom } from '@/state/store';
import { formatBytes } from '@/lib/format';
import { Tag } from '@/ui';
import { CapabilityCard } from './components/CapabilityCard';
import styles from './DashboardPage.module.css';

/**
 * The landing page for the sandbox as a whole.
 *
 * One card per capability, each summarising itself and linking into its own
 * area. Mock data is the first capability, not the product — later phases add
 * cards here without this page needing to know what they are.
 */
export function DashboardPage() {
  const storeNonce = useAtomValue(storeNonceAtom);
  const summary = useAsync<MockDataSummary>(() => api.getMockDataSummary(), [storeNonce]);

  const problems = summary.status === 'ready' ? summary.data.invalidCount + summary.data.incompleteCount : 0;

  return (
    <>
      <PageHeader
        title="Dashboard"
        meta={
          <Tag tone={problems > 0 ? 'warn' : 'ok'}>
            {problems > 0 ? `${problems} need attention` : 'healthy'}
          </Tag>
        }
      />

      <div className={styles.rows}>
        <CapabilityCard
          title="Mock Data"
          description="Stand in for upstream services with deterministic, versioned responses."
          icon="mocks"
          to="/mock-data/mocks"
          state={summary.status}
          error={summary.status === 'error' ? summary.error.message : null}
          stats={
            summary.status === 'ready'
              ? [
                  { label: 'Services', value: String(summary.data.serviceCount) },
                  { label: 'Mocks', value: String(summary.data.mockCount) },
                  { label: 'Scenarios', value: String(summary.data.scenarioCount) },
                  { label: 'Serving', value: summary.data.activeScenarioId, mono: true },
                ]
              : []
          }
          notes={
            summary.status === 'ready'
              ? [
                  summary.data.invalidCount > 0
                    ? { tone: 'error' as const, text: `${summary.data.invalidCount} invalid` }
                    : null,
                  summary.data.incompleteCount > 0
                    ? { tone: 'warn' as const, text: `${summary.data.incompleteCount} incomplete` }
                    : null,
                  summary.data.servicesWithoutSchema > 0
                    ? {
                        tone: 'neutral' as const,
                        text: `${summary.data.servicesWithoutSchema} without schema`,
                      }
                    : null,
                  {
                    tone: 'info' as const,
                    text: `largest ${formatBytes(summary.data.largestMockBytes)}`,
                  },
                ].filter((note) => note !== null)
              : []
          }
        />

        <div className={styles.row}>
          <CapabilityCard
            title="Phase 2"
            description="Reserved for the next capability."
            icon="planned"
            to={null}
            state="ready"
            error={null}
            stats={[]}
            notes={[{ tone: 'neutral', text: 'not started' }]}
          />

          <CapabilityCard
            title="Phase 3"
            description="Reserved for a later capability."
            icon="planned"
            to={null}
            state="ready"
            error={null}
            stats={[]}
            notes={[{ tone: 'neutral', text: 'not started' }]}
          />
        </div>
      </div>
    </>
  );
}
