import { QueryClient, QueryFunction } from '@tanstack/react-query';
import { apiFetch } from './api';

/**
 * Default query function for React Query.
 * Joins the queryKey array into an API path and fetches from the backend.
 * Example: queryKey = ['/api/plants'] -> GET /api/plants
 */
const defaultQueryFn: QueryFunction = ({ queryKey }) => {
  const path = queryKey.join('') as string;
  return apiFetch(path);
};

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      queryFn: defaultQueryFn,
      // Data stays fresh until manually invalidated — same as PWA
      staleTime: Infinity,
      refetchInterval: false,
      refetchOnWindowFocus: false,
      retry: false,
    },
    mutations: {
      retry: false,
    },
  },
});
