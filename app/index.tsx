import { Redirect } from 'expo-router';
import { View, ActivityIndicator } from 'react-native';
import * as SplashScreen from 'expo-splash-screen';
import { useEffect, useState } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { useColors } from '../hooks/useColors';
import { getHasSeenIntro } from '../lib/storage';

/**
 * Initial route — resolves auth state and redirects accordingly.
 * This screen is shown only briefly while the splash screen is visible.
 *
 * Routing matrix:
 *   user set                      → /(tabs)
 *   no user, intro not yet seen   → /(intro)/welcome
 *   no user, intro already seen   → /(auth)/login
 */
export default function Index() {
  const { user, loading } = useAuth();
  const colors = useColors();
  const [introSeen, setIntroSeen] = useState<boolean | null>(null);

  useEffect(() => {
    getHasSeenIntro().then(setIntroSeen);
  }, []);

  const ready = !loading && introSeen !== null;

  useEffect(() => {
    if (ready) {
      SplashScreen.hideAsync();
    }
  }, [ready]);

  if (!ready) {
    return (
      <View style={{ flex: 1, backgroundColor: colors.background, justifyContent: 'center', alignItems: 'center' }}>
        <ActivityIndicator color={colors.primary} size="large" />
      </View>
    );
  }

  if (user) {
    return <Redirect href="/(tabs)" />;
  }

  if (!introSeen) {
    return <Redirect href="/(intro)/welcome" />;
  }

  return <Redirect href="/(auth)/login" />;
}
