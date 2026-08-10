import { Outlet } from 'react-router';
import { TooltipProvider } from '@/ui';
import { SideRail } from './SideRail';
import styles from './AppShell.module.css';

/**
 * Rail plus scrolling content area. Every route renders inside this.
 *
 * The tooltip provider sits here rather than around the rail that first needed one. Radix keeps
 * its open/skip timing per provider, so two of them make neighbouring tooltips serve the opening
 * delay twice — and a page is free to label a control without arranging its own provider first.
 */
export function AppShell() {
  return (
    <TooltipProvider>
      <div className={styles.shell}>
        <SideRail />
        <main className={styles.content}>
          <Outlet />
        </main>
      </div>
    </TooltipProvider>
  );
}
