import { createBrowserRouter, Navigate } from 'react-router';
import { DEFAULT_ROUTE } from '@/config/navigation';
import { DashboardPage } from '@/features/dashboard/DashboardPage';
import { MocksPage } from '@/features/mocks/MocksPage';
import { PlaceholderPage } from '@/features/placeholder/PlaceholderPage';
import { PlaygroundPage } from '@/features/playground/PlaygroundPage';
import { RequestsPage } from '@/features/requests/RequestsPage';
import { ScenariosPage } from '@/features/scenarios/ScenariosPage';
import { ServicesPage } from '@/features/services/ServicesPage';
import { AppShell } from './layout/AppShell';

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to={DEFAULT_ROUTE} replace /> },
      { path: 'dashboard', element: <DashboardPage /> },
      { path: 'mock-data/services', element: <ServicesPage /> },
      { path: 'mock-data/mocks', element: <MocksPage /> },
      { path: 'mock-data/scenarios', element: <ScenariosPage /> },
      { path: 'mock-data/requests', element: <RequestsPage /> },
      { path: 'mock-data/playground', element: <PlaygroundPage /> },
      {
        path: 'phase-2',
        element: <PlaceholderPage title="Phase 2" summary="Reserved for the next capability." />,
      },
      {
        path: 'phase-3',
        element: <PlaceholderPage title="Phase 3" summary="Reserved for a later capability." />,
      },
      { path: '*', element: <Navigate to={DEFAULT_ROUTE} replace /> },
    ],
  },
]);
