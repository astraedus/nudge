import { createRoot } from 'react-dom/client';
import '../../ui/tokens.css';
import { Dashboard } from './Dashboard';

createRoot(document.getElementById('root')!).render(<Dashboard />);
