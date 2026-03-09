import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import * as Localization from 'expo-localization';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { LANGUAGE_STORE_KEY } from '../lib/constants';

import en from './locales/en.json';
import ru from './locales/ru.json';

// Detect device language on first launch
const deviceLanguage = Localization.getLocales()[0]?.languageCode ?? 'en';
const supportedLanguages = ['en', 'ru'];
const defaultLanguage = supportedLanguages.includes(deviceLanguage)
  ? deviceLanguage
  : 'en';

i18n.use(initReactI18next).init({
  compatibilityJSON: 'v3',
  resources: {
    en: { translation: en },
    ru: { translation: ru },
  },
  lng: defaultLanguage,
  fallbackLng: 'en',
  interpolation: {
    escapeValue: false,
  },
});

/**
 * Load saved language preference from AsyncStorage.
 * Call this once at app startup (in root _layout.tsx).
 */
export async function loadSavedLanguage(): Promise<void> {
  try {
    const saved = await AsyncStorage.getItem(LANGUAGE_STORE_KEY);
    if (saved && supportedLanguages.includes(saved)) {
      await i18n.changeLanguage(saved);
    }
  } catch {
    // If AsyncStorage fails, keep the device default language
  }
}

/**
 * Change language and persist the choice for future sessions.
 */
export async function changeLanguage(lng: string): Promise<void> {
  await i18n.changeLanguage(lng);
  await AsyncStorage.setItem(LANGUAGE_STORE_KEY, lng);
}

export default i18n;
