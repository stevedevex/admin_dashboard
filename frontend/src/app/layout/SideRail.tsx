import { useAtom } from 'jotai';
import type { ReactElement } from 'react';
import { NavLink } from 'react-router';
import { app } from '@/config/app';
import { navigation, type NavItem } from '@/config/navigation';
import { railCollapsedAtom } from '@/state/shell';
import { themeAtom } from '@/state/theme';
import { Icon, Tag, Tooltip, TooltipProvider } from '@/ui';
import styles from './SideRail.module.css';

/**
 * Collapsible navigation rail. Renders `config/navigation` — it holds no
 * knowledge of what the destinations are, and carries no feature state.
 *
 * Collapsed, every control is reduced to a glyph, so each one is given a tooltip naming it.
 * A native `title` was there before and is not enough: it arrives about a second late, never
 * appears for a keyboard user, and cannot be seen at all by someone who has already decided the
 * icons are unreadable and stopped hovering. Expanded, the labels are on screen and the tooltips
 * would only repeat them, so they are not rendered.
 */
export function SideRail() {
  const [collapsed, setCollapsed] = useAtom(railCollapsedAtom);
  const [theme, setTheme] = useAtom(themeAtom);
  const nextTheme = theme === 'dark' ? 'light' : 'dark';

  return (
    <TooltipProvider>
      <nav className={collapsed ? styles.railCollapsed : styles.rail} aria-label="Main">
        <div className={styles.brandRow}>
          {collapsed ? (
            // Reduced to its mark, not removed. An empty box where the product name was reads as a
            // rendering fault; the accent initial reads as the same product, smaller.
            <Tooltip label={`${app.brand} {${app.name}}`}>
              <span className={styles.brandMark}>{app.brand.charAt(0)}</span>
            </Tooltip>
          ) : (
            <span className={styles.brand}>
              {app.brand} <span className={styles.brandDivider}>│</span>{' '}
              <span className={styles.brandName}>{`{${app.name}}`}</span>
            </span>
          )}
          <Labelled when={collapsed} label="Expand navigation">
            <button
              type="button"
              className={styles.toggle}
              onClick={() => setCollapsed(!collapsed)}
              aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'}
              aria-expanded={!collapsed}
            >
              <Icon name="collapse" />
            </button>
          </Labelled>
        </div>

        <div className={styles.groups}>
          {navigation.map((group) => (
            <div key={group.id} className={styles.group}>
              {!collapsed && group.label ? <p className={styles.groupLabel}>{group.label}</p> : null}
              <ul className={styles.list}>
                {group.items
                  // Collapsed, a disabled destination is a glyph with no label and nothing behind
                  // it — and next to Help it made three near-identical circles. What is announced
                  // but not built can wait for the rail to be open.
                  .filter((item) => !collapsed || !item.disabled)
                  .map((item) => (
                    // Labelled wraps the item rather than the link inside it. Radix's asChild
                    // merges className as a string, and NavLink's is a function of its own active
                    // state — merged, it stops being callable and the link renders unstyled.
                    <Labelled key={item.id} when={collapsed} label={item.label}>
                      <li>
                        <RailLink item={item} collapsed={collapsed} />
                      </li>
                    </Labelled>
                  ))}
              </ul>
            </div>
          ))}
        </div>

        <div className={styles.footer}>
          <Labelled when={collapsed} label={`Switch to ${nextTheme} theme`}>
            <button
              type="button"
              className={styles.footerItem}
              onClick={() => setTheme(nextTheme)}
              aria-label={`Switch to ${nextTheme} theme`}
            >
              <span className={styles.marker} />
              <span className={styles.glyph}>
                <Icon name={nextTheme} />
              </span>
              {!collapsed && (
                <span className={styles.label}>{nextTheme === 'dark' ? 'Dark' : 'Light'}</span>
              )}
            </button>
          </Labelled>

          <Labelled when={collapsed} label="Help">
            <button type="button" className={styles.footerItem} aria-label="Help">
              <span className={styles.marker} />
              <span className={styles.glyph}>
                <Icon name="help" />
              </span>
              {!collapsed && <span className={styles.label}>Help</span>}
            </button>
          </Labelled>
          {!collapsed && <p className={styles.version}>v{app.version}</p>}
        </div>
      </nav>
    </TooltipProvider>
  );
}

/**
 * A tooltip, but only when the control has nothing else naming it.
 *
 * Expanded, the label is already on screen and a tooltip repeating it is noise that follows the
 * pointer around. Written as a component rather than a ternary at each call site because there are
 * five of them and the condition is the same every time.
 */
function Labelled({
  when,
  label,
  children,
}: {
  when: boolean;
  label: string;
  children: ReactElement;
}) {
  return when ? <Tooltip label={label}>{children}</Tooltip> : children;
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
      <span className={styles.itemDisabled} aria-disabled="true">
        {body}
      </span>
    );
  }

  return (
    <NavLink
      to={item.path}
      aria-label={item.label}
      className={({ isActive }) => (isActive ? styles.itemActive : styles.item)}
    >
      {body}
    </NavLink>
  );
}
