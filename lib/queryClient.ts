import { QueryClient, QueryCache, MutationCache, QueryFunction } from '@tanstack/react-query';
import { apiFetch, TimeoutError, NetworkError, UnauthorizedError } from './api';

/**
 * Default query function for React Query.
 * Joins the queryKey array into an API path and fetches from the backend.
 * Example: queryKey = ['/api/plants'] -> GET /api/plants
 */
const defaultQueryFn: QueryFunction = ({ queryKey }) => {
  const path = queryKey.join('') as string;
  return apiFetch(path);
};

/**
 * Global callback fired when any query/mutation throws UnauthorizedError.
 * Set by AuthProvider at mount so we can sign the user out and send them to
 * the login screen without importing React context into plain modules.
 */
let unauthorizedHandler: (() => void) | null = null;

export function setUnauthorizedHandler(fn: (() => void) | null): void {
  unauthorizedHandler = fn;
}

function handleError(error: unknown): void {
  if (error instanceof UnauthorizedError) {
    unauthorizedHandler?.();
  }
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: handleError }),
  mutationCache: new MutationCache({ onError: handleError }),
  defaultOptions: {
    queries: {
      queryFn: defaultQueryFn,
      // Data stays fresh until manually invalidated — same as PWA
      staleTime: Infinity,
      refetchInterval: false,
      refetchOnWindowFocus: false,
      // Retry transient failures (timeout, network) up to 2 extra times. Never
      // retry auth failures (would loop forever) or HTTP 4xx (permanent).
      retry: (failureCount, error) => {
        if (error instanceof UnauthorizedError) return false;
        if (error instanceof TimeoutError || error instanceof NetworkError) {
          return failureCount < 2;
        }
        return false;
      },
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
    },
    mutations: {
      retry: false,
    },
  },
});
