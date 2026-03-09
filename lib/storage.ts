import * as SecureStore from 'expo-secure-store';
import { RECOVERY_KEY_STORE_KEY } from './constants';

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
