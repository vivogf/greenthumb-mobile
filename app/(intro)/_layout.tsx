import { Stack } from 'expo-router';

/**
 * Intro group — first-launch welcome carousel.
 * Shown only when AsyncStorage flag INTRO_SEEN_STORE_KEY is not set.
 * Root index.tsx handles the redirect.
 */
export default function IntroLayout() {
  return (
    <Stack screenOptions={{ headerShown: false }} />
  );
}
