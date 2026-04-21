/**
 * Profile screen with push notifications, notification time, and account management.
 */
import { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  Pressable,
  ScrollView,
  Switch,
  Platform,
  Modal,
  ActivityIndicator,
  Linking,
} from 'react-native';
import type { AccessibilityState } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import DateTimePicker, { DateTimePickerEvent } from '@react-native-community/datetimepicker';
import * as Clipboard from 'expo-clipboard';
import * as Haptics from 'expo-haptics';
import { Ionicons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';
import Constants from 'expo-constants';
import { useAuth } from '../../contexts/AuthContext';
import { changeLanguage } from '../../i18n';
import { useColors } from '../../hooks/useColors';
import { useTheme, type ThemePreference } from '../../contexts/ThemeContext';
import { useAlertDialog } from '../../components/AlertDialog';
import { apiRequest } from '../../lib/api';
import { SUPPORT_EMAIL } from '../../lib/constants';
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
  const { showAlert } = useAlertDialog();
  const { preference: themePreference, setPreference: setThemePreference } = useTheme();

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
          showAlert(
            t('common.error'),
            Platform.OS === 'ios'
              ? t('profile.pushPermissionDeniedIOS')
              : t('profile.pushPermissionDenied'),
          );
          setPushToggling(false);
          return;
        }
        await subscribeToExpoNotifications(token);
        setPushEnabled(true);
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        showAlert(t('profile.notificationsEnabled'), t('profile.notificationsEnabledHint'));
      } else {
        // Disable: unsubscribe from backend
        await unsubscribeFromExpoNotifications();
        setPushEnabled(false);
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        showAlert(t('profile.notificationsDisabled'), t('profile.notificationsDisabledHint'));
      }
    } catch (error: any) {
      console.error('[Push] Toggle error:', error);
      showAlert(t('common.error'), error.message);
    } finally {
      setPushToggling(false);
    }
  }, [t]);

  const handleTestNotification = useCallback(async () => {
    setTestSending(true);
    try {
      await sendLocalTestNotification();
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      showAlert(t('profile.testSent'), t('profile.testSentHint'));
    } catch (error: any) {
      showAlert(t('common.error'), error.message);
    } finally {
      setTestSending(false);
    }
  }, [t]);

  // Time picker helpers
  const currentTime = user?.notification_time ?? '09:00';
  const [hours, minutes] = currentTime.split(':').map(Number);
  const timeDate = new Date();
  timeDate.setHours(hours, minutes, 0, 0);

  const saveNotificationTime = useCallback(async (selectedDate: Date) => {
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
      showAlert(t('profile.settingsSaved'), t('profile.notificationTimeUpdated'));
    } catch (error: any) {
      showAlert(t('common.error'), error.message);
    } finally {
      setSavingTime(false);
    }
  }, [currentTime, t, updateUser]);

  const handleTimeChange = useCallback((event: DateTimePickerEvent, selectedDate?: Date) => {
    if (Platform.OS === 'android') {
      setShowTimePicker(false);
    }
    if (event.type === 'dismissed') return;
    if (!selectedDate) return;

    void saveNotificationTime(selectedDate);
  }, [saveNotificationTime]);

  const [iosTempTime, setIosTempTime] = useState(timeDate);

  const handleTimeIOSCancel = useCallback(() => {
    setShowTimePicker(false);
  }, []);

  const handleTimeIOSSave = useCallback(() => {
    setShowTimePicker(false);
    void saveNotificationTime(iosTempTime);
  }, [iosTempTime, saveNotificationTime]);

  const handleCopyKey = async () => {
    if (!user?.recovery_key) return;
    await Clipboard.setStringAsync(user.recovery_key);
    setCopied(true);
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleRegenerateKey = () => {
    showAlert(
      t('profile.generateNewKey'),
      'This will replace your current recovery key. Make sure to save the new one.',
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
              showAlert(t('common.error'), error.message);
            } finally {
              setRegenerating(false);
            }
          },
        },
      ],
    );
  };

  const handleSignOut = () => {
    showAlert(
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

  const currentLanguageName = i18n.language === 'ru' ? t('profile.languageRussian') : t('profile.languageEnglish');
  const [showLanguageModal, setShowLanguageModal] = useState(false);

  const handleSelectLanguage = (lng: string) => {
    changeLanguage(lng);
    setShowLanguageModal(false);
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
  };

  const [showThemeModal, setShowThemeModal] = useState(false);
  const themeLabel =
    themePreference === 'light'
      ? t('profile.themeLight')
      : themePreference === 'dark'
        ? t('profile.themeDark')
        : t('profile.themeAuto');

  const handleSelectTheme = (pref: ThemePreference) => {
    setThemePreference(pref);
    setShowThemeModal(false);
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
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
    accessibilityLabel,
    accessibilityState,
  }: {
    icon: string;
    label: string;
    value?: string;
    onPress?: () => void;
    danger?: boolean;
    right?: React.ReactNode;
    accessibilityLabel?: string;
    accessibilityState?: AccessibilityState;
  }) => (
    <Pressable
      onPress={onPress}
      disabled={!onPress}
      accessibilityRole={onPress || accessibilityLabel || accessibilityState ? 'button' : undefined}
      accessibilityLabel={accessibilityLabel}
      accessibilityState={accessibilityState}
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
            accessibilityLabel={t('profile.notificationTime')}
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
                accessibilityLabel={t('profile.testNotification')}
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
            value={currentLanguageName}
            accessibilityLabel={t('a11y.chooseLanguage')}
            onPress={() => setShowLanguageModal(true)}
          />
        </SectionCard>

        {/* Theme */}
        <SectionCard>
          <Row
            icon="color-palette-outline"
            label={t('profile.theme')}
            value={themeLabel}
            accessibilityLabel={t('a11y.chooseTheme')}
            onPress={() => setShowThemeModal(true)}
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
                accessibilityRole="button"
                accessibilityLabel={keyVisible ? t('profile.hide') : t('profile.show')}
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
              accessibilityRole="button"
              accessibilityLabel={t('profile.generateNewKey')}
              accessibilityState={{ disabled: regenerating, busy: regenerating }}
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

        {/* About & Privacy */}
        <SectionCard>
          <View style={{ padding: 16, gap: 12 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
              <Ionicons name="lock-closed" size={20} color={colors.primary} />
              <Text style={{ fontSize: 15, fontWeight: '600', color: colors.foreground, flex: 1 }}>
                {t('privacy.aboutTitle')}
              </Text>
            </View>

            <Text style={{ fontSize: 13, color: colors.mutedForeground, lineHeight: 19 }}>
              {t('privacy.aboutBody')}
            </Text>

            <View style={{ height: 1, backgroundColor: colors.border, marginVertical: 4 }} />

            <Text style={{ fontSize: 14, fontWeight: '600', color: colors.foreground }}>
              {t('privacy.bugTitle')}
            </Text>
            <Text style={{ fontSize: 13, color: colors.mutedForeground, lineHeight: 19 }}>
              {t('privacy.bugBody')}
            </Text>
            <Pressable
              onPress={() => Linking.openURL(`mailto:${SUPPORT_EMAIL}`)}
              accessibilityRole="link"
              accessibilityLabel={SUPPORT_EMAIL}
              hitSlop={8}
            >
              <Text style={{
                fontSize: 13,
                color: colors.primary,
                textDecorationLine: 'underline',
              }}>
                {SUPPORT_EMAIL}
              </Text>
            </Pressable>
          </View>
        </SectionCard>

        {/* Sign out */}
        <SectionCard>
          <Pressable
            onPress={handleSignOut}
            disabled={signingOut}
            accessibilityRole="button"
            accessibilityLabel={t('a11y.signOut')}
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

        <Text style={{ textAlign: 'center', fontSize: 12, color: colors.mutedForeground, marginTop: 8 }}>
          GreenThumb v{Constants.expoConfig?.version ?? '1.0.0'}
        </Text>

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
        <Modal
          visible={showTimePicker}
          transparent
          animationType="slide"
          onRequestClose={handleTimeIOSCancel}
        >
          <Pressable
            style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.45)' }}
            onPress={handleTimeIOSCancel}
          />
          <View style={{ backgroundColor: colors.card, paddingBottom: 32 }}>
            <View style={{
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'space-between',
              paddingHorizontal: 20,
              paddingTop: 14,
              paddingBottom: 4,
            }}>
              <Pressable onPress={handleTimeIOSCancel}>
                <Text style={{ color: colors.mutedForeground, fontSize: 16, fontWeight: '600' }}>
                  {t('common.cancel')}
                </Text>
              </Pressable>
              <Pressable onPress={handleTimeIOSSave}>
                <Text style={{ color: colors.primary, fontSize: 16, fontWeight: '600' }}>
                  {t('common.save')}
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

      {/* Language picker modal */}
      <Modal
        visible={showLanguageModal}
        transparent
        animationType="fade"
        onRequestClose={() => setShowLanguageModal(false)}
      >
        <Pressable
          style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'center', alignItems: 'center' }}
          onPress={() => setShowLanguageModal(false)}
        >
          <Pressable style={{
            backgroundColor: colors.card,
            borderColor: colors.cardBorder,
            borderWidth: 1,
            borderRadius: 16,
            width: '80%',
            padding: 24,
            gap: 12,
          }}>
            <Text style={{ fontSize: 18, fontWeight: '700', color: colors.foreground, textAlign: 'center', marginBottom: 4 }}>
              {t('profile.chooseLanguage')}
            </Text>
            {[
              { code: 'en', label: 'English' },
              { code: 'ru', label: 'Русский' },
            ].map((lang) => (
              <Pressable
                key={lang.code}
                onPress={() => handleSelectLanguage(lang.code)}
                style={({ pressed }) => ({
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  padding: 14,
                  borderRadius: 10,
                  backgroundColor: i18n.language === lang.code ? colors.primary + '20' : colors.muted,
                  opacity: pressed ? 0.7 : 1,
                })}
              >
                <Text style={{ fontSize: 16, color: colors.foreground, fontWeight: i18n.language === lang.code ? '600' : '400' }}>
                  {lang.label}
                </Text>
                {i18n.language === lang.code && (
                  <Ionicons name="checkmark-circle" size={20} color={colors.primary} />
                )}
              </Pressable>
            ))}
          </Pressable>
        </Pressable>
      </Modal>

      {/* Theme picker modal */}
      <Modal
        visible={showThemeModal}
        transparent
        animationType="fade"
        onRequestClose={() => setShowThemeModal(false)}
      >
        <Pressable
          style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'center', alignItems: 'center' }}
          onPress={() => setShowThemeModal(false)}
        >
          <Pressable style={{
            backgroundColor: colors.card,
            borderColor: colors.cardBorder,
            borderWidth: 1,
            borderRadius: 16,
            width: '80%',
            padding: 24,
            gap: 12,
          }}>
            <Text style={{ fontSize: 18, fontWeight: '700', color: colors.foreground, textAlign: 'center', marginBottom: 4 }}>
              {t('profile.chooseTheme')}
            </Text>
            {([
              { code: 'auto' as ThemePreference, label: t('profile.themeAuto'), icon: 'contrast-outline' as const },
              { code: 'light' as ThemePreference, label: t('profile.themeLight'), icon: 'sunny-outline' as const },
              { code: 'dark' as ThemePreference, label: t('profile.themeDark'), icon: 'moon-outline' as const },
            ]).map((opt) => (
              <Pressable
                key={opt.code}
                onPress={() => handleSelectTheme(opt.code)}
                style={({ pressed }) => ({
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: 12,
                  padding: 14,
                  borderRadius: 10,
                  backgroundColor: themePreference === opt.code ? colors.primary + '20' : colors.muted,
                  opacity: pressed ? 0.7 : 1,
                })}
              >
                <Ionicons name={opt.icon} size={20} color={colors.foreground} />
                <Text style={{
                  flex: 1,
                  fontSize: 16,
                  color: colors.foreground,
                  fontWeight: themePreference === opt.code ? '600' : '400',
                }}>
                  {opt.label}
                </Text>
                {themePreference === opt.code && (
                  <Ionicons name="checkmark-circle" size={20} color={colors.primary} />
                )}
              </Pressable>
            ))}
          </Pressable>
        </Pressable>
      </Modal>
    </SafeAreaView>
  );
}
