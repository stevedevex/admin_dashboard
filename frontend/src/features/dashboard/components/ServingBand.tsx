import { Link } from 'react-router';
import type { Scenario } from '@/api';
import { Icon, Tag } from '@/ui';
import styles from './ServingBand.module.css';

export type ServingBandProps = {
  /** The scenario id the server reports as active. Known before its record loads. */
  activeScenarioId: string | null;
  /** The full record, once listed. Null while loading, or if it names one no longer defined. */
  scenario: Scenario | null;
  problems: number;
};

/**
 * What the sandbox is currently answering with.
 *
 * Given the whole top of the page because it is the only fact here that changes what callers
 * receive: every other number describes what exists, this one describes what is served. Someone
 * debugging a client that suddenly returns empty collections needs to see `empty-results` on
 * arrival, not after opening a second page.
 *
 * Deliberately not a switcher. Changing the served scenario affects every caller of a shared
 * instance at once, so it stays a considered action on the Scenarios page rather than a dropdown
 * on the landing page that someone changes while looking for something else.
 */
export function ServingBand({ activeScenarioId, scenario, problems }: ServingBandProps) {
  return (
    <section className={styles.band}>
      <div className={styles.identity}>
        <span className={styles.label}>Serving</span>
        <p className={styles.scenario}>{activeScenarioId ?? '—'}</p>
        <p className={styles.description}>
          {scenario?.description || 'Every caller of this sandbox receives these responses.'}
        </p>
      </div>

      <div className={styles.aside}>
        <Tag
          tone={problems > 0 ? 'warn' : 'ok'}
          icon={<Icon name={problems > 0 ? 'warn' : 'ok'} size={12} />}
        >
          {problems > 0
            ? `${problems} ${problems === 1 ? 'needs' : 'need'} attention`
            : 'all mocks healthy'}
        </Tag>

        {scenario?.extends ? (
          <span className={styles.inherits}>
            inherits <span className={styles.inheritsFrom}>{scenario.extends}</span>
          </span>
        ) : null}

        <Link to="/mock-data/scenarios" className={styles.action}>
          Change what is served
          <Icon name="scenarios" size={13} />
        </Link>
      </div>
    </section>
  );
}
