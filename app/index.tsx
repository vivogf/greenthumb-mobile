import { Redirect } from 'expo-router';
import { View, ActivityIndicator } from 'react-native';
import * as SplashScreen from 'expo-splash-screen';
import { useEffect } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { useColors } from '../hooks/useColors';

/**
 * Initial route — resolves auth state and redirects accordingly.
 * This screen is shown only briefly while the splash screen is visible.
 */
export default function Index() {
  const { user, loading } = useAuth();
  const colors = useColors();

  useEffect(() => {
    if (!loading) {
      // Auth is resolved — hide the native splash screen
      SplashScreen.hideAsync();
    }
  }, [loading]);

  if (loading) {
    // The native splash screen is still showing; render a blank screen here
    return (
      <View style={{ flex: 1, backgroundColor: colors.background, justifyContent: 'center', alignItems: 'center' }}>
        <ActivityIndicator color={colors.primary} size="large" />
      </View>
    );
  }

  if (user) {
    return <Redirect href="/(tabs)" />;
  }

  return <Redirect href="/(auth)/login" />;
}
