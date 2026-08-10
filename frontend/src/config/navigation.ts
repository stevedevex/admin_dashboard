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

/**
 * What the product can do, as opposed to where you can go.
 *
 * Two lists because they answer different questions, and the dashboard needs the one the rail
 * does not have. The rail lists destinations: a capability with no pages yet contributes nothing
 * to it beyond a dimmed row. The dashboard has to name the capability itself — including the ones
 * that do not exist — because a landing page showing only what is built cannot be read as
 * "this is one of three things"; it reads as "this is the product".
 *
 * `id` is the same id as the nav group the capability owns, and `navigation.test.ts` holds them
 * together: a live capability with no group is a section on the dashboard that leads nowhere.
 *
 * @param summary one line, in the reader's terms, saying what the capability is for. Shown under
 *   the name on the dashboard — it is the only place the product says what any of this is.
 */
export type Capability = {
  id: string;
  name: string;
  summary: string;
  status: 'live' | 'planned';
};

export const capabilities: Capability[] = [
  {
    id: 'mock-data',
    name: 'Mock data',
    summary: 'Stand in for the services an application depends on, so it can be run and tested without them.',
    status: 'live',
  },
  {
    id: 'phase-2',
    name: 'Phase 2',
    summary: 'The next capability. Not built yet — it will report itself here when it is.',
    status: 'planned',
  },
  {
    id: 'phase-3',
    name: 'Phase 3',
    summary: 'The one after that. Not built yet — it will report itself here when it is.',
    status: 'planned',
  },
];

export const navigation: NavGroup[] = [
  {
    id: 'root',
    label: '',
    items: [{ id: 'dashboard', label: 'Dashboard', path: '/dashboard', icon: 'dashboard' }],
  },
  {
    id: 'mock-data',
    label: 'Mock data',
    items: [
      { id: 'services', label: 'Services', path: '/mock-data/services', icon: 'service' },
      { id: 'mocks', label: 'Mocks', path: '/mock-data/mocks', icon: 'mocks' },
      { id: 'scenarios', label: 'Scenarios', path: '/mock-data/scenarios', icon: 'scenarios' },
      { id: 'requests', label: 'Requests', path: '/mock-data/requests', icon: 'requests' },
      { id: 'playground', label: 'Playground', path: '/mock-data/playground', icon: 'playground' },
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
