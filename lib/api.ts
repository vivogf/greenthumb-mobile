import { API_BASE_URL } from './constants';

export class ApiError extends Error {
  status: number;
  code: 'unauthorized' | 'client' | 'server' | 'network' | 'timeout';

  constructor(status: number, code: ApiError['code'], message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

type BaseFetchOptions = RequestInit & {
  timeout?: number;
};

async function toApiError(res: Response): Promise<ApiError> {
  const text = (await res.text()) || res.statusText;

  if (res.status === 401) {
    return new ApiError(res.status, 'unauthorized', text);
  }
  if (res.status >= 500) {
    return new ApiError(res.status, 'server', text);
  }
  return new ApiError(res.status, 'client', text);
}

/**
 * Core fetch wrapper. Prepends the API base URL and always sends credentials
 * (cookies). React Native stores cookies in-memory for the session lifetime.
 * On app restart, we rely on the stored recovery key for auto-login.
 */
export async function baseFetch(
  path: string,
  options: BaseFetchOptions = {},
): Promise<Response> {
  const { timeout = 10_000, signal, ...init } = options;
  const url = `${API_BASE_URL}${path}`;
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeout);

  const abortFromSignal = () => controller.abort();

  if (signal) {
    if (signal.aborted) {
      controller.abort();
    } else {
      signal.addEventListener('abort', abortFromSignal, { once: true });
    }
  }

  try {
    return await fetch(url, {
      ...init,
      credentials: 'include',
      signal: controller.signal,
    });
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') {
      throw new ApiError(0, 'timeout', 'Request timed out');
    }
    if (error instanceof TypeError) {
      throw new ApiError(0, 'network', 'Network error');
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
    signal?.removeEventListener('abort', abortFromSignal);
  }
}

export async function apiRequest(
  method: string,
  path: string,
  data?: unknown,
): Promise<Response> {
  const res = await baseFetch(path, {
    method,
    headers: data ? { 'Content-Type': 'application/json' } : {},
    body: data ? JSON.stringify(data) : undefined,
  });

  if (!res.ok) {
    throw await toApiError(res);
  }

  return res;
}

/**
 * GET helper — used by React Query as the default queryFn.
 * Paths in queryKey arrays are joined and prefixed with API_BASE_URL.
 */
export async function apiFetch<T>(path: string): Promise<T> {
  const res = await baseFetch(path);

  if (!res.ok) {
    throw await toApiError(res);
  }

  return res.json() as Promise<T>;
}
