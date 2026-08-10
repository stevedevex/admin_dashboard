import { useAtomValue } from 'jotai';
import { api, type MockDataSummary, type Scenario, type Service } from '@/api';
import { PageHeader } from '@/app/layout/PageHeader';
import { useAsync } from '@/hooks/useAsync';
import { useRequestLog } from '@/hooks/useRequestLog';
import { storeNonceAtom } from '@/state/store';
import { formatBytes } from '@/lib/format';
import { capabilities } from '@/config/navigation';
import { MetricStrip, type Metric } from './components/MetricStrip';
import { ServingBand } from './components/ServingBand';
import { ActivityPanel } from './components/ActivityPanel';
import { CapabilitySection } from './components/CapabilitySection';
import { CoveragePanel } from './components/CoveragePanel';
import styles from './DashboardPage.module.css';

/**
 * The landing page: one section per capability the product has.
 *
 * Structured by capability rather than by panel, because the panels alone cannot say what they
 * are panels *of*. Everything on this page describes mock data, and laid out as a flat run of
 * cards that fact is invisible — the page reads as the whole product, which is true only until
 * there is a second capability. Naming the boundary while there is one thing inside it is what
 * makes the second one an addition rather than a redesign.
 *
 * Within the mock-data section the order is by how quickly a fact goes stale rather than by how
 * much of it there is. The served scenario changes what every caller receives and leads; the
 * library counts change when someone authors a mock; the request feed changes while being
 * watched. Someone opening this page mid-debug is asking "is my application reaching this, and is
 * it getting what I think" — both halves of that are answerable here without navigating.
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

  /**
   * What is worth someone's attention, across both kinds of wrong.
   *
   * Unreachable belongs here even though it is not a validation verdict. A mock at an address no
   * request produces is the one failure with no symptom anywhere else — it lists, it validates
   * clean, and it silently never answers — so a band reading "all mocks healthy" over one is the
   * single most misleading thing this page could say.
   */
  const problems = facts === null ? 0 : facts.invalidCount + facts.incompleteCount + facts.unreachableCount;

  /**
   * How many mocks anything has actually looked at.
   *
   * The invalid and incomplete counts are silent about this, and their silence is loudest exactly
   * when it matters: verdicts live in memory and are only written by a validation running, so a
   * restarted sandbox reports zero of each — the same numbers a library checked end to end and
   * found clean reports. Every zero below therefore says which of the two it is.
   */
  const checked = facts === null ? 0 : facts.mockCount - facts.uncheckedCount;

  /**
   * Said differently per tile. Both read "none of N checked" before, so two tiles carried the
   * identical sentence and the repetition read as a rendering fault rather than as two facts.
   */
  const nothingChecked = checked === 0;

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
      note:
        facts === null
          ? ''
          : facts.invalidCount > 0
            ? 'failing schema'
            : nothingChecked
              ? 'nothing checked yet'
              : `${checked} checked, none failing`,
      attention: facts !== null && facts.invalidCount > 0,
      to: '/mock-data/mocks',
    },
    {
      label: 'Incomplete',
      value: facts === null ? '—' : String(facts.incompleteCount),
      note:
        facts === null
          ? ''
          : facts.incompleteCount > 0
            ? 'partially filled'
            : nothingChecked
              ? 'nothing checked yet'
              : 'all fully populated',
      attention: facts !== null && facts.incompleteCount > 0,
      to: '/mock-data/mocks',
    },
    {
      // Not gated on `nothingChecked`: reachability is read off the file name and the operation's
      // configuration, so it is known for every mock from the moment the store is read. Unlike the
      // two above it, a zero here always means zero.
      label: 'Unreachable',
      value: facts === null ? '—' : String(facts.unreachableCount),
      note:
        facts === null
          ? ''
          : facts.unreachableCount > 0
            ? 'no request produces the name'
            : 'every name is reachable',
      attention: facts !== null && facts.unreachableCount > 0,
      to: '/mock-data/mocks',
    },
  ];

  // Rendered from the capability list rather than written out, so a capability that becomes real
  // is a change to `config/navigation.ts` and a case below — never a re-layout of this page.
  const mockData = capabilities.find((capability) => capability.id === 'mock-data');
  const rest = capabilities.filter((capability) => capability.id !== 'mock-data');

  return (
    <>
      <PageHeader title="Dashboard" />

      <div className={styles.page}>
        {mockData ? (
          <CapabilitySection capability={mockData}>
            <ServingBand
              activeScenarioId={facts?.activeScenarioId ?? null}
              scenario={serving}
              problems={problems}
            />

            <MetricStrip metrics={metrics} />

            <div className={styles.split}>
              <ActivityPanel entries={log.entries} live={log.live} sampled={log.sampled} error={log.error} />

              <CoveragePanel
                services={services.status === 'ready' ? services.data : []}
                state={services.status}
                error={services.status === 'error' ? services.error.message : null}
              />
            </div>
          </CapabilitySection>
        ) : null}

        {rest.map((capability) => (
          <CapabilitySection key={capability.id} capability={capability} />
        ))}
      </div>
    </>
  );
}
