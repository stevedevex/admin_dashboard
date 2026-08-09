import type { Scenario } from '@/api';
import { Button, Icon, Tag } from '@/ui';
import styles from './ScenarioTable.module.css';

export type ScenarioTableProps = {
  scenarios: Scenario[];
  /** Which one the sandbox serves, or null until the server has said. */
  activeId: string | null;
  /** The id currently being switched to, so only that row shows the pending state. */
  switching: string | null;
  onServe: (scenarioId: string) => void;
};

export function ScenarioTable({ scenarios, activeId, switching, onServe }: ScenarioTableProps) {
  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>Scenario</th>
          <th>Inherits</th>
          <th className={styles.numeric}>Services</th>
          <th className={styles.numeric}>Mocks</th>
          <th className={styles.serving}>Serving</th>
        </tr>
      </thead>
      <tbody>
        {scenarios.map((scenario) => {
          const active = scenario.id === activeId;

          return (
            <tr key={scenario.id} className={active ? styles.rowActive : undefined}>
              <td>
                <span className={styles.name}>{scenario.name}</span>
                <span className={styles.description}>{scenario.description}</span>
              </td>
              <td>
                {scenario.extends ? (
                  <Tag tone="info">{scenario.extends}</Tag>
                ) : (
                  <span className={styles.muted}>—</span>
                )}
              </td>
              <td className={styles.numeric}>{scenario.serviceIds.length}</td>
              <td className={styles.numeric}>{scenario.mockCount}</td>
              <td className={styles.serving}>
                {active ? (
                  // A state, not a button: switching to what is already served does nothing, and
                  // offering it would suggest otherwise.
                  <Tag tone="ok" icon={<Icon name="ok" size={11} />}>
                    serving
                  </Tag>
                ) : (
                  <Button
                    emphasis="muted"
                    disabled={switching !== null || activeId === null}
                    onClick={() => onServe(scenario.id)}
                  >
                    {switching === scenario.id ? 'Switching…' : 'Serve this'}
                  </Button>
                )}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
