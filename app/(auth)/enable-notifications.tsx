import { useCallback, useState } from 'react';
import {
  View,
  Text,
  Pressable,
  ActivityIndicator,
  Platform,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useTranslation } from 'react-i18next';
import * as Haptics from 'expo-haptics';
import { Ionicons } from '@expo/vector-icons';
import { useColors } from '../../hooks/useColors';
import { useAlertDialog } from '../../components/AlertDialog';
import {
  registerForPushNotificationsAsync,
  subscribeToExpoNotifications,
  isExpoGo,
} from '../../lib/notifications';

/**
 * Pre-permission prompt shown once, right after account creation (show-key step).
 * Explains the value of reminders before the system permission dialog fires.
 * Pressing "Later" or a permission denial still lands the user on the dashboard.
 */
export default function EnableNotificationsScreen() {
  const { t } = useTranslation();
  const router = useRouter();
  const colors = useColors();
  const { showAlert } = useAlertDialog();

  const [loading, setLoading] = useState(false);

  const goHome = useCallback(() => {
    router.replace('/(tabs)');
  }, [router]);

  const handleEnable = useCallback(async () => {
    if (isExpoGo) {
      // Push permissions don't work in Expo Go (SDK 53+). Skip gracefully.
      goHome();
      return;
    }
    setLoading(true);
    try {
      const token = await registerForPushNotificationsAsync();
      if (!token) {
        // Permission denied — tell the user how to enable it later, then proceed.
        showAlert(
          t('common.error'),
          Platform.OS === 'ios'
            ? t('profile.pushPermissionDeniedIOS')
            : t('profile.pushPermissionDenied'),
        );
        goHome();
        return;
      }
      await subscribeToExpoNotifications(token);
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      goHome();
    } catch (error: any) {
      console.error('[EnableNotifications] Error:', error);
      showAlert(t('common.error'), error.message);
      goHome();
    } finally {
      setLoading(false);
    }
  }, [goHome, showAlert, t]);

  const handleLater = useCallback(() => {
    Haptics.selectionAsync();
    goHome();
  }, [goHome]);

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
      <View style={{ flex: 1, justifyContent: 'center', padding: 24 }}>
        <View
          style={{
            backgroundColor: colors.card,
            borderColor: colors.cardBorder,
            borderWidth: 1,
            borderRadius: 16,
            padding: 24,
            gap: 20,
          }}
        >
          {/* Icon */}
          <View style={{ alignItems: 'center', gap: 14 }}>
            <View
              style={{
                width: 80,
                height: 80,
                borderRadius: 40,
                backgroundColor: colors.primary + '22',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Ionicons name="notifications" size={40} color={colors.primary} />
            </View>
            <Text
              style={{
                fontSize: 22,
                fontWeight: '700',
                color: colors.foreground,
                textAlign: 'center',
              }}
            >
              {t('enableNotifications.title')}
            </Text>
            <Text
              style={{
                fontSize: 15,
                color: colors.mutedForeground,
                textAlign: 'center',
                lineHeight: 22,
              }}
            >
              {t('enableNotifications.body')}
            </Text>
          </View>

          {/* Primary CTA */}
          <Pressable
            onPress={handleEnable}
            disabled={loading}
            accessibilityRole="button"
            accessibilityLabel={t('enableNotifications.enable')}
            accessibilityState={{ disabled: loading, busy: loading }}
            style={({ pressed }) => ({
              backgroundColor: colors.primary,
              borderRadius: 10,
              paddingVertical: 14,
              alignItems: 'center',
              flexDirection: 'row',
              justifyContent: 'center',
              gap: 8,
              opacity: pressed || loading ? 0.75 : 1,
            })}
          >
            {loading ? <ActivityIndicator color={colors.primaryForeground} size="small" /> : null}
            <Text style={{ color: colors.primaryForeground, fontSize: 16, fontWeight: '600' }}>
              {t('enableNotifications.enable')}
            </Text>
          </Pressable>

          {/* Secondary CTA */}
          <Pressable
            onPress={handleLater}
            disabled={loading}
            accessibilityRole="button"
            accessibilityLabel={t('enableNotifications.later')}
            style={({ pressed }) => ({
              alignItems: 'center',
              paddingVertical: 8,
              opacity: pressed ? 0.6 : 1,
            })}
          >
            <Text style={{ color: colors.mutedForeground, fontSize: 15, fontWeight: '500' }}>
              {t('enableNotifications.later')}
            </Text>
          </Pressable>
        </View>
      </View>
    </SafeAreaView>
  );
}
