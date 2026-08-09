import { Outlet } from 'react-router';
import { SideRail } from './SideRail';
import styles from './AppShell.module.css';

/** Rail plus scrolling content area. Every route renders inside this. */
export function AppShell() {
  return (
    <div className={styles.shell}>
      <SideRail />
      <main className={styles.content}>
        <Outlet />
      </main>
    </div>
  );
}
