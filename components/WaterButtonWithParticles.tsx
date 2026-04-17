/**
 * Water button with heart particle animation.
 * Manages the "just watered" state locally — shows particles first,
 * then fires the actual water mutation after a short delay.
 *
 * Two visual modes:
 *   compact  — small circular button (for list view)
 *   fullWidth — full-width button with label (for card view)
 */
import { useState, useCallback, useRef } from 'react';
import { Pressable, Text, ActivityIndicator, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import * as Haptics from 'expo-haptics';
import { WaterParticles } from './WaterParticles';

interface WaterButtonProps {
  onWater: () => void;
  isWatering: boolean;
  compact?: boolean;
  label?: string;
  accessibilityLabel?: string;
  colors: {
    primary: string;
    primaryForeground: string;
    muted: string;
  };
}

export function WaterButtonWithParticles({
  onWater,
  isWatering,
  compact = false,
  label,
  accessibilityLabel,
  colors,
}: WaterButtonProps) {
  const [showParticles, setShowParticles] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handlePress = useCallback(() => {
    if (isWatering || showParticles) return;

    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
    setShowParticles(true);

    // Fire the actual mutation after a short delay so the animation plays first
    timerRef.current = setTimeout(() => {
      onWater();
      // Keep particles visible a bit longer after mutation fires
      setTimeout(() => setShowParticles(false), 800);
    }, 400);
  }, [onWater, isWatering, showParticles]);

  if (compact) {
    // Circular button for list mode
    return (
      <View style={{ position: 'relative', overflow: 'visible' }}>
        <Pressable
          onPress={handlePress}
          disabled={isWatering}
          accessibilityRole="button"
          accessibilityLabel={accessibilityLabel}
          accessibilityState={{ disabled: isWatering, busy: isWatering }}
          style={({ pressed }) => ({
            width: 40,
            height: 40,
            borderRadius: 20,
            backgroundColor: isWatering ? colors.muted : colors.primary,
            alignItems: 'center',
            justifyContent: 'center',
            opacity: pressed ? 0.7 : 1,
          })}
        >
          {isWatering ? (
            <ActivityIndicator size="small" color={colors.primaryForeground} />
          ) : (
            <Ionicons name="water" size={20} color={colors.primaryForeground} />
          )}
        </Pressable>
        <WaterParticles active={showParticles} color={colors.primary} />
      </View>
    );
  }

  // Full-width button for card mode
  return (
    <View style={{ position: 'relative', overflow: 'visible' }}>
      <Pressable
        onPress={handlePress}
        disabled={isWatering}
        accessibilityRole="button"
        accessibilityLabel={accessibilityLabel ?? label}
        accessibilityState={{ disabled: isWatering, busy: isWatering }}
        style={({ pressed }) => ({
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 8,
          backgroundColor: colors.primary,
          borderRadius: 10,
          paddingVertical: 10,
          opacity: pressed || isWatering ? 0.7 : 1,
        })}
      >
        {isWatering ? (
          <ActivityIndicator size="small" color={colors.primaryForeground} />
        ) : (
          <>
            <Ionicons name="water" size={16} color={colors.primaryForeground} />
            {label && (
              <Text
                style={{
                  fontSize: 14,
                  fontWeight: '600',
                  color: colors.primaryForeground,
                }}
              >
                {label}
              </Text>
            )}
          </>
        )}
      </Pressable>
      <WaterParticles active={showParticles} color={colors.primary} />
    </View>
  );
}
