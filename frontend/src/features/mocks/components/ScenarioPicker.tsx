import { api, type Scenario } from '@/api';
import { useAsync } from '@/hooks/useAsync';
import styles from './ScenarioPicker.module.css';

/**
 * Chooses which scenario's files are being browsed.
 *
 * Page-scoped on purpose. It changes what this page shows and nothing else —
 * it does not change what the sandbox serves, which is a deliberate action on
 * the Scenarios page.
 */
export function ScenarioPicker({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const state = useAsync<Scenario[]>(() => api.listScenarios(), []);

  return (
    <label className={styles.wrap}>
      <span className={styles.label}>Viewing</span>
      <select
        className={styles.select}
        value={value}
        disabled={state.status !== 'ready'}
        onChange={(event) => onChange(event.target.value)}
      >
        {state.status === 'ready' ? (
          state.data.map((scenario) => (
            <option key={scenario.id} value={scenario.id}>
              {scenario.name}
            </option>
          ))
        ) : (
          <option value={value}>{value}</option>
        )}
      </select>
    </label>
  );
}
