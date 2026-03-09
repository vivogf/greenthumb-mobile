import { createContext, useContext, useEffect, useState } from 'react';
import { API_BASE_URL } from '../lib/constants';
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
   * 2. If no valid session → try auto-login with the stored recovery key
   * 3. If auto-login fails → clear the key (it might have been regenerated)
   */
  async function initSession() {
    try {
      // Step 1 — cookie still alive?
      const meRes = await fetch(`${API_BASE_URL}/api/auth/me`, {
        credentials: 'include',
      });

      if (meRes.ok) {
        const data = await meRes.json();
        setUser(data.user);
        return;
      }

      // Step 2 — auto-login with stored key
      const storedKey = await getStoredRecoveryKey();
      if (storedKey) {
        const loginRes = await fetch(`${API_BASE_URL}/api/auth/login-recovery`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ recoveryKey: storedKey }),
          credentials: 'include',
        });

        if (loginRes.ok) {
          const data = await loginRes.json();
          setUser(data.user);
          return;
        }

        // Step 3 — stored key is invalid, clear it
        await clearRecoveryKey();
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
    const res = await fetch(`${API_BASE_URL}/api/auth/create-anonymous`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
      credentials: 'include',
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
    const res = await fetch(`${API_BASE_URL}/api/auth/login-recovery`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ recoveryKey }),
      credentials: 'include',
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
      await fetch(`${API_BASE_URL}/api/auth/logout`, {
        method: 'POST',
        credentials: 'include',
      });
    } catch (error) {
      console.error('[Auth] Logout request failed:', error);
    }
    // Always clear local state even if the server request fails
    await clearRecoveryKey();
    setUser(null);
  };

  const updateUser = (userData: Partial<User>): void => {
    setUser((prev) => (prev ? { ...prev, ...userData } : null));
  };

  const regenerateRecoveryKey = async (): Promise<void> => {
    const res = await fetch(`${API_BASE_URL}/api/auth/regenerate-recovery-key`, {
      method: 'POST',
      credentials: 'include',
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
