/**
 * ThanosSnap — wraps content and dissolves it into dust particles.
 * Earthy/green-toned particles scatter rightward in a left-to-right sweep,
 * simulating the iconic Thanos disintegration effect.
 */
import { useEffect, useState, useCallback } from 'react';
import { View, type LayoutChangeEvent } from 'react-native';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withDelay,
  withTiming,
  Easing,
  runOnJS,
} from 'react-native-reanimated';

const PARTICLE_COUNT = 28;
const ANIM_DURATION = 1300;

const DUST_COLORS = [
  'rgba(180,160,130,0.9)',
  'rgba(140,120,95,0.85)',
  'rgba(100,90,70,0.75)',
  'rgba(160,180,140,0.85)',
  'rgba(120,140,100,0.75)',
  'rgba(200,180,150,0.7)',
  'rgba(80,70,55,0.8)',
  'rgba(170,150,120,0.8)',
];

interface ParticleData {
  id: number;
  startX: number;
  startY: number;
  dx: number;
  dy: number;
  size: number;
  color: string;
  delay: number;
  rotation: number;
}

function generateParticles(w: number, h: number): ParticleData[] {
  return Array.from({ length: PARTICLE_COUNT }, (_, i) => {
    const startX = Math.random() * w;
    const startY = Math.random() * h;
    // Mostly rightward + slightly upward scatter
    const angle = (Math.random() * 0.7 - 0.15) * Math.PI;
    const dist = 50 + Math.random() * 90;

    return {
      id: i,
      startX,
      startY,
      dx: Math.cos(angle) * dist + 25,
      dy: -Math.abs(Math.sin(angle) * dist) - 8,
      size: 3 + Math.random() * 6,
      color: DUST_COLORS[Math.floor(Math.random() * DUST_COLORS.length)],
      // Left-to-right sweep: left particles start first
      delay: (startX / w) * 450 + Math.random() * 200,
      rotation: (Math.random() - 0.5) * 200,
    };
  });
}

function DustParticle({ data, baseDelay }: { data: ParticleData; baseDelay: number }) {
  const progress = useSharedValue(0);

  useEffect(() => {
    progress.value = withDelay(
      baseDelay + data.delay,
      withTiming(1, { duration: ANIM_DURATION, easing: Easing.out(Easing.cubic) }),
    );
  }, []);

  const style = useAnimatedStyle(() => {
    const p = progress.value;
    return {
      position: 'absolute' as const,
      left: data.startX + data.dx * p,
      top: data.startY + data.dy * p,
      width: data.size,
      height: data.size,
      borderRadius: 1,
      backgroundColor: data.color,
      opacity: p < 0.12 ? p / 0.12 : p < 0.45 ? 1 : 1 - (p - 0.45) / 0.55,
      transform: [
        { rotate: `${data.rotation * p}deg` },
        { scale: 1 - p * 0.35 },
      ],
    };
  });

  return <Animated.View style={style} />;
}

interface ThanosSnapProps {
  children: React.ReactNode;
  snap: boolean;
  delay?: number;
  onComplete?: () => void;
}

export function ThanosSnap({ children, snap, delay = 0, onComplete }: ThanosSnapProps) {
  const [dims, setDims] = useState({ w: 0, h: 0 });
  const [particles, setParticles] = useState<ParticleData[]>([]);
  const contentOpacity = useSharedValue(1);

  const handleLayout = useCallback((e: LayoutChangeEvent) => {
    const { width, height } = e.nativeEvent.layout;
    if (width > 0 && height > 0) setDims({ w: width, h: height });
  }, []);

  useEffect(() => {
    if (snap && dims.w > 0 && dims.h > 0) {
      setParticles(generateParticles(dims.w, dims.h));
      contentOpacity.value = withDelay(
        delay + 250,
        withTiming(
          0,
          { duration: ANIM_DURATION * 0.75, easing: Easing.in(Easing.quad) },
          (finished) => {
            if (finished && onComplete) runOnJS(onComplete)();
          },
        ),
      );
    }
  }, [snap, dims]);

  const contentStyle = useAnimatedStyle(() => ({
    opacity: contentOpacity.value,
  }));

  return (
    <View onLayout={handleLayout} style={{ overflow: 'visible' }}>
      <Animated.View style={contentStyle}>{children}</Animated.View>
      {particles.length > 0 && (
        <View
          pointerEvents="none"
          style={{
            position: 'absolute',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            overflow: 'visible',
          }}
        >
          {particles.map((p) => (
            <DustParticle key={p.id} data={p} baseDelay={delay} />
          ))}
        </View>
      )}
    </View>
  );
}
