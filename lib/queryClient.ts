import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, QueryFunction } from '@tanstack/react-query';
import { createAsyncStoragePersister } from '@tanstack/query-async-storage-persister';
import { apiFetch } from './api';

/**
 * Default query function for React Query.
 * Uses only the first queryKey element as the API path.
 */
const defaultQueryFn: QueryFunction = ({ queryKey }) => {
  const [path] = queryKey as [string, ...unknown[]];
  return apiFetch(path);
};

export const persister = createAsyncStoragePersister({
  storage: AsyncStorage,
  key: 'gt-rq-cache-v1',
  throttleTime: 1000,
});

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      queryFn: defaultQueryFn,
      // Plant data is allowed to be slightly stale (1 minute) so the UI
      // stays snappy. After that, return to the app / reconnect = refetch.
      // Without a finite staleTime, refetchOnWindowFocus has no effect.
      staleTime: 60_000,
      refetchInterval: false,
      refetchOnWindowFocus: true,
      refetchOnReconnect: true,
      retry: false,
    },
    mutations: {
      retry: false,
    },
  },
});
