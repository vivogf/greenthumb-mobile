import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useColorScheme as useSystemColorScheme } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { THEME_STORE_KEY } from '../lib/constants';

export type ThemePreference = 'light' | 'dark' | 'auto';
export type EffectiveScheme = 'light' | 'dark';

interface ThemeContextValue {
  preference: ThemePreference;
  effective: EffectiveScheme;
  setPreference: (pref: ThemePreference) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) {
    throw new Error('useTheme must be used within <ThemeProvider>');
  }
  return ctx;
}

/**
 * Holds user's theme preference ('light' | 'dark' | 'auto') and resolves it
 * to the effective scheme. When preference === 'auto', follows the OS.
 * Persisted in AsyncStorage so selection survives restarts.
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const systemScheme = useSystemColorScheme();
  const [preference, setPreferenceState] = useState<ThemePreference>('auto');

  useEffect(() => {
    AsyncStorage.getItem(THEME_STORE_KEY)
      .then((v) => {
        if (v === 'light' || v === 'dark' || v === 'auto') {
          setPreferenceState(v);
        }
      })
      .catch(() => {
        // Storage failure — fall back to default 'auto'
      });
  }, []);

  const setPreference = useCallback((pref: ThemePreference) => {
    setPreferenceState(pref);
    AsyncStorage.setItem(THEME_STORE_KEY, pref).catch(() => {});
  }, []);

  const effective: EffectiveScheme =
    preference === 'auto' ? (systemScheme === 'dark' ? 'dark' : 'light') : preference;

  const value = useMemo(
    () => ({ preference, effective, setPreference }),
    [preference, effective, setPreference],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
