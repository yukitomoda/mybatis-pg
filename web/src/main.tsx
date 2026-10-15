import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { migrateStorage } from './migrate';
import './styles.css';

try {
  migrateStorage(localStorage);
} catch {
  // 保存できない環境では引き継がない
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
