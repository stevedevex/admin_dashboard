import { navigation } from '@/config/navigation';
import { Icon, Tag } from '@/ui';
import styles from './RoadmapStrip.module.css';

/**
 * Capabilities the rail announces but nothing has built yet.
 *
 * Read from the navigation config rather than listed here, so a later phase is still added in one
 * place — the same property that made these full cards worth having, without spending half the
 * landing page to report that nothing exists. Announcing absence should cost a line, not a column.
 *
 * Renders nothing at all once every destination is built, rather than leaving an empty rule.
 */
export function RoadmapStrip() {
  const planned = navigation.flatMap((group) => group.items).filter((item) => item.disabled);

  if (planned.length === 0) return null;

  return (
    <section className={styles.strip}>
      <span className={styles.label}>Planned</span>

      <ul className={styles.items}>
        {planned.map((item) => (
          <li key={item.id} className={styles.item}>
            <Icon name={item.icon} size={13} />
            {item.label}
            {item.badge ? <Tag tone="neutral">{item.badge}</Tag> : null}
          </li>
        ))}
      </ul>
    </section>
  );
}
