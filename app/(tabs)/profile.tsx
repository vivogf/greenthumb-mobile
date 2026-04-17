/**
 * Profile screen with push notifications, notification time, and account management.
 */
import { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  Pressable,
  ScrollView,
  Alert,
  Switch,
  Platform,
  Modal,
  ActivityIndicator,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import DateTimePicker, { DateTimePickerEvent } from '@react-native-community/datetimepicker';
import * as Clipboard from 'expo-clipboard';
import * as Haptics from 'expo-haptics';
import { Ionicons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { useColors } from '../../hooks/useColors';
import { apiRequest, TimeoutError, NetworkError } from '../../lib/api';
import { changeLanguage } from '../../i18n/index';
import {
  registerForPushNotificationsAsync,
  subscribeToExpoNotifications,
  unsubscribeFromExpoNotifications,
  checkExpoSubscription,
  sendLocalTestNotification,
  isExpoGo,
} from '../../lib/notifications';

export default function ProfileScreen() {
  const { t, i18n } = useTranslation();
  const { user, signOut, regenerateRecoveryKey, updateUser } = useAuth();
  const colors = useColors();

  const currentLanguage = i18n.language?.startsWith('ru') ? 'ru' : 'en';

  const handleChangeLanguage = useCallback(() => {
    Alert.alert(
      t('profile.language'),
      undefined,
      [
        { text: 'English', onPress: () => void changeLanguage('en') },
        { text: 'Русский', onPress: () => void changeLanguage('ru') },
        { text: t('common.cancel'), style: 'cancel' },
      ],
    );
  }, [t]);

  const [keyVisible, setKeyVisible] = useState(false);
  const [copied, setCopied] = useState(false);
  const [regenerating, setRegenerating] = useState(false);
  const [signingOut, setSigningOut] = useState(false);

  // Push notifications state
  const [pushEnabled, setPushEnabled] = useState(false);
  const [pushLoading, setPushLoading] = useState(true);
  const [pushToggling, setPushToggling] = useState(false);
  const [testSending, setTestSending] = useState(false);

  // Notification time state
  const [showTimePicker, setShowTimePicker] = useState(false);
  const [savingTime, setSavingTime] = useState(false);

  // Check subscription status on mount
  useEffect(() => {
    if (isExpoGo) {
      setPushLoading(false);
      return;
    }
    if (user) {
      checkExpoSubscription()
        .then(setPushEnabled)
        .catch(() => setPushEnabled(false))
        .finally(() => setPushLoading(false));
    } else {
      setPushLoading(false);
    }
  }, [user]);

  const handleTogglePush = useCallback(async (value: boolean) => {
    setPushToggling(true);
    try {
      if (value) {
        // Enable: request permissions → get token → subscribe
        const token = await registerForPushNotificationsAsync();
        if (!token) {
          Alert.alert(
            t('common.error'),
            Platform.OS === 'ios'
              ? t('common.notificationPermissionDeniedIOS')
              : t('common.notificationPermissionDeniedAndroid'),
          );
          setPushToggling(false);
          return;
        }
        await subscribeToExpoNotifications(token);
        setPushEnabled(true);
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        Alert.alert(t('profile.notificationsEnabled'), t('profile.notificationsEnabledHint'));
      } else {
        // Disable: unsubscribe from backend
        await unsubscribeFromExpoNotifications();
        setPushEnabled(false);
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        Alert.alert(t('profile.notificationsDisabled'), t('profile.notificationsDisabledHint'));
      }
    } catch (error: any) {
      console.error('[Push] Toggle error:', error);
      const msg =
        error instanceof TimeoutError
          ? t('errors.timeout')
          : error instanceof NetworkError
            ? t('errors.network')
            : error?.message || t('errors.unknown');
      Alert.alert(t('common.error'), msg);
    } finally {
      setPushToggling(false);
    }
  }, [t]);

  const handleTestNotification = useCallback(async () => {
    setTestSending(true);
    try {
      await sendLocalTestNotification();
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      Alert.alert(t('profile.testSent'), t('profile.testSentHint'));
    } catch (error: any) {
      Alert.alert(t('common.error'), error.message);
    } finally {
      setTestSending(false);
    }
  }, [t]);

  // Time picker helpers
  const currentTime = user?.notification_time ?? '09:00';
  const [hours, minutes] = currentTime.split(':').map(Number);
  const timeDate = new Date();
  timeDate.setHours(hours, minutes, 0, 0);

  const handleTimeChange = useCallback(async (event: DateTimePickerEvent, selectedDate?: Date) => {
    if (Platform.OS === 'android') {
      setShowTimePicker(false);
    }
    if (event.type === 'dismissed') return;
    if (!selectedDate) return;

    const h = String(selectedDate.getHours()).padStart(2, '0');
    const m = String(selectedDate.getMinutes()).padStart(2, '0');
    const newTime = `${h}:${m}`;

    if (newTime === currentTime) return;

    setSavingTime(true);
    try {
      const res = await apiRequest('PATCH', '/api/auth/update-notification-time', {
        notification_time: newTime,
      });
      const data = await res.json();
      updateUser(data.user);
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      Alert.alert(t('profile.settingsSaved'), t('profile.notificationTimeUpdated'));
    } catch (error: any) {
      Alert.alert(t('common.error'), error.message);
    } finally {
      setSavingTime(false);
    }
  }, [currentTime, t, updateUser]);

  const handleTimeIOSDone = useCallback((selectedDate: Date) => {
    setShowTimePicker(false);
    // Trigger the same save logic
    const h = String(selectedDate.getHours()).padStart(2, '0');
    const m = String(selectedDate.getMinutes()).padStart(2, '0');
    const newTime = `${h}:${m}`;

    if (newTime === currentTime) return;

    setSavingTime(true);
    apiRequest('PATCH', '/api/auth/update-notification-time', {
      notification_time: newTime,
    })
      .then(res => res.json())
      .then(data => {
        updateUser(data.user);
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        Alert.alert(t('profile.settingsSaved'), t('profile.notificationTimeUpdated'));
      })
      .catch((error: any) => Alert.alert(t('common.error'), error.message))
      .finally(() => setSavingTime(false));
  }, [currentTime, t, updateUser]);

  const [iosTempTime, setIosTempTime] = useState(timeDate);

  const handleCopyKey = async () => {
    if (!user?.recovery_key) return;
    await Clipboard.setStringAsync(user.recovery_key);
    setCopied(true);
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleRegenerateKey = () => {
    Alert.alert(
      t('profile.generateNewKey'),
      t('errors.confirmRegenerate'),
      [
        { text: t('common.cancel'), style: 'cancel' },
        {
          text: t('profile.generateNewKey'),
          style: 'destructive',
          onPress: async () => {
            setRegenerating(true);
            try {
              await regenerateRecoveryKey();
              setKeyVisible(true);
              Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
            } catch (error: any) {
              // AuthContext throws i18n keys (e.g., "errors.regenerateFailed") as Error.message
              const msg = error?.message?.startsWith('errors.')
                ? t(error.message)
                : (error?.message || t('errors.unknown'));
              Alert.alert(t('common.error'), msg);
            } finally {
              setRegenerating(false);
            }
          },
        },
      ],
    );
  };

  const handleSignOut = () => {
    Alert.alert(
      t('profile.signOutConfirm'),
      t('profile.signOutWarning'),
      [
        { text: t('common.cancel'), style: 'cancel' },
        {
          text: t('profile.signOut'),
          style: 'destructive',
          onPress: async () => {
            setSigningOut(true);
            await signOut();
          },
        },
      ],
    );
  };

  const SectionCard = ({ children }: { children: React.ReactNode }) => (
    <View style={{
      backgroundColor: colors.card,
      borderColor: colors.cardBorder,
      borderWidth: 1,
      borderRadius: 14,
      overflow: 'hidden',
      marginBottom: 12,
    }}>
      {children}
    </View>
  );

  const Row = ({
    icon,
    label,
    value,
    onPress,
    danger,
    right,
  }: {
    icon: string;
    label: string;
    value?: string;
    onPress?: () => void;
    danger?: boolean;
    right?: React.ReactNode;
  }) => (
    <Pressable
      onPress={onPress}
      disabled={!onPress}
      style={({ pressed }) => ({
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 16,
        paddingVertical: 14,
        gap: 14,
        opacity: pressed && onPress ? 0.7 : 1,
      })}
    >
      <Ionicons name={icon as any} size={20} color={danger ? colors.destructive : colors.primary} />
      <View style={{ flex: 1 }}>
        <Text style={{ fontSize: 15, color: danger ? colors.destructive : colors.foreground }}>
          {label}
        </Text>
        {value ? (
          <Text style={{ fontSize: 13, color: colors.mutedForeground, marginTop: 2 }}>
            {value}
          </Text>
        ) : null}
      </View>
      {right ? right : onPress ? (
        <Ionicons name="chevron-forward" size={16} color={colors.mutedForeground} />
      ) : null}
    </Pressable>
  );

  const Divider = () => (
    <View style={{ height: 1, backgroundColor: colors.border, marginLeft: 50 }} />
  );

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
      <ScrollView contentContainerStyle={{ padding: 20, paddingBottom: 40 }}>

        {/* Header */}
        <Text style={{ fontSize: 24, fontWeight: '700', color: colors.foreground, marginBottom: 20 }}>
          {t('profile.title')}
        </Text>

        {/* Account info */}
        <SectionCard>
          <View style={{ padding: 16, gap: 6 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
              <View style={{
                width: 48,
                height: 48,
                borderRadius: 24,
                backgroundColor: colors.primary + '22',
                alignItems: 'center',
                justifyContent: 'center',
              }}>
                <Ionicons name="person" size={24} color={colors.primary} />
              </View>
              <View>
                <Text style={{ fontSize: 17, fontWeight: '600', color: colors.foreground }}>
                  {user?.name || t('profile.anonymous')}
                </Text>
                <Text style={{ fontSize: 13, color: colors.mutedForeground }}>
                  {t('profile.appDescription')}
                </Text>
              </View>
            </View>
          </View>
        </SectionCard>

        {/* Notifications */}
        <SectionCard>
          <Row
            icon="notifications-outline"
            label={t('profile.notifications')}
            value={isExpoGo ? 'Requires development build (not Expo Go)' : t('profile.notificationsHint')}
            right={
              isExpoGo ? undefined :
              pushLoading ? (
                <ActivityIndicator size="small" color={colors.primary} />
              ) : (
                <Switch
                  value={pushEnabled}
                  onValueChange={handleTogglePush}
                  disabled={pushToggling}
                  trackColor={{ false: colors.muted, true: colors.primary + '66' }}
                  thumbColor={pushEnabled ? colors.primary : colors.mutedForeground}
                />
              )
            }
          />
          <Divider />
          <Row
            icon="time-outline"
            label={t('profile.notificationTime')}
            value={savingTime ? t('profile.saving') : currentTime}
            onPress={pushEnabled ? () => {
              setIosTempTime(timeDate);
              setShowTimePicker(true);
            } : undefined}
            right={pushEnabled ? (
              <Ionicons name="chevron-forward" size={16} color={colors.mutedForeground} />
            ) : undefined}
          />
          {pushEnabled && (
            <>
              <Divider />
              <Row
                icon="paper-plane-outline"
                label={t('profile.testNotification')}
                value={testSending ? t('profile.saving') : undefined}
                onPress={testSending ? undefined : handleTestNotification}
              />
            </>
          )}
        </SectionCard>

        {/* Language */}
        <SectionCard>
          <Row
            icon="language-outline"
            label={t('profile.language')}
            value={currentLanguage === 'ru' ? 'Русский' : 'English'}
            onPress={handleChangeLanguage}
          />
        </SectionCard>

        {/* Recovery key */}
        <SectionCard>
          <View style={{ padding: 16, gap: 12 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
              <Ionicons name="key" size={20} color={colors.primary} />
              <Text style={{ fontSize: 15, fontWeight: '600', color: colors.foreground, flex: 1 }}>
                {t('profile.recoveryKey')}
              </Text>
              <Pressable
                onPress={() => setKeyVisible(!keyVisible)}
                style={{ padding: 4 }}
                hitSlop={8}
              >
                <Text style={{ fontSize: 13, color: colors.primary }}>
                  {keyVisible ? t('profile.hide') : t('profile.show')}
                </Text>
              </Pressable>
            </View>

            <Text style={{ fontSize: 13, color: colors.mutedForeground, lineHeight: 18 }}>
              {t('profile.recoveryKeyHint')}
            </Text>

            {keyVisible && (
              <View style={{
                flexDirection: 'row',
                alignItems: 'center',
                backgroundColor: colors.muted,
                borderColor: colors.border,
                borderWidth: 1,
                borderRadius: 10,
                padding: 12,
                gap: 10,
              }}>
                <Text style={{
                  flex: 1,
                  fontFamily: 'monospace',
                  fontSize: 12,
                  color: colors.foreground,
                  letterSpacing: 0.5,
                }}>
                  {user?.recovery_key}
                </Text>
                <Pressable onPress={handleCopyKey} hitSlop={8}>
                  <Ionicons
                    name={copied ? 'checkmark' : 'copy-outline'}
                    size={18}
                    color={copied ? '#22c55e' : colors.primary}
                  />
                </Pressable>
              </View>
            )}

            <Pressable
              onPress={handleRegenerateKey}
              disabled={regenerating}
              style={({ pressed }) => ({
                flexDirection: 'row',
                alignItems: 'center',
                gap: 8,
                opacity: (pressed || regenerating) ? 0.6 : 1,
              })}
            >
              <Ionicons name="refresh" size={16} color={colors.mutedForeground} />
              <Text style={{ fontSize: 13, color: colors.mutedForeground }}>
                {regenerating ? t('profile.generating') : t('profile.generateNewKey')}
              </Text>
            </Pressable>
          </View>
        </SectionCard>

        {/* Sign out */}
        <SectionCard>
          <Pressable
            onPress={handleSignOut}
            disabled={signingOut}
            style={({ pressed }) => ({
              flexDirection: 'row',
              alignItems: 'center',
              paddingHorizontal: 16,
              paddingVertical: 14,
              gap: 14,
              opacity: (pressed || signingOut) ? 0.6 : 1,
            })}
          >
            <Ionicons name="log-out-outline" size={20} color={colors.destructive} />
            <Text style={{ fontSize: 15, color: colors.destructive, fontWeight: '500' }}>
              {t('profile.signOut')}
            </Text>
          </Pressable>
        </SectionCard>

      </ScrollView>

      {/* Android time picker — shows as dialog */}
      {Platform.OS === 'android' && showTimePicker && (
        <DateTimePicker
          value={timeDate}
          mode="time"
          is24Hour={true}
          display="default"
          onChange={handleTimeChange}
        />
      )}

      {/* iOS time picker — spinner in bottom modal */}
      {Platform.OS === 'ios' && (
        <Modal visible={showTimePicker} transparent animationType="slide">
          <Pressable
            style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.45)' }}
            onPress={() => handleTimeIOSDone(iosTempTime)}
          />
          <View style={{ backgroundColor: colors.card, paddingBottom: 32 }}>
            <View style={{
              flexDirection: 'row',
              justifyContent: 'flex-end',
              paddingHorizontal: 20,
              paddingTop: 14,
              paddingBottom: 4,
            }}>
              <Pressable onPress={() => handleTimeIOSDone(iosTempTime)}>
                <Text style={{ color: colors.primary, fontSize: 16, fontWeight: '600' }}>
                  OK
                </Text>
              </Pressable>
            </View>
            <DateTimePicker
              value={iosTempTime}
              mode="time"
              is24Hour={true}
              display="spinner"
              onChange={(_evt, date) => { if (date) setIosTempTime(date); }}
              style={{ height: 200 }}
            />
          </View>
        </Modal>
      )}
    </SafeAreaView>
  );
}
