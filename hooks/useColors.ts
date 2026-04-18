import { Colors } from '../lib/constants';
import { useTheme } from '../contexts/ThemeContext';

export function useColors() {
  const { effective } = useTheme();
  return effective === 'dark' ? Colors.dark : Colors.light;
}
