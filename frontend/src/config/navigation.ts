import type { IconName } from '@/ui';

/**
 * Navigation is configuration, not markup.
 *
 * The rail renders whatever this describes. Adding a destination means adding
 * an entry here and a route in `app/router.tsx` — never editing `SideRail`.
 *
 * The shape reflects what the product is: a sandbox environment, of which mock
 * data is the first capability. Dashboard sits above the feature groups and
 * summarises all of them.
 */

export type NavItem = {
  id: string;
  label: string;
  path: string;
  icon: IconName;
  /** Rendered dimmed and non-interactive, with a badge. */
  disabled?: boolean;
  badge?: string;
};

export type NavGroup = {
  id: string;
  label: string;
  items: NavItem[];
};

export const navigation: NavGroup[] = [
  {
    id: 'root',
    label: '',
    items: [{ id: 'dashboard', label: 'Dashboard', path: '/dashboard', icon: 'dashboard' }],
  },
  {
    id: 'mock-data',
    label: 'Mock Data',
    items: [
      { id: 'services', label: 'Services', path: '/mock-data/services', icon: 'service' },
      { id: 'mocks', label: 'Mocks', path: '/mock-data/mocks', icon: 'mocks' },
      { id: 'scenarios', label: 'Scenarios', path: '/mock-data/scenarios', icon: 'scenarios' },
      { id: 'requests', label: 'Requests', path: '/mock-data/requests', icon: 'requests' },
    ],
  },
  {
    id: 'future',
    label: '',
    items: [
      {
        id: 'phase-2',
        label: 'Phase 2',
        path: '/phase-2',
        icon: 'planned',
        disabled: true,
        badge: 'soon',
      },
      {
        id: 'phase-3',
        label: 'Phase 3',
        path: '/phase-3',
        icon: 'planned',
        disabled: true,
        badge: 'soon',
      },
    ],
  },
];

export const DEFAULT_ROUTE = '/dashboard';
