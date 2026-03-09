/**
 * Floating heart particles that appear when a plant is watered.
 * Replicates the PWA's heart animation — 8-12 green hearts float upward
 * with random scatter, rotation, and scale.
 */
import { useState, useEffect, useRef } from 'react';
import { View } from 'react-native';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withTiming,
  withSequence,
  withDelay,
  Easing,
  runOnJS,
} from 'react-native-reanimated';
import { Ionicons } from '@expo/vector-icons';

interface Particle {
  id: number;
  x: number;        // horizontal offset (-40 to +40)
  travel: number;    // upward distance (40-70)
  rotation: number;  // degrees (-30 to +30)
  size: number;      // icon size (14-22)
  delay: number;     // stagger delay (0-300ms)
}

interface WaterParticlesProps {
  active: boolean;
  color: string;
}

function generateParticles(): Particle[] {
  const count = 8 + Math.floor(Math.random() * 5); // 8-12
  return Array.from({ length: count }, (_, i) => ({
    id: i,
    x: (Math.random() - 0.5) * 80,        // -40 to +40
    travel: 40 + Math.random() * 30,        // 40 to 70
    rotation: (Math.random() - 0.5) * 60,   // -30 to +30
    size: 14 + Math.floor(Math.random() * 9), // 14 to 22
    delay: Math.random() * 300,              // 0 to 300ms stagger
  }));
}

function HeartParticle({ particle, color }: { particle: Particle; color: string }) {
  const progress = useSharedValue(0);

  useEffect(() => {
    progress.value = withDelay(
      particle.delay,
      withTiming(1, { duration: 1800, easing: Easing.out(Easing.cubic) }),
    );
  }, []);

  const animatedStyle = useAnimatedStyle(() => {
    const p = progress.value;

    // Scale: 0.3 → 1.1 (at 30%) → 0.7 (at 70%) → 0
    let scale: number;
    if (p < 0.3) {
      scale = 0.3 + (p / 0.3) * 0.8;          // 0.3 → 1.1
    } else if (p < 0.7) {
      scale = 1.1 - ((p - 0.3) / 0.4) * 0.4;  // 1.1 → 0.7
    } else {
      scale = 0.7 - ((p - 0.7) / 0.3) * 0.7;  // 0.7 → 0
    }

    // Y: upward arc then slight fall
    let translateY: number;
    if (p < 0.75) {
      translateY = -(p / 0.75) * particle.travel;
    } else {
      translateY = -particle.travel + ((p - 0.75) / 0.25) * 12; // slight drop at end
    }

    return {
      opacity: p < 0.8 ? 1 : 1 - (p - 0.8) / 0.2,
      transform: [
        { translateX: particle.x * p },
        { translateY },
        { scale: Math.max(0, scale) },
        { rotate: `${particle.rotation * p}deg` },
      ],
    };
  });

  return (
    <Animated.View
      style={[
        {
          position: 'absolute',
          left: -particle.size / 2,
          top: -particle.size / 2,
        },
        animatedStyle,
      ]}
    >
      <Ionicons name="heart" size={particle.size} color={color} />
    </Animated.View>
  );
}

export function WaterParticles({ active, color }: WaterParticlesProps) {
  const [particles, setParticles] = useState<Particle[]>([]);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (active) {
      setParticles(generateParticles());
      timerRef.current = setTimeout(() => setParticles([]), 2200);
    }
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [active]);

  if (particles.length === 0) return null;

  return (
    <View
      pointerEvents="none"
      style={{
        position: 'absolute',
        left: '50%',
        top: '50%',
        width: 0,
        height: 0,
        overflow: 'visible',
        zIndex: 999,
      }}
    >
      {particles.map((p) => (
        <HeartParticle key={p.id} particle={p} color={color} />
      ))}
    </View>
  );
}
