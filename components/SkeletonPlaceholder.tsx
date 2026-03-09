/**
 * Skeleton loading placeholders for the Dashboard.
 * Pulses opacity via Reanimated to indicate loading state.
 * Renders different layouts for list / card / grid view modes.
 */
import { useEffect } from 'react';
import { View } from 'react-native';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withRepeat,
  withSequence,
  withTiming,
} from 'react-native-reanimated';

type ViewMode = 'list' | 'card' | 'grid';

interface SkeletonLoaderProps {
  viewMode: ViewMode;
  colors: {
    muted: string;
    card: string;
    cardBorder: string;
  };
  screenWidth: number;
}

const GRID_PADDING = 16;
const GRID_GAP = 7;
const GRID_COLS = 3;

function useSkeletonPulse() {
  const opacity = useSharedValue(0.35);

  useEffect(() => {
    opacity.value = withRepeat(
      withSequence(
        withTiming(1, { duration: 800 }),
        withTiming(0.35, { duration: 800 }),
      ),
      -1,
      false,
    );
  }, []);

  return useAnimatedStyle(() => ({ opacity: opacity.value }));
}

function Bone({
  width,
  height,
  borderRadius = 6,
  color,
  style,
  animatedStyle,
}: {
  width: number | string;
  height: number;
  borderRadius?: number;
  color: string;
  style?: object;
  animatedStyle: object;
}) {
  return (
    <Animated.View
      style={[
        {
          width: width as any,
          height,
          borderRadius,
          backgroundColor: color,
        },
        style,
        animatedStyle,
      ]}
    />
  );
}

// ---------------------------------------------------------------------------
// List skeleton: 6 rows
// ---------------------------------------------------------------------------

function SkeletonList({ colors, animatedStyle }: { colors: SkeletonLoaderProps['colors']; animatedStyle: object }) {
  return (
    <View style={{ paddingHorizontal: 20, gap: 10 }}>
      {Array.from({ length: 6 }).map((_, i) => (
        <View
          key={i}
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            backgroundColor: colors.card,
            borderColor: colors.cardBorder,
            borderWidth: 1,
            borderRadius: 14,
            padding: 12,
            gap: 12,
          }}
        >
          <Bone width={56} height={56} borderRadius={10} color={colors.muted} animatedStyle={animatedStyle} />
          <View style={{ flex: 1, gap: 8 }}>
            <Bone width="65%" height={14} color={colors.muted} animatedStyle={animatedStyle} />
            <Bone width="40%" height={10} color={colors.muted} animatedStyle={animatedStyle} />
            <Bone width={80} height={18} borderRadius={6} color={colors.muted} animatedStyle={animatedStyle} />
          </View>
          <Bone width={40} height={40} borderRadius={20} color={colors.muted} animatedStyle={animatedStyle} />
        </View>
      ))}
    </View>
  );
}

// ---------------------------------------------------------------------------
// Card skeleton: 2 big cards
// ---------------------------------------------------------------------------

function SkeletonCards({ colors, animatedStyle }: { colors: SkeletonLoaderProps['colors']; animatedStyle: object }) {
  return (
    <View style={{ paddingHorizontal: 20, gap: 16 }}>
      {Array.from({ length: 2 }).map((_, i) => (
        <View
          key={i}
          style={{
            backgroundColor: colors.card,
            borderColor: colors.cardBorder,
            borderWidth: 1,
            borderRadius: 16,
            overflow: 'hidden',
          }}
        >
          <Bone width="100%" height={280} borderRadius={0} color={colors.muted} animatedStyle={animatedStyle} />
          <View style={{ padding: 14, gap: 10 }}>
            <Bone width="55%" height={16} color={colors.muted} animatedStyle={animatedStyle} />
            <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
              <Bone width="35%" height={12} color={colors.muted} animatedStyle={animatedStyle} />
              <Bone width={80} height={22} borderRadius={8} color={colors.muted} animatedStyle={animatedStyle} />
            </View>
            <Bone width="100%" height={38} borderRadius={10} color={colors.muted} animatedStyle={animatedStyle} />
          </View>
        </View>
      ))}
    </View>
  );
}

// ---------------------------------------------------------------------------
// Grid skeleton: 3x3 squares
// ---------------------------------------------------------------------------

function SkeletonGrid({
  colors,
  animatedStyle,
  screenWidth,
}: {
  colors: SkeletonLoaderProps['colors'];
  animatedStyle: object;
  screenWidth: number;
}) {
  const cellSize = Math.floor(
    (screenWidth - GRID_PADDING * 2 - GRID_GAP * (GRID_COLS - 1)) / GRID_COLS,
  );

  return (
    <View
      style={{
        flexDirection: 'row',
        flexWrap: 'wrap',
        paddingHorizontal: GRID_PADDING,
        gap: GRID_GAP,
      }}
    >
      {Array.from({ length: 9 }).map((_, i) => (
        <Bone
          key={i}
          width={cellSize}
          height={cellSize}
          borderRadius={8}
          color={colors.muted}
          animatedStyle={animatedStyle}
        />
      ))}
    </View>
  );
}

// ---------------------------------------------------------------------------
// Main export
// ---------------------------------------------------------------------------

export function SkeletonLoader({ viewMode, colors, screenWidth }: SkeletonLoaderProps) {
  const animatedStyle = useSkeletonPulse();

  if (viewMode === 'card') {
    return <SkeletonCards colors={colors} animatedStyle={animatedStyle} />;
  }
  if (viewMode === 'grid') {
    return <SkeletonGrid colors={colors} animatedStyle={animatedStyle} screenWidth={screenWidth} />;
  }
  return <SkeletonList colors={colors} animatedStyle={animatedStyle} />;
}
