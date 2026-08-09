import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { applyStoredTheme } from './state/theme';
import './ui/tokens.css';
import './styles/global.css';

// Before React, so the first paint is already the right colour rather than a flash of the
// wrong one that then corrects itself.
applyStoredTheme();

const container = document.getElementById('root');
if (!container) throw new Error('Root element not found');

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
