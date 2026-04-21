import * as SecureStore from 'expo-secure-store';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { INTRO_SEEN_STORE_KEY, RECOVERY_KEY_STORE_KEY } from './constants';

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
  } catch {
    // Non-fatal: the user will re-see intro on next launch.
  }
}
