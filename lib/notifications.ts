import Constants from 'expo-constants';
import { Platform } from 'react-native';
import i18n from '../i18n';
import { apiRequest, apiFetch } from './api';

/** True when running inside Expo Go (not a development/standalone build). */
export const isExpoGo = Constants.appOwnership === 'expo';

let Notifications: typeof import('expo-notifications') | null = null;

/** Lazy-load expo-notifications (crashes on import in Expo Go since SDK 53). */
async function getNotifications() {
  if (Notifications) return Notifications;
  if (isExpoGo) return null;
  try {
    Notifications = await import('expo-notifications');
    return Notifications;
  } catch {
    return null;
  }
}

/** Set up foreground notification handler. Call once at app startup. */
export async function initNotificationHandler(): Promise<void> {
  const N = await getNotifications();
  if (!N) return;
  N.setNotificationHandler({
    handleNotification: async () => ({
      shouldShowBanner: true,
      shouldShowList: true,
      shouldPlaySound: true,
      shouldSetBadge: false,
    }),
  });
}

/**
 * Request notification permissions and return Expo Push Token.
 * Returns null if permissions denied, running in Expo Go, or in simulator.
 */
export async function registerForPushNotificationsAsync(): Promise<string | null> {
  const N = await getNotifications();
  if (!N) return null;

  if (Platform.OS === 'android') {
    await N.setNotificationChannelAsync('default', {
      name: 'Plant Care Reminders',
      importance: N.AndroidImportance.HIGH,
      vibrationPattern: [0, 250, 250, 250],
      lightColor: '#4a9a5a',
    });
  }

  const { status: existingStatus } = await N.getPermissionsAsync();
  let finalStatus = existingStatus;

  if (existingStatus !== 'granted') {
    const { status } = await N.requestPermissionsAsync();
    finalStatus = status;
  }

  if (finalStatus !== 'granted') {
    return null;
  }

  const projectId = Constants.expoConfig?.extra?.eas?.projectId;
  const tokenData = await N.getExpoPushTokenAsync({
    ...(projectId ? { projectId } : {}),
  });

  return tokenData.data;
}

/** Subscribe this device's Expo token to backend push notifications. */
export async function subscribeToExpoNotifications(token: string): Promise<void> {
  // Send current UI language so the backend can localize push notification text.
  // Defensive: if i18n module is unavailable in the current bundle (OTA edge case),
  // fall back to 'ru' instead of crashing the whole subscribe flow.
  let language: 'en' | 'ru' = 'ru';
  try {
    if (i18n?.language?.startsWith('en')) language = 'en';
  } catch {
    // i18n access failed — keep default 'ru'.
  }
  await apiRequest('POST', '/api/push/subscribe-expo', {
    expo_push_token: token,
    language,
  });
}

/** Unsubscribe this device from backend push notifications. */
export async function unsubscribeFromExpoNotifications(): Promise<void> {
  await apiRequest('DELETE', '/api/push/subscribe-expo');
}

/** Check whether this user has an active Expo push subscription. */
export async function checkExpoSubscription(): Promise<boolean> {
  const data = await apiFetch<{ subscribed: boolean }>('/api/push/expo-subscription');
  return data.subscribed;
}

/**
 * Subscribe to "user tapped a notification" events.
 * Returns an unsubscribe function. No-op in Expo Go.
 *
 * The callback receives the notification data payload, which by convention
 * may contain a `plant_id` so the app can deep-link into the plant screen.
 */
export async function addNotificationResponseListener(
  callback: (data: Record<string, unknown>) => void,
): Promise<() => void> {
  const N = await getNotifications();
  if (!N) return () => {};
  const sub = N.addNotificationResponseReceivedListener((response) => {
    const data = (response.notification.request.content.data ?? {}) as Record<string, unknown>;
    callback(data);
  });
  return () => sub.remove();
}

/** Send a local test notification to verify permissions work. */
export async function sendLocalTestNotification(): Promise<void> {
  const N = await getNotifications();
  if (!N) return;
  await N.scheduleNotificationAsync({
    content: {
      title: 'GreenThumb 💚',
      body: 'Уведомления работают! 🌿',
      sound: 'default',
    },
    // Android: route through the HIGH-importance `default` channel we set up
    // in registerForPushNotificationsAsync, so the banner/sound actually fire.
    // iOS ignores channelId.
    trigger:
      Platform.OS === 'android' ? { channelId: 'default' } : null,
  });
}
