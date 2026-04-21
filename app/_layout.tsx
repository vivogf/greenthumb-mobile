import '../i18n/index';
import { useEffect } from 'react';
import { Stack, router } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import * as SplashScreen from 'expo-splash-screen';
import { PersistQueryClientProvider } from '@tanstack/react-query-persist-client';
import { I18nextProvider } from 'react-i18next';
import { AppState, type AppStateStatus } from 'react-native';
import { focusManager } from '@tanstack/react-query';
import { queryClient, persister } from '../lib/queryClient';
import { loadSavedLanguage } from '../i18n/index';
import { AuthProvider } from '../contexts/AuthContext';
import { ThemeProvider, useTheme } from '../contexts/ThemeContext';
import { AlertDialogProvider } from '../components/AlertDialog';
import { useColors } from '../hooks/useColors';
import * as SystemUI from 'expo-system-ui';
import i18n from '../i18n/index';
import {
  initNotificationHandler,
  addNotificationResponseListener,
} from '../lib/notifications';

// Keep the native splash screen visible until auth is resolved
SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  useEffect(() => {
    loadSavedLanguage();
  }, []);

  useEffect(() => {
    // Bridge React Native AppState → React Query focusManager so that
    // refetchOnWindowFocus actually triggers when the user returns to the app.
    // Without this, refetchOnWindowFocus is silently a no-op on mobile.
    const onAppStateChange = (status: AppStateStatus) => {
      focusManager.setFocused(status === 'active');
    };
    const sub = AppState.addEventListener('change', onAppStateChange);
    return () => sub.remove();
  }, []);

  useEffect(() => {
    // Foreground notification banner (no-op in Expo Go).
    initNotificationHandler();

    // Deep-link from a tapped notification: if backend includes `plant_id`
    // in the push payload, jump straight to that plant. Otherwise just open
    // the app (auth gate handles the rest).
    let unsubscribe: (() => void) | undefined;
    addNotificationResponseListener((data) => {
      const plantId = data?.plant_id;
      if (typeof plantId === 'number' || typeof plantId === 'string') {
        router.push(`/plant/${plantId}`);
      }
    }).then((u) => {
      unsubscribe = u;
    });

    return () => {
      unsubscribe?.();
    };
  }, []);

  return (
    <PersistQueryClientProvider
      client={queryClient}
      persistOptions={{
        persister,
        maxAge: 1000 * 60 * 60 * 24 * 7,
        buster: 'v1',
      }}
    >
      <AuthProvider>
        <ThemeProvider>
          <I18nextProvider i18n={i18n}>
            <AlertDialogProvider>
              <ThemedStack />
            </AlertDialogProvider>
          </I18nextProvider>
        </ThemeProvider>
      </AuthProvider>
    </PersistQueryClientProvider>
  );
}

/**
 * Stack + status bar + system UI — split out so they can consume the theme
 * context (must live below <ThemeProvider>).
 */
function ThemedStack() {
  const colors = useColors();
  const { effective } = useTheme();

  useEffect(() => {
    // Set native Android window background to prevent white flash during screen transitions
    SystemUI.setBackgroundColorAsync(colors.background);
  }, [colors.background]);

  return (
    <>
      <StatusBar style={effective === 'dark' ? 'light' : 'dark'} />
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: colors.background },
        }}
      >
        {/*
          The index route acts as the initial auth gate.
          After that, (auth) and (tabs) groups handle their own redirects.
        */}
        <Stack.Screen name="index" />
        <Stack.Screen name="(intro)" />
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
        <Stack.Screen name="plant/[id]" options={{ headerShown: false }} />
      </Stack>
    </>
  );
}
