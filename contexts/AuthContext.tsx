import { createContext, useContext, useEffect, useState } from 'react';
import { ApiError, baseFetch } from '../lib/api';
import { queryClient, persister } from '../lib/queryClient';
import {
  saveRecoveryKey,
  getStoredRecoveryKey,
  clearRecoveryKey,
  syncHandoffFile,
  clearHandoffFile,
} from '../lib/storage';
import type { User } from '../shared/schema';

/**
 * Best-effort sync of the device's IANA timezone with the backend.
 * Fire-and-forget — a failure here must NEVER break login/auth flow.
 * The backend stores `users.timezone` and the cron uses it to decide when
 * to send the daily care reminder. NULL on the server falls back to
 * Europe/Moscow, so this is purely an upgrade for non-Moscow users.
 */
async function syncTimezoneInBackground(): Promise<void> {
  try {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (!tz) return;
    await baseFetch('/api/auth/update-timezone', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ timezone: tz }),
    });
  } catch (err) {
    // Silent — timezone sync is non-critical.
    console.warn('[Auth] Timezone sync failed (non-fatal):', err);
  }
}

/**
 * Wrap a non-OK Response into an ApiError so callers can branch on
 * error.code instead of regexing error.message. Keeps consistency with the
 * rest of lib/api.ts which already uses ApiError everywhere else.
 */
async function throwAsApiError(res: Response, fallbackMessage: string): Promise<never> {
  let message = fallbackMessage;
  try {
    const data = await res.json();
    if (typeof data?.error === 'string') message = data.error;
  } catch {
    // body wasn't json — keep fallback
  }
  const code: ApiError['code'] =
    res.status === 401 ? 'unauthorized' :
    res.status >= 500 ? 'server' : 'client';
  throw new ApiError(res.status, code, message);
}

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface AuthContextType {
  user: User | null;
  loading: boolean;
  createAnonymousAccount: (name?: string) => Promise<User>;
  signInWithRecoveryKey: (recoveryKey: string) => Promise<void>;
  signOut: () => Promise<void>;
  updateUser: (userData: Partial<User>) => void;
  regenerateRecoveryKey: () => Promise<void>;
}

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

const AuthContext = createContext<AuthContextType | undefined>(undefined);

// ---------------------------------------------------------------------------
// Provider
// ---------------------------------------------------------------------------

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    initSession();
  }, []);

  /**
   * Session initialisation on app start:
   * 1. Try the in-memory cookie (valid if the app was not killed)
   * 2. Only if the backend explicitly says "not authenticated" (401),
   *    try auto-login with the stored recovery key
   * 3. Clear the stored key only if the backend explicitly rejects it (401)
   */
  async function initSession() {
    try {
      // Step 1 — cookie still alive?
      const meRes = await baseFetch('/api/auth/me');

      if (meRes.ok) {
        const data = await meRes.json();
        setUser(data.user);
        // Sync TZ on every cold-start so we catch users who travel between
        // timezones (or who upgraded from a build that didn't send timezone).
        syncTimezoneInBackground();
        // Stage 0 (KMP handoff): refresh the handoff file once per session
        // start while a recovery key is alive.
        await syncHandoffFile();
        return;
      }

      // Only recover from the expected "no session" state.
      // Temporary backend/proxy errors should not trigger recovery-key cleanup.
      if (meRes.status !== 401) {
        console.warn(`[Auth] Session check returned ${meRes.status}, skipping recovery auto-login`);
        return;
      }

      // Step 2 — auto-login with stored key
      const storedKey = await getStoredRecoveryKey();
      if (storedKey) {
        // Stage 0 (KMP handoff): the key is alive — refresh the handoff file
        // once per session start, before the recovery round-trip.
        await syncHandoffFile();

        const loginRes = await baseFetch('/api/auth/login-recovery', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ recoveryKey: storedKey }),
        });

        if (loginRes.ok) {
          const data = await loginRes.json();
          setUser(data.user);
          syncTimezoneInBackground();
          return;
        }

        // Only clear the key when the server explicitly says it is invalid.
        // For 429/5xx and other transient failures we keep the stored key.
        if (loginRes.status === 401) {
          await clearRecoveryKey();
          // Stage 0 (KMP handoff): the server rejected this key, so the
          // handoff copy is equally dead — drop it together with the key.
          await clearHandoffFile();
          return;
        }

        console.warn(`[Auth] Recovery login returned ${loginRes.status}, keeping stored key`);
      }
    } catch (error) {
      // Network error during init — user stays null, login screen is shown
      console.error('[Auth] Session init failed:', error);
    } finally {
      setLoading(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Auth actions
  // ---------------------------------------------------------------------------

  const createAnonymousAccount = async (name?: string): Promise<User> => {
    const res = await baseFetch('/api/auth/create-anonymous', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    });

    if (!res.ok) {
      await throwAsApiError(res, 'Failed to create account');
    }

    const data = await res.json();

    // Persist the recovery key so future app launches auto-login
    await saveRecoveryKey(data.user.recovery_key);
    // Stage 0 (KMP handoff): mirror the new key + current settings to the file
    await syncHandoffFile();
    setUser(data.user);
    // Best-effort: tell the backend our timezone so the daily push fires
    // at the user's local notification_time, not Moscow time.
    syncTimezoneInBackground();
    return data.user;
  };

  const signInWithRecoveryKey = async (recoveryKey: string): Promise<void> => {
    const res = await baseFetch('/api/auth/login-recovery', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ recoveryKey }),
    });

    if (!res.ok) {
      await throwAsApiError(res, 'Invalid recovery key');
    }

    const data = await res.json();

    // Persist for auto-login
    await saveRecoveryKey(recoveryKey);
    // Stage 0 (KMP handoff): mirror the key + current settings to the file
    await syncHandoffFile();
    setUser(data.user);
    syncTimezoneInBackground();
  };

  const signOut = async (): Promise<void> => {
    try {
      await baseFetch('/api/auth/logout', {
        method: 'POST',
      });
    } catch (error) {
      console.error('[Auth] Logout request failed:', error);
    }
    // Always clear local state even if the server request fails.
    // queryClient.clear() drops all cached queries so the next account
    // can't see the previous user's plants/profile (security).
    await clearRecoveryKey();
    // Stage 0 (KMP handoff): remove the handoff copy together with the key —
    // leaving it behind would let the KMP build silently sign the user back
    // into the abandoned account.
    await clearHandoffFile();
    queryClient.clear();
    await persister.removeClient();
    setUser(null);
  };

  const updateUser = (userData: Partial<User>): void => {
    setUser((prev) => (prev ? { ...prev, ...userData } : null));
  };

  const regenerateRecoveryKey = async (): Promise<void> => {
    const res = await baseFetch('/api/auth/regenerate-recovery-key', {
      method: 'POST',
    });

    const data = await res.json();

    if (!res.ok) {
      throw new Error(data.error || 'Failed to regenerate recovery key');
    }

    // Update the stored key to the new one
    await saveRecoveryKey(data.user.recovery_key);
    // Stage 0 (KMP handoff): mirror the new key + current settings to the file
    await syncHandoffFile();
    setUser(data.user);
  };

  // ---------------------------------------------------------------------------

  return (
    <AuthContext.Provider
      value={{
        user,
        loading,
        createAnonymousAccount,
        signInWithRecoveryKey,
        signOut,
        updateUser,
        regenerateRecoveryKey,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

export function useAuth(): AuthContextType {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return ctx;
}
