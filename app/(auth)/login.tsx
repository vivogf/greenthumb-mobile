import { useState, useEffect } from 'react';
import {
  View,
  Text,
  TextInput,
  Pressable,
  ScrollView,
  KeyboardAvoidingView,
  Platform,
  ActivityIndicator,
  Alert,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useTranslation } from 'react-i18next';
import * as Clipboard from 'expo-clipboard';
import * as Haptics from 'expo-haptics';
import { Ionicons } from '@expo/vector-icons';
import { useAuth } from '../../contexts/AuthContext';
import { useColors } from '../../hooks/useColors';

type LoginMode = 'choose' | 'create' | 'login' | 'show-key';

export default function LoginScreen() {
  const { t } = useTranslation();
  const router = useRouter();
  const { user, createAnonymousAccount, signInWithRecoveryKey } = useAuth();
  const colors = useColors();

  const [mode, setMode] = useState<LoginMode>('choose');
  const [name, setName] = useState('');
  const [recoveryKey, setRecoveryKey] = useState('');
  const [newUserKey, setNewUserKey] = useState('');
  const [copied, setCopied] = useState(false);
  const [loading, setLoading] = useState(false);

  // If user becomes authenticated while NOT in show-key mode, go to app
  useEffect(() => {
    if (user && mode !== 'show-key') {
      router.replace('/(tabs)');
    }
  }, [user, mode]);

  // ---------------------------------------------------------------------------
  // Actions
  // ---------------------------------------------------------------------------

  const handleCreateAccount = async () => {
    setLoading(true);
    try {
      const newUser = await createAnonymousAccount(name.trim() || undefined);
      setNewUserKey(newUser.recovery_key);
      setMode('show-key');
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    } catch (error: any) {
      Alert.alert(t('common.error'), error.message);
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
    } finally {
      setLoading(false);
    }
  };

  const handleLogin = async () => {
    if (!recoveryKey.trim()) return;
    setLoading(true);
    try {
      await signInWithRecoveryKey(recoveryKey.trim());
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      // useEffect above will navigate to /(tabs) when user is set
    } catch (error: any) {
      Alert.alert(t('common.error'), error.message);
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
      setLoading(false);
    }
  };

  const copyKey = async () => {
    await Clipboard.setStringAsync(newUserKey);
    setCopied(true);
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleContinue = () => {
    router.replace('/(tabs)');
  };

  // ---------------------------------------------------------------------------
  // Shared styles (via constants — avoids re-creation on each render)
  // ---------------------------------------------------------------------------

  const inputStyle = {
    backgroundColor: colors.input,
    borderColor: colors.border,
    color: colors.foreground,
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 16,
  };

  const primaryButtonStyle = {
    backgroundColor: colors.primary,
    borderRadius: 10,
    paddingVertical: 14,
    alignItems: 'center' as const,
    flexDirection: 'row' as const,
    justifyContent: 'center' as const,
    gap: 8,
  };

  const outlineButtonStyle = {
    backgroundColor: 'transparent',
    borderColor: colors.border,
    borderWidth: 1,
    borderRadius: 10,
    paddingVertical: 14,
    alignItems: 'center' as const,
    flexDirection: 'row' as const,
    justifyContent: 'center' as const,
    gap: 8,
  };

  const cardStyle = {
    backgroundColor: colors.card,
    borderColor: colors.cardBorder,
    borderWidth: 1,
    borderRadius: 16,
    padding: 24,
    gap: 20,
  };

  const Container = ({ children }: { children: React.ReactNode }) => (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        style={{ flex: 1 }}
      >
        <ScrollView
          contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', padding: 20 }}
          keyboardShouldPersistTaps="handled"
        >
          {children}
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );

  // ---------------------------------------------------------------------------
  // Mode: choose (initial screen)
  // ---------------------------------------------------------------------------

  if (mode === 'choose') {
    return (
      <Container>
        <View style={cardStyle}>
          {/* Logo */}
          <View style={{ alignItems: 'center', gap: 12 }}>
            <View style={{
              width: 72,
              height: 72,
              borderRadius: 36,
              backgroundColor: colors.primary + '22',
              alignItems: 'center',
              justifyContent: 'center',
            }}>
              <Ionicons name="leaf" size={36} color={colors.primary} />
            </View>
            <Text style={{ fontSize: 28, fontWeight: '600', color: colors.foreground, textAlign: 'center' }}>
              {t('login.title')}
            </Text>
            <Text style={{ fontSize: 15, color: colors.mutedForeground, textAlign: 'center', lineHeight: 22 }}>
              {t('login.subtitle')}
            </Text>
          </View>

          {/* Buttons */}
          <View style={{ gap: 12 }}>
            <Pressable
              style={{ ...primaryButtonStyle }}
              onPress={() => setMode('create')}
            >
              <Ionicons name="person-add-outline" size={18} color={colors.primaryForeground} />
              <Text style={{ color: colors.primaryForeground, fontSize: 16, fontWeight: '600' }}>
                {t('login.createAccount')}
              </Text>
            </Pressable>

            <Pressable
              style={{ ...outlineButtonStyle }}
              onPress={() => setMode('login')}
            >
              <Ionicons name="key-outline" size={18} color={colors.foreground} />
              <Text style={{ color: colors.foreground, fontSize: 16, fontWeight: '500' }}>
                {t('login.haveKey')}
              </Text>
            </Pressable>
          </View>
        </View>
      </Container>
    );
  }

  // ---------------------------------------------------------------------------
  // Mode: create
  // ---------------------------------------------------------------------------

  if (mode === 'create') {
    return (
      <Container>
        <View style={cardStyle}>
          {/* Back button */}
          <Pressable
            onPress={() => setMode('choose')}
            style={{ alignSelf: 'flex-start', padding: 4 }}
            hitSlop={12}
          >
            <Ionicons name="arrow-back" size={22} color={colors.mutedForeground} />
          </Pressable>

          {/* Header */}
          <View style={{ alignItems: 'center', gap: 10 }}>
            <View style={{
              width: 64,
              height: 64,
              borderRadius: 32,
              backgroundColor: colors.primary + '22',
              alignItems: 'center',
              justifyContent: 'center',
            }}>
              <Ionicons name="leaf" size={30} color={colors.primary} />
            </View>
            <Text style={{ fontSize: 22, fontWeight: '600', color: colors.foreground }}>
              {t('login.createTitle')}
            </Text>
            <Text style={{ fontSize: 14, color: colors.mutedForeground, textAlign: 'center' }}>
              {t('login.createSubtitle')}
            </Text>
          </View>

          {/* Name input */}
          <View style={{ gap: 8 }}>
            <Text style={{ fontSize: 14, fontWeight: '500', color: colors.foreground }}>
              {t('login.nameLabel')}
            </Text>
            <TextInput
              style={inputStyle}
              placeholder={t('login.namePlaceholder')}
              placeholderTextColor={colors.mutedForeground}
              value={name}
              onChangeText={setName}
              autoComplete="name"
              returnKeyType="done"
            />
          </View>

          {/* Warning box */}
          <View style={{
            backgroundColor: colors.amberBg,
            borderColor: colors.amberBorder,
            borderWidth: 1,
            borderRadius: 10,
            padding: 14,
            flexDirection: 'row',
            gap: 12,
          }}>
            <Ionicons name="warning-outline" size={20} color={colors.amber} style={{ marginTop: 2 }} />
            <View style={{ flex: 1, gap: 4 }}>
              <Text style={{ fontSize: 14, fontWeight: '600', color: colors.amber }}>
                {t('login.importantTitle')}
              </Text>
              <Text style={{ fontSize: 13, color: colors.mutedForeground, lineHeight: 19 }}>
                {t('login.importantText')}{' '}
                <Text style={{ fontWeight: '600', color: colors.foreground }}>
                  {t('login.importantSaveIt')}
                </Text>
                {t('login.importantNoRestore')}
              </Text>
            </View>
          </View>

          {/* Create button */}
          <Pressable
            style={{ ...primaryButtonStyle, opacity: loading ? 0.75 : 1 }}
            onPress={handleCreateAccount}
            disabled={loading}
          >
            {loading ? (
              <ActivityIndicator color={colors.primaryForeground} size="small" />
            ) : null}
            <Text style={{ color: colors.primaryForeground, fontSize: 16, fontWeight: '600' }}>
              {loading ? t('login.creating') : t('login.create')}
            </Text>
          </Pressable>

          {/* Back link */}
          <Pressable onPress={() => setMode('choose')} style={{ alignItems: 'center' }}>
            <Text style={{ fontSize: 14, color: colors.mutedForeground }}>
              {t('login.back')}
            </Text>
          </Pressable>
        </View>
      </Container>
    );
  }

  // ---------------------------------------------------------------------------
  // Mode: show-key
  // ---------------------------------------------------------------------------

  if (mode === 'show-key') {
    return (
      <Container>
        <View style={cardStyle}>
          {/* Success icon */}
          <View style={{ alignItems: 'center', gap: 12 }}>
            <View style={{
              width: 64,
              height: 64,
              borderRadius: 32,
              backgroundColor: '#22c55e22',
              alignItems: 'center',
              justifyContent: 'center',
            }}>
              <Ionicons name="checkmark-circle" size={36} color="#22c55e" />
            </View>
            <Text style={{ fontSize: 22, fontWeight: '600', color: colors.foreground }}>
              {t('login.accountCreated')}
            </Text>
            <Text style={{ fontSize: 14, color: colors.mutedForeground, textAlign: 'center' }}>
              {t('login.saveKeyNow')}
            </Text>
          </View>

          {/* Danger warning */}
          <View style={{
            backgroundColor: colors.destructive + '1A',
            borderColor: colors.destructive + '40',
            borderWidth: 1,
            borderRadius: 10,
            padding: 14,
            flexDirection: 'row',
            gap: 12,
          }}>
            <Ionicons name="warning" size={20} color={colors.destructive} style={{ marginTop: 2 }} />
            <View style={{ flex: 1, gap: 4 }}>
              <Text style={{ fontSize: 14, fontWeight: '600', color: colors.destructive }}>
                {t('login.saveKeyWarningTitle')}
              </Text>
              <Text style={{ fontSize: 13, color: colors.mutedForeground, lineHeight: 19 }}>
                {t('login.saveKeyWarningText')}{' '}
                <Text style={{ fontWeight: '600', color: colors.foreground }}>
                  {t('login.saveKeyWarningOnly')}
                </Text>{' '}
                {t('login.saveKeyWarningAccess')}{' '}
                <Text style={{ fontWeight: '600', color: colors.foreground }}>
                  {t('login.saveKeyWarningLost')}
                </Text>
                .
              </Text>
            </View>
          </View>

          {/* Recovery key display */}
          <View style={{ gap: 8 }}>
            <Text style={{ fontSize: 14, fontWeight: '500', color: colors.foreground }}>
              {t('login.yourRecoveryKey')}
            </Text>
            <View style={{ flexDirection: 'row', gap: 10, alignItems: 'center' }}>
              <View style={{
                flex: 1,
                backgroundColor: colors.muted,
                borderColor: colors.border,
                borderWidth: 1,
                borderRadius: 10,
                padding: 14,
              }}>
                <Text style={{
                  fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                  fontSize: 13,
                  color: colors.foreground,
                  letterSpacing: 0.5,
                }}>
                  {newUserKey}
                </Text>
              </View>
              <Pressable
                onPress={copyKey}
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

          {/* Continue button */}
          <Pressable
            style={{ ...primaryButtonStyle }}
            onPress={handleContinue}
          >
            <Text style={{ color: colors.primaryForeground, fontSize: 16, fontWeight: '600' }}>
              {t('login.keySaved')}
            </Text>
          </Pressable>
        </View>
      </Container>
    );
  }

  // ---------------------------------------------------------------------------
  // Mode: login (sign in with recovery key)
  // ---------------------------------------------------------------------------

  return (
    <Container>
      <View style={cardStyle}>
        {/* Back button */}
        <Pressable
          onPress={() => setMode('choose')}
          style={{ alignSelf: 'flex-start', padding: 4 }}
          hitSlop={12}
        >
          <Ionicons name="arrow-back" size={22} color={colors.mutedForeground} />
        </Pressable>

        {/* Header */}
        <View style={{ alignItems: 'center', gap: 10 }}>
          <View style={{
            width: 64,
            height: 64,
            borderRadius: 32,
            backgroundColor: colors.primary + '22',
            alignItems: 'center',
            justifyContent: 'center',
          }}>
            <Ionicons name="key" size={30} color={colors.primary} />
          </View>
          <Text style={{ fontSize: 22, fontWeight: '600', color: colors.foreground }}>
            {t('login.welcomeBack')}
          </Text>
          <Text style={{ fontSize: 14, color: colors.mutedForeground, textAlign: 'center' }}>
            {t('login.enterKey')}
          </Text>
        </View>

        {/* Recovery key input */}
        <View style={{ gap: 8 }}>
          <Text style={{ fontSize: 14, fontWeight: '500', color: colors.foreground }}>
            {t('login.recoveryKeyLabel')}
          </Text>
          <TextInput
            style={[inputStyle, {
              fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
              fontSize: 14,
            }]}
            placeholder={t('login.recoveryKeyPlaceholder')}
            placeholderTextColor={colors.mutedForeground}
            value={recoveryKey}
            onChangeText={setRecoveryKey}
            autoCapitalize="none"
            autoCorrect={false}
            autoComplete="off"
            returnKeyType="done"
            onSubmitEditing={handleLogin}
          />
        </View>

        {/* Sign in button */}
        <Pressable
          style={{ ...primaryButtonStyle, opacity: (loading || !recoveryKey.trim()) ? 0.65 : 1 }}
          onPress={handleLogin}
          disabled={loading || !recoveryKey.trim()}
        >
          {loading ? (
            <ActivityIndicator color={colors.primaryForeground} size="small" />
          ) : null}
          <Text style={{ color: colors.primaryForeground, fontSize: 16, fontWeight: '600' }}>
            {loading ? t('login.signingIn') : t('login.signIn')}
          </Text>
        </Pressable>

        {/* Back link */}
        <Pressable onPress={() => setMode('choose')} style={{ alignItems: 'center' }}>
          <Text style={{ fontSize: 14, color: colors.mutedForeground }}>
            {t('login.back')}
          </Text>
        </Pressable>
      </View>
    </Container>
  );
}
