import { API_BASE_URL } from './constants';

/** Default network timeout — long enough to cover cold-start, short enough to surface real failures. */
export const DEFAULT_TIMEOUT_MS = 25000;

/** Thrown when a request exceeds the timeout. Carries a stable code so UI can localize. */
export class TimeoutError extends Error {
  code = 'TIMEOUT' as const;
  constructor() {
    super('timeout');
    this.name = 'TimeoutError';
  }
}

/** Thrown on network failures (DNS, offline, refused). Carries a stable code. */
export class NetworkError extends Error {
  code = 'NETWORK' as const;
  constructor(message = 'network') {
    super(message);
    this.name = 'NetworkError';
  }
}

/** Thrown when the server rejected the session (401). UI uses this to redirect to login. */
export class UnauthorizedError extends Error {
  code = 'UNAUTHORIZED' as const;
  constructor() {
    super('unauthorized');
    this.name = 'UnauthorizedError';
  }
}

/**
 * fetch with a hard client-side timeout. AbortController cancels the request
 * after `timeoutMs`; any AbortError is rethrown as TimeoutError, any other
 * thrown (DNS/offline/refused) is rethrown as NetworkError.
 */
export async function fetchWithTimeout(
  input: RequestInfo | URL,
  init: RequestInit = {},
  timeoutMs: number = DEFAULT_TIMEOUT_MS,
): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(input, { ...init, signal: controller.signal });
  } catch (err: any) {
    if (err?.name === 'AbortError') throw new TimeoutError();
    throw new NetworkError(err?.message);
  } finally {
    clearTimeout(timer);
  }
}

async function throwIfNotOk(res: Response): Promise<void> {
  if (res.status === 401) throw new UnauthorizedError();
  if (!res.ok) {
    const text = (await res.text()) || res.statusText;
    throw new Error(`${res.status}: ${text}`);
  }
}

/**
 * Core fetch wrapper. Prepends the API base URL and always sends credentials
 * (cookies). React Native stores cookies in-memory for the session lifetime.
 * On app restart, we rely on the stored recovery key for auto-login.
 */
export async function apiRequest(
  method: string,
  path: string,
  data?: unknown,
): Promise<Response> {
  const url = `${API_BASE_URL}${path}`;
  const res = await fetchWithTimeout(url, {
    method,
    headers: data ? { 'Content-Type': 'application/json' } : {},
    body: data ? JSON.stringify(data) : undefined,
    credentials: 'include',
  });
  await throwIfNotOk(res);
  return res;
}

/**
 * GET helper — used by React Query as the default queryFn.
 * Paths in queryKey arrays are joined and prefixed with API_BASE_URL.
 */
export async function apiFetch<T>(path: string): Promise<T> {
  const url = `${API_BASE_URL}${path}`;
  const res = await fetchWithTimeout(url, { credentials: 'include' });
  await throwIfNotOk(res);
  return res.json() as Promise<T>;
}
