import { API_BASE_URL } from './constants';

async function throwIfNotOk(res: Response): Promise<void> {
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
  const res = await fetch(url, {
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
  const res = await fetch(url, { credentials: 'include' });
  if (res.status === 401) {
    throw new Error('401: Unauthorized');
  }
  await throwIfNotOk(res);
  return res.json() as Promise<T>;
}
