import { useCallback, useRef, useState } from 'react';
import {
  View,
  Text,
  Pressable,
  ScrollView,
  useWindowDimensions,
  type NativeSyntheticEvent,
  type NativeScrollEvent,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useTranslation } from 'react-i18next';
import { Ionicons } from '@expo/vector-icons';
import * as Haptics from 'expo-haptics';
import { useColors } from '../../hooks/useColors';
import { setHasSeenIntro } from '../../lib/storage';

type SlideKey = '1' | '2' | '3';

type Slide = {
  key: SlideKey;
  icon: keyof typeof Ionicons.glyphMap;
  titleKey: string;
  bodyKey: string;
};

const SLIDES: Slide[] = [
  { key: '1', icon: 'leaf',              titleKey: 'intro.slide1Title', bodyKey: 'intro.slide1Body' },
  { key: '2', icon: 'water',             titleKey: 'intro.slide2Title', bodyKey: 'intro.slide2Body' },
  { key: '3', icon: 'notifications',     titleKey: 'intro.slide3Title', bodyKey: 'intro.slide3Body' },
];

export default function WelcomeScreen() {
  const { t } = useTranslation();
  const router = useRouter();
  const colors = useColors();
  const { width } = useWindowDimensions();

  const scrollRef = useRef<ScrollView>(null);
  const [index, setIndex] = useState(0);

  const finish = useCallback(async () => {
    await setHasSeenIntro();
    router.replace('/(auth)/login');
  }, [router]);

  const handleNext = useCallback(() => {
    if (index >= SLIDES.length - 1) {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      void finish();
      return;
    }
    const nextIndex = index + 1;
    scrollRef.current?.scrollTo({ x: nextIndex * width, animated: true });
    setIndex(nextIndex);
    Haptics.selectionAsync();
  }, [index, width, finish]);

  const handleScrollEnd = useCallback((e: NativeSyntheticEvent<NativeScrollEvent>) => {
    const offset = e.nativeEvent.contentOffset.x;
    const newIndex = Math.round(offset / width);
    if (newIndex !== index) setIndex(newIndex);
  }, [index, width]);

  const isLast = index === SLIDES.length - 1;

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
      {/* Skip — top right */}
      <View style={{ alignItems: 'flex-end', paddingHorizontal: 20, paddingTop: 8 }}>
        <Pressable
          onPress={() => void finish()}
          accessibilityRole="button"
          accessibilityLabel={t('intro.skip')}
          hitSlop={12}
          style={({ pressed }) => ({ opacity: pressed ? 0.6 : 1, padding: 8 })}
        >
          <Text style={{ fontSize: 15, color: colors.mutedForeground, fontWeight: '500' }}>
            {t('intro.skip')}
          </Text>
        </Pressable>
      </View>

      {/* Paged slides */}
      <ScrollView
        ref={scrollRef}
        horizontal
        pagingEnabled
        showsHorizontalScrollIndicator={false}
        onMomentumScrollEnd={handleScrollEnd}
        style={{ flex: 1 }}
      >
        {SLIDES.map((slide) => (
          <View
            key={slide.key}
            style={{
              width,
              flex: 1,
              paddingHorizontal: 32,
              justifyContent: 'center',
              alignItems: 'center',
              gap: 24,
            }}
          >
            <View
              style={{
                width: 120,
                height: 120,
                borderRadius: 60,
                backgroundColor: colors.primary + '22',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Ionicons name={slide.icon} size={56} color={colors.primary} />
            </View>

            <Text
              style={{
                fontSize: 26,
                fontWeight: '700',
                color: colors.foreground,
                textAlign: 'center',
              }}
            >
              {t(slide.titleKey)}
            </Text>

            <Text
              style={{
                fontSize: 16,
                color: colors.mutedForeground,
                textAlign: 'center',
                lineHeight: 24,
                maxWidth: 320,
              }}
            >
              {t(slide.bodyKey)}
            </Text>
          </View>
        ))}
      </ScrollView>

      {/* Dots indicator */}
      <View style={{ flexDirection: 'row', justifyContent: 'center', gap: 8, paddingVertical: 16 }}>
        {SLIDES.map((slide, i) => (
          <View
            key={slide.key}
            style={{
              width: i === index ? 24 : 8,
              height: 8,
              borderRadius: 4,
              backgroundColor: i === index ? colors.primary : colors.border,
            }}
          />
        ))}
      </View>

      {/* Primary CTA */}
      <View style={{ paddingHorizontal: 20, paddingBottom: 20 }}>
        <Pressable
          onPress={handleNext}
          accessibilityRole="button"
          accessibilityLabel={isLast ? t('intro.getStarted') : t('intro.next')}
          style={({ pressed }) => ({
            backgroundColor: colors.primary,
            borderRadius: 10,
            paddingVertical: 14,
            alignItems: 'center',
            opacity: pressed ? 0.85 : 1,
          })}
        >
          <Text style={{ color: colors.primaryForeground, fontSize: 16, fontWeight: '600' }}>
            {isLast ? t('intro.getStarted') : t('intro.next')}
          </Text>
        </Pressable>
      </View>
    </SafeAreaView>
  );
}
