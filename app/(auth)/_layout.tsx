import { Stack } from 'expo-router';

/**
 * Auth group layout — public screens (login, etc.).
 * No redirect logic here; the root index.tsx handles the initial routing,
 * and the login screen itself handles post-login navigation.
 */
export default function AuthLayout() {
  return (
    <Stack screenOptions={{ headerShown: false }} />
  );
}
