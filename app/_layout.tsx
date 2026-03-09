import '../i18n/index';
import { useEffect } from 'react';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import * as SplashScreen from 'expo-splash-screen';
import { QueryClientProvider } from '@tanstack/react-query';
import { I18nextProvider } from 'react-i18next';
import { useColorScheme } from 'react-native';
import { queryClient } from '../lib/queryClient';
import { loadSavedLanguage } from '../i18n/index';
import { AuthProvider } from '../contexts/AuthContext';
import { useColors } from '../hooks/useColors';
import * as SystemUI from 'expo-system-ui';
import i18n from '../i18n/index';

// Keep the native splash screen visible until auth is resolved
SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  const colorScheme = useColorScheme();
  const colors = useColors();

  useEffect(() => {
    loadSavedLanguage();
  }, []);

  useEffect(() => {
    // Set native Android window background to prevent white flash during screen transitions
    SystemUI.setBackgroundColorAsync(colors.background);
  }, [colors.background]);

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <I18nextProvider i18n={i18n}>
          <StatusBar style={colorScheme === 'dark' ? 'light' : 'dark'} />
          <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }}>
            {/*
              The index route acts as the initial auth gate.
              After that, (auth) and (tabs) groups handle their own redirects.
            */}
            <Stack.Screen name="index" />
            <Stack.Screen name="(auth)" />
            <Stack.Screen name="(tabs)" />
            <Stack.Screen
              name="add-plant"
              options={{
                headerShown: true,
                headerTitle: '',
                headerTransparent: true,
                presentation: 'card',
              }}
            />
            <Stack.Screen
              name="plant/[id]"
              options={{ headerShown: false }}
            />
          </Stack>
        </I18nextProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}
