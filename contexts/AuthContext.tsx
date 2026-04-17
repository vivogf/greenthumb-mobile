import { createContext, useContext, useEffect, useState } from 'react';
import { baseFetch } from '../lib/api';
import { queryClient, persister } from '../lib/queryClient';
import {
  saveRecoveryKey,
  getStoredRecoveryKey,
  clearRecoveryKey,
} from '../lib/storage';
import type { User } from '../shared/schema';

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
        const loginRes = await baseFetch('/api/auth/login-recovery', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ recoveryKey: storedKey }),
        });

        if (loginRes.ok) {
          const data = await loginRes.json();
          setUser(data.user);
          return;
        }

        // Only clear the key when the server explicitly says it is invalid.
        // For 429/5xx and other transient failures we keep the stored key.
        if (loginRes.status === 401) {
          await clearRecoveryKey();
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

    const data = await res.json();

    if (!res.ok) {
      throw new Error(data.error || 'Failed to create account');
    }

    // Persist the recovery key so future app launches auto-login
    await saveRecoveryKey(data.user.recovery_key);
    setUser(data.user);
    return data.user;
  };

  const signInWithRecoveryKey = async (recoveryKey: string): Promise<void> => {
    const res = await baseFetch('/api/auth/login-recovery', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ recoveryKey }),
    });

    const data = await res.json();

    if (!res.ok) {
      throw new Error(data.error || 'Invalid recovery key');
    }

    // Persist for auto-login
    await saveRecoveryKey(recoveryKey);
    setUser(data.user);
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
