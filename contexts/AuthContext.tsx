import { createContext, useContext, useEffect, useRef, useState } from 'react';
import { API_BASE_URL } from '../lib/constants';
import { fetchWithTimeout, TimeoutError, NetworkError } from '../lib/api';
import { setUnauthorizedHandler } from '../lib/queryClient';
import {
  saveRecoveryKey,
  getStoredRecoveryKey,
  clearRecoveryKey,
} from '../lib/storage';
import type { User } from '../shared/schema';

/**
 * Normalizes a thrown auth error into a translation key string so the UI can
 * render a localized message regardless of what the underlying layer threw.
 * The UI detects keys starting with `errors.` and runs them through i18n.
 */
function authErrorKey(err: unknown, fallbackKey: string): string {
  if (err instanceof TimeoutError) return 'errors.timeout';
  if (err instanceof NetworkError) return 'errors.network';
  if (err && typeof err === 'object' && 'message' in err && typeof (err as any).message === 'string') {
    return (err as Error).message || fallbackKey;
  }
  return fallbackKey;
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
  const isHandlingUnauthorized = useRef(false);

  useEffect(() => {
    initSession();
  }, []);

  // Register a global handler so any 401 from the API layer forces a local sign-out.
  // Guarded by a ref so that a burst of parallel 401s (dashboard + detail view)
  // only triggers one clearRecoveryKey / setUser(null) pass.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      if (isHandlingUnauthorized.current) return;
      isHandlingUnauthorized.current = true;
      clearRecoveryKey().finally(() => {
        setUser(null);
        // Allow another handler run after the UI has navigated to /login.
        setTimeout(() => { isHandlingUnauthorized.current = false; }, 1000);
      });
    });
    return () => setUnauthorizedHandler(null);
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
      const meRes = await fetchWithTimeout(`${API_BASE_URL}/api/auth/me`, {
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
        const loginRes = await fetchWithTimeout(`${API_BASE_URL}/api/auth/login-recovery`, {
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

        // Only clear the key if the server actively rejected it (4xx).
        // For timeout/5xx we keep the key so the next cold start can retry.
        if (loginRes.status >= 400 && loginRes.status < 500) {
          await clearRecoveryKey();
        }
      }
    } catch (error) {
      // Network / timeout error during init — user stays null, login screen is shown.
      // Do NOT clear the recovery key: it is probably still valid.
      console.error('[Auth] Session init failed:', error);
    } finally {
      setLoading(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Auth actions
  // ---------------------------------------------------------------------------

  const createAnonymousAccount = async (name?: string): Promise<User> => {
    let res: Response;
    try {
      res = await fetchWithTimeout(`${API_BASE_URL}/api/auth/create-anonymous`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
        credentials: 'include',
      });
    } catch (err) {
      throw new Error(authErrorKey(err, 'errors.createAccountFailed'));
    }

    const data = await res.json().catch(() => ({} as any));

    if (!res.ok) {
      throw new Error(data?.error || 'errors.createAccountFailed');
    }

    // Persist the recovery key so future app launches auto-login
    await saveRecoveryKey(data.user.recovery_key);
    setUser(data.user);
    return data.user;
  };

  const signInWithRecoveryKey = async (recoveryKey: string): Promise<void> => {
    let res: Response;
    try {
      res = await fetchWithTimeout(`${API_BASE_URL}/api/auth/login-recovery`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ recoveryKey }),
        credentials: 'include',
      });
    } catch (err) {
      throw new Error(authErrorKey(err, 'errors.signInFailed'));
    }

    const data = await res.json().catch(() => ({} as any));

    if (!res.ok) {
      if (res.status === 401 || res.status === 404) {
        throw new Error('errors.invalidRecoveryKey');
      }
      throw new Error(data?.error || 'errors.signInFailed');
    }

    // Persist for auto-login
    await saveRecoveryKey(recoveryKey);
    setUser(data.user);
  };

  const signOut = async (): Promise<void> => {
    try {
      await fetchWithTimeout(`${API_BASE_URL}/api/auth/logout`, {
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
    let res: Response;
    try {
      res = await fetchWithTimeout(`${API_BASE_URL}/api/auth/regenerate-recovery-key`, {
        method: 'POST',
        credentials: 'include',
      });
    } catch (err) {
      throw new Error(authErrorKey(err, 'errors.regenerateFailed'));
    }

    const data = await res.json().catch(() => ({} as any));

    if (!res.ok) {
      throw new Error(data?.error || 'errors.regenerateFailed');
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
