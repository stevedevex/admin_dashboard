import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // The control panel is same-origin in production. Proxying it here keeps it same-origin in
      // development too, so no CORS configuration ever ships — see docs/control-panel-specs.md §2.
      // Only `/__tao` is proxied: the mock endpoints belong to the application under test, and
      // routing them through the dashboard's dev server would make it look like a client of them.
      '/__tao': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
});
