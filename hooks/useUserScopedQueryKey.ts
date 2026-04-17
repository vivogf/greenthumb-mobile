import { useAuth } from '../contexts/AuthContext';

export function useUserScopedQueryKey() {
  const { user } = useAuth();

  return (path: string) => [path, user?.id ?? 'anon'] as const;
}
