import { Provider as JotaiProvider } from 'jotai';
import { RouterProvider } from 'react-router';
import { router } from './app/router';

export function App() {
  return (
    <JotaiProvider>
      <RouterProvider router={router} />
    </JotaiProvider>
  );
}
