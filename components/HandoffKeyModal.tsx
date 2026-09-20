/**
 * One-time "save your recovery key" modal (Stage 0 of the KMP migration).
 *
 * Shown once per app versionCode after the session resolves, for users who
 * are logged in and are already inside the main app (never on top of the
 * auth/intro flow — fresh registrations have the show-key gateway instead).
 * Reminds users that the recovery key is the only credential and cannot be
 * restored if lost.
 */
import { useEffect, useState } from 'react';
import { Modal, Platform, Pressable, Text, View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { useSegments } from 'expo-router';
import * as Clipboard from 'expo-clipboard';
import * as Haptics from 'expo-haptics';
import { Ionicons } from '@expo/vector-icons';
import Constants from 'expo-constants';
import { useAuth } from '../contexts/AuthContext';
import { useColors } from '../hooks/useColors';
import {
  getHandoffModalSeenVersion,
  setHandoffModalSeenVersion,
} from '../lib/storage';

export default function HandoffKeyModal() {
  const { t } = useTranslation();
  const { user, loading } = useAuth();
  const colors = useColors();
  const segments = useSegments();

  const [copied, setCopied] = useState(false);
  const [seenVersion, setSeenVersion] = useState<string | null>(null);
  const [flagLoaded, setFlagLoaded] = useState(false);

  useEffect(() => {
    getHandoffModalSeenVersion()
      .then(setSeenVersion)
      .catch(() => setSeenVersion(null))
      .finally(() => setFlagLoaded(true));
  }, []);

  const versionCode = Constants.expoConfig?.android?.versionCode;
  const versionCodeStr = typeof versionCode === 'number' ? String(versionCode) : null;

  // Only surface inside the main app area — the auth flow has its own
  // show-key gateway and the intro carousel must stay undisturbed.
  const inMainApp =
    segments.length > 0 && segments[0] !== '(auth)' && segments[0] !== '(intro)';

  const visible =
    flagLoaded &&
    !!versionCodeStr &&
    !!user &&
    !loading &&
    inMainApp &&
    seenVersion !== versionCodeStr;

  const handleDismiss = async () => {
    if (versionCodeStr) {
      setSeenVersion(versionCodeStr);
      await setHandoffModalSeenVersion(versionCodeStr);
    }
  };

  const handleCopyKey = async () => {
    if (!user?.recovery_key) return;
    await Clipboard.setStringAsync(user.recovery_key);
    setCopied(true);
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    setTimeout(() => setCopied(false), 2000);
  };

  if (!visible) return null;

  return (
    <Modal transparent animationType="fade" onRequestClose={handleDismiss}>
      <View
        style={{
          flex: 1,
          backgroundColor: 'rgba(0,0,0,0.5)',
          justifyContent: 'center',
          alignItems: 'center',
          padding: 24,
        }}
      >
        <View
          accessible
          accessibilityLabel={t('handoffModal.title')}
          style={{
            backgroundColor: colors.card,
            borderColor: colors.cardBorder,
            borderWidth: 1,
            borderRadius: 16,
            width: '100%',
            maxWidth: 400,
            padding: 24,
            gap: 16,
          }}
        >
          {/* Header */}
          <View style={{ alignItems: 'center', gap: 10 }}>
            <View
              style={{
                width: 56,
                height: 56,
                borderRadius: 28,
                backgroundColor: colors.primary + '22',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Ionicons name="key" size={28} color={colors.primary} />
            </View>
            <Text
              style={{
                fontSize: 20,
                fontWeight: '700',
                color: colors.foreground,
                textAlign: 'center',
              }}
            >
              {t('handoffModal.title')}
            </Text>
            <Text
              style={{
                fontSize: 14,
                color: colors.mutedForeground,
                textAlign: 'center',
                lineHeight: 20,
              }}
            >
              {t('handoffModal.subtitle')}
            </Text>
          </View>

          {/* Key display + copy */}
          <View style={{ gap: 8 }}>
            <Text style={{ fontSize: 14, fontWeight: '500', color: colors.foreground }}>
              {t('handoffModal.keyLabel')}
            </Text>
            <View style={{ flexDirection: 'row', gap: 10, alignItems: 'center' }}>
              <View
                style={{
                  flex: 1,
                  backgroundColor: colors.muted,
                  borderColor: colors.border,
                  borderWidth: 1,
                  borderRadius: 10,
                  padding: 14,
                }}
              >
                <Text
                  selectable
                  style={{
                    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                    fontSize: 13,
                    color: colors.foreground,
                    letterSpacing: 0.5,
                  }}
                >
                  {user?.recovery_key}
                </Text>
              </View>
              <Pressable
                onPress={handleCopyKey}
                accessibilityRole="button"
                accessibilityLabel={t('handoffModal.copy')}
                style={({ pressed }) => ({
                  width: 44,
                  height: 44,
                  borderRadius: 10,
                  borderWidth: 1,
                  borderColor: colors.border,
                  backgroundColor: colors.card,
                  alignItems: 'center',
                  justifyContent: 'center',
                  opacity: pressed ? 0.7 : 1,
                })}
              >
                <Ionicons
                  name={copied ? 'checkmark' : 'copy-outline'}
                  size={20}
                  color={copied ? '#22c55e' : colors.foreground}
                />
              </Pressable>
            </View>
          </View>

          {/* Non-restorability warning */}
          <View
            style={{
              backgroundColor: colors.destructive + '1A',
              borderColor: colors.destructive + '40',
              borderWidth: 1,
              borderRadius: 10,
              padding: 14,
              flexDirection: 'row',
              gap: 12,
            }}
          >
            <Ionicons
              name="warning"
              size={20}
              color={colors.destructive}
              style={{ marginTop: 2 }}
            />
            <Text
              style={{
                flex: 1,
                fontSize: 13,
                color: colors.mutedForeground,
                lineHeight: 19,
              }}
            >
              {t('handoffModal.warning')}
            </Text>
          </View>

          {/* Dismiss */}
          <Pressable
            onPress={handleDismiss}
            accessibilityRole="button"
            accessibilityLabel={t('handoffModal.understood')}
            style={({ pressed }) => ({
              backgroundColor: colors.primary,
              borderRadius: 10,
              paddingVertical: 14,
              alignItems: 'center',
              opacity: pressed ? 0.85 : 1,
            })}
          >
            <Text style={{ color: colors.primaryForeground, fontSize: 16, fontWeight: '600' }}>
              {t('handoffModal.understood')}
            </Text>
          </Pressable>
        </View>
      </View>
    </Modal>
  );
}
