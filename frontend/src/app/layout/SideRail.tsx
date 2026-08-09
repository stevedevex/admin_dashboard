import { useAtom } from 'jotai';
import { NavLink } from 'react-router';
import { app } from '@/config/app';
import { navigation, type NavItem } from '@/config/navigation';
import { railCollapsedAtom } from '@/state/shell';
import { Icon, Tag } from '@/ui';
import styles from './SideRail.module.css';

/**
 * Collapsible navigation rail. Renders `config/navigation` — it holds no
 * knowledge of what the destinations are, and carries no feature state.
 */
export function SideRail() {
  const [collapsed, setCollapsed] = useAtom(railCollapsedAtom);

  return (
    <nav className={collapsed ? styles.railCollapsed : styles.rail} aria-label="Main">
      <div className={styles.brandRow}>
        {!collapsed && (
          <span className={styles.brand}>
            {app.brand} <span className={styles.brandDivider}>│</span>{' '}
            <span className={styles.brandName}>{`{${app.name}}`}</span>
          </span>
        )}
        <button
          type="button"
          className={styles.toggle}
          onClick={() => setCollapsed(!collapsed)}
          aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'}
          aria-expanded={!collapsed}
        >
          <Icon name="collapse" />
        </button>
      </div>

      <div className={styles.groups}>
        {navigation.map((group) => (
          <div key={group.id} className={styles.group}>
            {!collapsed && group.label ? <p className={styles.groupLabel}>{group.label}</p> : null}
            <ul className={styles.list}>
              {group.items.map((item) => (
                <li key={item.id}>
                  <RailLink item={item} collapsed={collapsed} />
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      <div className={styles.footer}>
        <button type="button" className={styles.footerItem} title="Help" aria-label="Help">
          <span className={styles.marker} />
          <span className={styles.glyph}>
            <Icon name="help" />
          </span>
          {!collapsed && <span className={styles.label}>Help</span>}
        </button>
        {!collapsed && <p className={styles.version}>v{app.version}</p>}
      </div>
    </nav>
  );
}

function RailLink({ item, collapsed }: { item: NavItem; collapsed: boolean }) {
  const body = (
    <>
      <span className={styles.marker} />
      <span className={styles.glyph}>
        <Icon name={item.icon} />
      </span>
      {!collapsed && (
        <>
          <span className={styles.label}>{item.label}</span>
          {item.badge ? <Tag tone="neutral">{item.badge}</Tag> : null}
        </>
      )}
    </>
  );

  if (item.disabled) {
    return (
      <span className={styles.itemDisabled} aria-disabled="true" title={item.label}>
        {body}
      </span>
    );
  }

  return (
    <NavLink
      to={item.path}
      title={item.label}
      className={({ isActive }) => (isActive ? styles.itemActive : styles.item)}
    >
      {body}
    </NavLink>
  );
}
