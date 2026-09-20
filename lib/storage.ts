import * as SecureStore from 'expo-secure-store';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Localization from 'expo-localization';
import { File, Paths } from 'expo-file-system';
import {
  HANDOFF_MODAL_SEEN_KEY,
  INTRO_SEEN_STORE_KEY,
  LANGUAGE_STORE_KEY,
  LAYOUT_MODE_STORE_KEY,
  RECOVERY_KEY_STORE_KEY,
  THEME_STORE_KEY,
} from './constants';

/**
 * Persists the recovery key in the device's secure encrypted storage.
 * This key is used for auto-login on subsequent app launches.
 */
export async function saveRecoveryKey(key: string): Promise<void> {
  await SecureStore.setItemAsync(RECOVERY_KEY_STORE_KEY, key);
}

/**
 * Retrieves the stored recovery key, or null if not present.
 */
export async function getStoredRecoveryKey(): Promise<string | null> {
  return SecureStore.getItemAsync(RECOVERY_KEY_STORE_KEY);
}

/**
 * Removes the recovery key from secure storage (called on sign-out).
 */
export async function clearRecoveryKey(): Promise<void> {
  await SecureStore.deleteItemAsync(RECOVERY_KEY_STORE_KEY);
}

/**
 * True when the user has finished (or skipped) the first-launch welcome carousel.
 * Missing value or read error is treated as "not seen" so the worst case is a
 * one-time extra intro, never a stuck app.
 */
export async function getHasSeenIntro(): Promise<boolean> {
  try {
    const v = await AsyncStorage.getItem(INTRO_SEEN_STORE_KEY);
    return v === '1';
  } catch {
    return false;
  }
}

export async function setHasSeenIntro(): Promise<void> {
  try {
    await AsyncStorage.setItem(INTRO_SEEN_STORE_KEY, '1');
    // The intro flag lives in the handoff payload — re-sync it (non-fatal).
    await syncHandoffFile();
  } catch {
    // Non-fatal: the user will re-see intro on next launch.
  }
}

// ---------------------------------------------------------------------------
// KMP handoff file (Stage 0 of the KMP migration)
// ---------------------------------------------------------------------------
// The upcoming Kotlin Multiplatform build can neither read expo-secure-store's
// encrypted storage nor AsyncStorage (on Android it's a SQLite database). To
// make the migration seamless, the recovery key and user preferences are
// mirrored to gt-handoff.json in the documents directory (= filesDir on
// Android), which both stacks read without cryptography. The file is deleted
// on sign-out and kept out of cloud backups via android.allowBackup=false.

const HANDOFF_FILE_NAME = 'gt-handoff.json';
const HANDOFF_PAYLOAD_VERSION = 1;
const SUPPORTED_LANGUAGES = ['en', 'ru'];

interface HandoffPayload {
  v: number;
  recovery_key: string;
  language: string;
  theme: string;
  layout_mode: string;
  intro_seen: boolean;
}

/**
 * Collects the current user preferences from AsyncStorage, falling back to the
 * same defaults the app itself uses when a preference was never set.
 */
async function buildHandoffPayload(recoveryKey: string): Promise<HandoffPayload> {
  const [language, layoutMode, theme, introSeen] = await Promise.all([
    AsyncStorage.getItem(LANGUAGE_STORE_KEY),
    AsyncStorage.getItem(LAYOUT_MODE_STORE_KEY),
    AsyncStorage.getItem(THEME_STORE_KEY),
    AsyncStorage.getItem(INTRO_SEEN_STORE_KEY),
  ]);

  // Language default mirrors i18n/index.ts: device language when the user
  // never picked one explicitly, else the i18next fallback ('en').
  const deviceLanguage = Localization.getLocales()[0]?.languageCode ?? 'en';
  const defaultLanguage = SUPPORTED_LANGUAGES.includes(deviceLanguage)
    ? deviceLanguage
    : 'en';

  return {
    v: HANDOFF_PAYLOAD_VERSION,
    recovery_key: recoveryKey,
    language: language && SUPPORTED_LANGUAGES.includes(language) ? language : defaultLanguage,
    theme: theme ?? 'auto',
    layout_mode: layoutMode ?? 'list',
    intro_seen: introSeen === '1',
  };
}

/**
 * Writes the handoff file if a recovery key is stored; deletes any stale file
 * when there is no key (they only ever exist together). Never throws — a
 * handoff failure must not break auth or settings flows.
 */
export async function syncHandoffFile(): Promise<void> {
  try {
    const key = await getStoredRecoveryKey();
    if (!key) {
      deleteHandoffFile();
      return;
    }
    const payload = await buildHandoffPayload(key);
    // Native write creates the file if it doesn't exist yet.
    const file = new File(Paths.document, HANDOFF_FILE_NAME);
    file.write(JSON.stringify(payload));
  } catch (err) {
    console.warn('[storage] Handoff sync failed (non-fatal):', err);
  }
}

/**
 * Removes the handoff file, if present. Never throws.
 * MUST be called next to clearRecoveryKey() — the KMP build treats a leftover
 * file as a login credential and would silently sign the user back in.
 */
export async function clearHandoffFile(): Promise<void> {
  try {
    deleteHandoffFile();
  } catch (err) {
    console.warn('[storage] Failed to clear handoff file (non-fatal):', err);
  }
}

function deleteHandoffFile(): void {
  const file = new File(Paths.document, HANDOFF_FILE_NAME);
  if (file.exists) file.delete();
}

// ---------------------------------------------------------------------------
// "Save your key" reminder modal flag (Stage 0)
// ---------------------------------------------------------------------------

/**
 * The app versionCode for which the one-time "save your recovery key" modal
 * was last shown, or null when it hasn't been shown yet. Keyed by versionCode
 * so the reminder re-appears once after each version bump.
 */
export async function getHandoffModalSeenVersion(): Promise<string | null> {
  try {
    return await AsyncStorage.getItem(HANDOFF_MODAL_SEEN_KEY);
  } catch {
    return null;
  }
}

export async function setHandoffModalSeenVersion(versionCode: string): Promise<void> {
  try {
    await AsyncStorage.setItem(HANDOFF_MODAL_SEEN_KEY, versionCode);
  } catch {
    // Non-fatal: worst case the modal is shown again on next launch.
  }
}
