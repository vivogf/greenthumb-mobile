import { useColorScheme } from 'react-native';
import { Colors } from '../lib/constants';

export function useColors() {
  const colorScheme = useColorScheme();
  return colorScheme === 'dark' ? Colors.dark : Colors.light;
}
