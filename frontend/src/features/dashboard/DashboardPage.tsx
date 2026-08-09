import { useAtomValue } from 'jotai';
import { api, type MockDataSummary, type Scenario, type Service } from '@/api';
import { PageHeader } from '@/app/layout/PageHeader';
import { useAsync } from '@/hooks/useAsync';
import { useRequestLog } from '@/hooks/useRequestLog';
import { storeNonceAtom } from '@/state/store';
import { formatBytes } from '@/lib/format';
import { MetricStrip, type Metric } from './components/MetricStrip';
import { ServingBand } from './components/ServingBand';
import { ActivityPanel } from './components/ActivityPanel';
import { CoveragePanel } from './components/CoveragePanel';
import { RoadmapStrip } from './components/RoadmapStrip';
import styles from './DashboardPage.module.css';

/**
 * The landing page: what is being served, what exists, and what is arriving.
 *
 * Ordered by how quickly a fact goes stale rather than by how much of it there is. The served
 * scenario changes what every caller receives and leads; the library counts change when someone
 * authors a mock; the request feed changes while being watched. Someone opening this page mid-
 * debug is asking "is my application reaching this, and is it getting what I think" — both halves
 * of that are answerable here without navigating.
 *
 * Each region loads independently. A backend that answers the catalogue but not the log leaves a
 * page with one broken panel, not a blank screen.
 */
export function DashboardPage() {
  const storeNonce = useAtomValue(storeNonceAtom);

  const summary = useAsync<MockDataSummary>(() => api.getMockDataSummary(), [storeNonce]);
  const services = useAsync<Service[]>(() => api.listServices(), [storeNonce]);
  const scenarios = useAsync<Scenario[]>(() => api.listScenarios(), [storeNonce]);
  const log = useRequestLog();

  const facts = summary.status === 'ready' ? summary.data : null;
  const problems = facts === null ? 0 : facts.invalidCount + facts.incompleteCount;

  const serving =
    facts !== null && scenarios.status === 'ready'
      ? (scenarios.data.find((scenario) => scenario.id === facts.activeScenarioId) ?? null)
      : null;

  const metrics: Metric[] = [
    {
      label: 'Services',
      value: facts === null ? '—' : String(facts.serviceCount),
      note:
        facts === null || facts.servicesWithoutSchema === 0
          ? 'all with schemas'
          : `${facts.servicesWithoutSchema} without schema`,
      attention: facts !== null && facts.servicesWithoutSchema > 0,
      to: '/mock-data/services',
    },
    {
      label: 'Mocks',
      value: facts === null ? '—' : String(facts.mockCount),
      note: facts === null ? '' : `largest ${formatBytes(facts.largestMockBytes)}`,
      to: '/mock-data/mocks',
    },
    {
      label: 'Scenarios',
      value: facts === null ? '—' : String(facts.scenarioCount),
      note: serving?.extends ? `extends ${serving.extends}` : 'no inheritance',
      to: '/mock-data/scenarios',
    },
    {
      label: 'Invalid',
      value: facts === null ? '—' : String(facts.invalidCount),
      note: facts === null || facts.invalidCount === 0 ? 'none failing schema' : 'failing schema',
      attention: facts !== null && facts.invalidCount > 0,
      to: '/mock-data/mocks',
    },
    {
      label: 'Incomplete',
      value: facts === null ? '—' : String(facts.incompleteCount),
      note:
        facts === null || facts.incompleteCount === 0
          ? 'none partially filled'
          : 'partially filled',
      attention: facts !== null && facts.incompleteCount > 0,
      to: '/mock-data/mocks',
    },
  ];

  return (
    <>
      <PageHeader title="Dashboard" />

      <div className={styles.page}>
        <ServingBand
          activeScenarioId={facts?.activeScenarioId ?? null}
          scenario={serving}
          problems={problems}
        />

        <MetricStrip metrics={metrics} />

        <div className={styles.split}>
          <ActivityPanel
            entries={log.entries}
            live={log.live}
            sampled={log.sampled}
            error={log.error}
          />

          <CoveragePanel
            services={services.status === 'ready' ? services.data : []}
            state={services.status}
            error={services.status === 'error' ? services.error.message : null}
          />
        </div>

        <RoadmapStrip />
      </div>
    </>
  );
}
