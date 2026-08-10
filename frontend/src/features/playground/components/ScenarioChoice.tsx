import { api, type Scenario } from '@/api';
import { useAsync } from '@/hooks/useAsync';
import styles from './ScenarioChoice.module.css';

/**
 * Which scenario to send against.
 *
 * Not the mocks page's picker, though it looks like one. That chooses which files are *browsed*;
 * this chooses which scenario a real request is answered from, and it carries an option that one has
 * no use for — "whatever is active", meaning send exactly as an ordinary client would and let the
 * sandbox decide. That default matters: a playground pinned to a named scenario would quietly stop
 * agreeing with what everyone else is being served.
 *
 * Either way nothing is switched. The choice rides along as the scenario override header, the same
 * way a real client asks for one, so trying a scenario never disturbs what the sandbox serves.
 */
export function ScenarioChoice({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const state = useAsync<Scenario[]>(() => api.listScenarios(), []);

  return (
    <label className={styles.wrap}>
      <span className={styles.label}>Send against</span>
      <select
        className={styles.select}
        value={value}
        disabled={state.status !== 'ready'}
        onChange={(event) => onChange(event.target.value)}
      >
        <option value="">whatever is active</option>
        {state.status === 'ready' &&
          state.data.map((scenario) => (
            <option key={scenario.id} value={scenario.id}>
              {scenario.name}
            </option>
          ))}
      </select>
    </label>
  );
}
