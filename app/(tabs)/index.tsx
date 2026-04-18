/**
 * Dashboard — Plants tab (Phase 3 + Polish).
 *
 * Three view modes: list / card / grid
 * Animations: FadeInDown stagger, view mode crossfade, water particles
 * Skeleton loading, card gradient overlay, pull-to-refresh
 */
import { useState, useMemo, useCallback, useEffect } from 'react';
import {
  View,
  Text,
  Pressable,
  Image,
  TextInput,
  RefreshControl,
  ActivityIndicator,
  useWindowDimensions,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Ionicons } from '@expo/vector-icons';
import AsyncStorage from '@react-native-async-storage/async-storage';
import Animated, {
  FadeIn,
  FadeInDown,
  LinearTransition,
} from 'react-native-reanimated';
import { LinearGradient } from 'expo-linear-gradient';
import { useColors } from '../../hooks/useColors';
import { useAlertDialog } from '../../components/AlertDialog';
import { useUserScopedQueryKey } from '../../hooks/useUserScopedQueryKey';
import { useAuth } from '../../contexts/AuthContext';
import { getDaysUntilWatering, getWateringStatus, todayString } from '../../lib/utils';
import { apiRequest } from '../../lib/api';
import { LAYOUT_MODE_STORE_KEY } from '../../lib/constants';
import { SkeletonLoader } from '../../components/SkeletonPlaceholder';
import { WaterButtonWithParticles } from '../../components/WaterButtonWithParticles';
import { ThanosSnap } from '../../components/ThanosSnap';
import type { Plant } from '../../shared/schema';

type Filter = 'all' | 'needsWater' | 'healthy';
type ViewMode = 'list' | 'card' | 'grid';

const GRID_PADDING = 16;
const GRID_GAP = 10;
const GRID_COLS = 3;
const BULK_WATER_SNAP_MS = 1150;
const BULK_WATER_SUCCESS_BANNER_MS = 2400;

export default function DashboardScreen() {
  const { t } = useTranslation();
  const colors = useColors();
  const { showAlert } = useAlertDialog();
  const router = useRouter();
  const { user } = useAuth();
  const getUserScopedQueryKey = useUserScopedQueryKey();
  const queryClient = useQueryClient();
  const { width: screenWidth } = useWindowDimensions();
  const plantsQueryKey = getUserScopedQueryKey('/api/plants');

  const [searchQuery, setSearchQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [viewMode, setViewMode] = useState<ViewMode>('list');
  const [isManualRefreshing, setIsManualRefreshing] = useState(false);
  const [pendingBulkWaterIds, setPendingBulkWaterIds] = useState<Set<string>>(new Set());
  const [snappingIds, setSnappingIds] = useState<Set<string>>(new Set());
  const [waterAllSuccessCount, setWaterAllSuccessCount] = useState<number | null>(null);

  const gridCellSize = Math.floor(
    (screenWidth - GRID_PADDING * 2 - GRID_GAP * (GRID_COLS - 1)) / GRID_COLS,
  );

  // ---------------------------------------------------------------------------
  // Persist view mode
  // ---------------------------------------------------------------------------

  useEffect(() => {
    AsyncStorage.getItem(LAYOUT_MODE_STORE_KEY).then((mode) => {
      if (mode === 'list' || mode === 'card' || mode === 'grid') setViewMode(mode);
    });
  }, []);

  const changeViewMode = useCallback((mode: ViewMode) => {
    setViewMode(mode);
    AsyncStorage.setItem(LAYOUT_MODE_STORE_KEY, mode);
  }, []);

  useEffect(() => {
    if (waterAllSuccessCount === null) return;

    const timeout = setTimeout(() => {
      setWaterAllSuccessCount(null);
    }, BULK_WATER_SUCCESS_BANNER_MS);

    return () => clearTimeout(timeout);
  }, [waterAllSuccessCount]);

  // ---------------------------------------------------------------------------
  // Queries & mutations
  // ---------------------------------------------------------------------------

  const { data: plants, isLoading, error, refetch, dataUpdatedAt } = useQuery<Plant[]>({
    queryKey: plantsQueryKey,
  });

  const handleRefresh = useCallback(async () => {
    setIsManualRefreshing(true);
    await refetch();
    setIsManualRefreshing(false);
  }, [refetch]);

  const waterMutation = useMutation({
    mutationFn: async (plantId: string) => {
      await apiRequest('PATCH', `/api/plants/${plantId}`, {
        last_watered_date: todayString(),
      });
    },
    onMutate: async (plantId) => {
      await queryClient.cancelQueries({ queryKey: plantsQueryKey });
      const previous = queryClient.getQueryData<Plant[]>(plantsQueryKey);
      queryClient.setQueryData<Plant[]>(plantsQueryKey, (old) =>
        old?.map((p) =>
          p.id === plantId ? { ...p, last_watered_date: todayString() } : p
        )
      );
      return { previous };
    },
    onError: (_err, _plantId, context) => {
      if (context?.previous) {
        queryClient.setQueryData(plantsQueryKey, context.previous);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: plantsQueryKey });
    },
  });

  const waterAllMutation = useMutation({
    mutationFn: async () => {
      const res = await apiRequest('POST', '/api/plants/water-all');
      return res.json() as Promise<{ count: number }>;
    },
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: plantsQueryKey });
      const previous = queryClient.getQueryData<Plant[]>(plantsQueryKey);
      const ids = (previous || [])
        .filter((p) => getWateringStatus(p.last_watered_date, p.water_frequency_days) !== 'healthy')
        .map((p) => p.id);

      setWaterAllSuccessCount(null);
      setSnappingIds(new Set());
      setPendingBulkWaterIds(new Set(ids));

      return { previous, ids };
    },
    onSuccess: async (data, _vars, context) => {
      const ids = context?.ids ?? [];

      setPendingBulkWaterIds(new Set());
      if (ids.length === 0) {
        queryClient.invalidateQueries({ queryKey: plantsQueryKey });
        return;
      }

      setWaterAllSuccessCount(data.count || ids.length);
      setSnappingIds(new Set(ids));

      await new Promise((resolve) => setTimeout(resolve, BULK_WATER_SNAP_MS));

      queryClient.setQueryData<Plant[]>(plantsQueryKey, (old) =>
        old?.map((p) =>
          ids.includes(p.id) ? { ...p, last_watered_date: todayString() } : p
        )
      );
      setSnappingIds(new Set());
      queryClient.invalidateQueries({ queryKey: plantsQueryKey });
    },
    onError: (err, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData(plantsQueryKey, context.previous);
      }
      setPendingBulkWaterIds(new Set());
      setSnappingIds(new Set());
      setWaterAllSuccessCount(null);
      const message = err instanceof Error ? err.message : 'Ошибка массового полива';
      showAlert(t('common.error'), message);
    },
  });

  const postponeAllMutation = useMutation({
    mutationFn: async () => {
      const res = await apiRequest('POST', '/api/plants/postpone-all');
      return res.json() as Promise<{ count: number }>;
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: plantsQueryKey });
    },
  });

  // ---------------------------------------------------------------------------
  // Filtered & sorted plants
  // ---------------------------------------------------------------------------

  const filteredPlants = useMemo(() => {
    let result = plants || [];

    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      result = result.filter(
        (p) =>
          p.name.toLowerCase().includes(q) ||
          p.location.toLowerCase().includes(q),
      );
    }

    if (filter === 'needsWater') {
      result = result.filter(
        (p) => getWateringStatus(p.last_watered_date, p.water_frequency_days) !== 'healthy',
      );
    } else if (filter === 'healthy') {
      result = result.filter(
        (p) => getWateringStatus(p.last_watered_date, p.water_frequency_days) === 'healthy',
      );
    }

    return [...result].sort(
      (a, b) =>
        getDaysUntilWatering(a.last_watered_date, a.water_frequency_days) -
        getDaysUntilWatering(b.last_watered_date, b.water_frequency_days),
    );
  }, [plants, searchQuery, filter]);

  const needsWaterCount = useMemo(
    () =>
      (plants || []).filter(
        (p) => getWateringStatus(p.last_watered_date, p.water_frequency_days) !== 'healthy',
      ).length,
    [plants],
  );

  const bulkWaterPendingCount = pendingBulkWaterIds.size;
  const isBulkWaterBusy =
    waterAllMutation.isPending || bulkWaterPendingCount > 0 || snappingIds.size > 0;

  // ---------------------------------------------------------------------------
  // Status helpers
  // ---------------------------------------------------------------------------

  const getStatusInfo = useCallback(
    (item: Plant) => {
      const daysUntil = getDaysUntilWatering(item.last_watered_date, item.water_frequency_days);
      const status = getWateringStatus(item.last_watered_date, item.water_frequency_days);

      let statusColor: string = colors.primary;
      let statusBg: string = colors.primary + '18';
      let statusText = t('plant.daysLeft', { count: daysUntil });

      if (status === 'overdue') {
        statusColor = colors.destructive;
        statusBg = colors.destructive + '18';
        statusText = t('plant.overdue', { count: Math.abs(daysUntil) });
      } else if (status === 'today') {
        statusColor = colors.amber;
        statusBg = colors.amberBg;
        statusText = t('plant.waterToday');
      }

      const accentColor: string =
        status === 'overdue'
          ? colors.destructive
          : status === 'today'
            ? colors.amber
            : colors.primary;

      return { daysUntil, status, statusColor, statusBg, statusText, accentColor };
    },
    [colors, t],
  );

  // ---------------------------------------------------------------------------
  // Render: LIST mode
  // ---------------------------------------------------------------------------

  const renderListItem = useCallback(
    ({ item, index }: { item: Plant; index: number }) => {
      const { status, statusColor, statusBg, statusText, accentColor } = getStatusInfo(item);
      const isWatering = waterMutation.isPending && waterMutation.variables === item.id;
      const isBulkProcessing = pendingBulkWaterIds.has(item.id) || snappingIds.has(item.id);
      const effectiveStatusColor = isBulkProcessing ? colors.primary : statusColor;
      const effectiveStatusBg = isBulkProcessing ? colors.primary + '18' : statusBg;
      const effectiveStatusText = isBulkProcessing ? t('dashboard.wateringNow') : statusText;

      return (
        <Animated.View entering={FadeInDown.delay(index * 60).duration(350).springify()} layout={LinearTransition.springify()}>
          <Pressable
            onPress={() => router.push(`/plant/${item.id}`)}
            accessibilityRole="button"
            accessibilityLabel={t('a11y.openPlant', { name: item.name })}
            style={({ pressed }) => ({
              flexDirection: 'row',
              alignItems: 'center',
              backgroundColor: colors.card,
              borderColor: colors.cardBorder,
              borderWidth: 1,
              borderRadius: 14,
              borderLeftWidth: 3,
              borderLeftColor: accentColor,
              padding: 12,
              marginBottom: 10,
              gap: 12,
              opacity: pressed ? 0.85 : 1,
            })}
          >
            {item.photo_url ? (
              <Image
                source={{ uri: item.photo_url }}
                style={{ width: 56, height: 56, borderRadius: 10 }}
                resizeMode="cover"
              />
            ) : (
              <View
                style={{
                  width: 56,
                  height: 56,
                  borderRadius: 10,
                  backgroundColor: colors.muted,
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <Ionicons name="leaf" size={24} color={colors.mutedForeground} />
              </View>
            )}

            <View style={{ flex: 1, gap: 4 }}>
              <Text
                style={{ fontSize: 16, fontWeight: '600', color: colors.foreground }}
                numberOfLines={1}
              >
                {item.name}
              </Text>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
                <Ionicons name="location-outline" size={12} color={colors.mutedForeground} />
                <Text style={{ fontSize: 12, color: colors.mutedForeground }} numberOfLines={1}>
                  {item.location}
                </Text>
              </View>
              <View
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: 4,
                  backgroundColor: effectiveStatusBg,
                  paddingHorizontal: 8,
                  paddingVertical: 3,
                  borderRadius: 6,
                  alignSelf: 'flex-start',
                }}
              >
                <Ionicons name="water-outline" size={11} color={effectiveStatusColor} />
                <Text style={{ fontSize: 11, fontWeight: '600', color: effectiveStatusColor }}>
                  {effectiveStatusText}
                </Text>
              </View>
            </View>

            {isBulkProcessing ? (
              <View style={{ width: 32, alignItems: 'center', justifyContent: 'center' }}>
                <ActivityIndicator size="small" color={colors.primary} />
              </View>
            ) : status !== 'healthy' ? (
              <WaterButtonWithParticles
                compact
                onWater={() => waterMutation.mutate(item.id)}
                isWatering={isWatering}
                accessibilityLabel={t('a11y.waterPlant', { name: item.name })}
                colors={colors}
              />
            ) : (
              <Ionicons name="checkmark-circle" size={22} color={colors.primary + '66'} />
            )}
          </Pressable>
        </Animated.View>
      );
    },
    [colors, t, router, waterMutation, getStatusInfo, pendingBulkWaterIds, snappingIds],
  );

  // ---------------------------------------------------------------------------
  // Render: CARD mode (gradient overlay on photo)
  // ---------------------------------------------------------------------------

  const renderCardItem = useCallback(
    ({ item, index }: { item: Plant; index: number }) => {
      const { status, statusColor, statusBg, statusText } = getStatusInfo(item);
      const isWatering = waterMutation.isPending && waterMutation.variables === item.id;
      const isBulkProcessing = pendingBulkWaterIds.has(item.id) || snappingIds.has(item.id);
      const effectiveStatusColor = isBulkProcessing ? colors.primary : statusColor;
      const effectiveStatusBg = isBulkProcessing ? colors.primary + '18' : statusBg;
      const effectiveStatusText = isBulkProcessing ? t('dashboard.wateringNow') : statusText;

      return (
        <Animated.View entering={FadeInDown.delay(index * 80).duration(400).springify()} layout={LinearTransition.springify()}>
          <Pressable
            onPress={() => router.push(`/plant/${item.id}`)}
            accessibilityRole="button"
            accessibilityLabel={t('a11y.openPlant', { name: item.name })}
            style={({ pressed }) => ({
              backgroundColor: colors.card,
              borderColor: colors.cardBorder,
              borderWidth: 1,
              borderRadius: 16,
              overflow: 'hidden',
              marginBottom: 16,
              opacity: pressed ? 0.92 : 1,
            })}
          >
            {/* Photo with gradient overlay and name */}
            <View style={{ aspectRatio: 1, backgroundColor: colors.muted }}>
              {item.photo_url ? (
                <Image
                  source={{ uri: item.photo_url }}
                  style={{ width: '100%', height: '100%' }}
                  resizeMode="cover"
                />
              ) : (
                <View
                  style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}
                >
                  <Ionicons name="leaf" size={56} color={colors.mutedForeground} />
                </View>
              )}

              {/* Gradient + name overlay */}
              <LinearGradient
                colors={['transparent', 'rgba(0,0,0,0.7)']}
                style={{
                  position: 'absolute',
                  bottom: 0,
                  left: 0,
                  right: 0,
                  height: '45%',
                  justifyContent: 'flex-end',
                  paddingHorizontal: 14,
                  paddingBottom: 12,
                }}
              >
                <Text
                  style={{ fontSize: 20, fontWeight: '700', color: '#ffffff' }}
                  numberOfLines={1}
                >
                  {item.name}
                </Text>
              </LinearGradient>
            </View>

            {/* Content below photo */}
            <View style={{ padding: 14, gap: 10 }}>
              <View
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                }}
              >
                <View
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: 4,
                    flex: 1,
                  }}
                >
                  <Ionicons name="location-outline" size={14} color={colors.mutedForeground} />
                  <Text
                    style={{ fontSize: 13, color: colors.mutedForeground }}
                    numberOfLines={1}
                  >
                    {item.location}
                  </Text>
                </View>

                <View
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: 4,
                    backgroundColor: effectiveStatusBg,
                    paddingHorizontal: 10,
                    paddingVertical: 4,
                    borderRadius: 8,
                  }}
                >
                  <Ionicons name="water-outline" size={12} color={effectiveStatusColor} />
                  <Text style={{ fontSize: 12, fontWeight: '600', color: effectiveStatusColor }}>
                    {effectiveStatusText}
                  </Text>
                </View>
              </View>

              {/* Full-width water button with particles */}
              {isBulkProcessing ? (
                <View
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: 8,
                    backgroundColor: colors.primary + '12',
                    borderRadius: 12,
                    paddingVertical: 12,
                  }}
                >
                  <ActivityIndicator size="small" color={colors.primary} />
                  <Text style={{ fontSize: 13, fontWeight: '600', color: colors.primary }}>
                    {t('dashboard.wateringNow')}
                  </Text>
                </View>
              ) : status !== 'healthy' && (
                <WaterButtonWithParticles
                  onWater={() => waterMutation.mutate(item.id)}
                  isWatering={isWatering}
                  label={t('plant.water')}
                  accessibilityLabel={t('a11y.waterPlant', { name: item.name })}
                  colors={colors}
                />
              )}
            </View>
          </Pressable>
        </Animated.View>
      );
    },
    [colors, t, router, waterMutation, getStatusInfo, pendingBulkWaterIds, snappingIds],
  );

  // ---------------------------------------------------------------------------
  // Render: GRID mode (diegetic)
  // ---------------------------------------------------------------------------

  const renderGridItem = useCallback(
    ({ item, index }: { item: Plant; index: number }) => {
      const { daysUntil, status, accentColor } = getStatusInfo(item);
      const isWatering = waterMutation.isPending && waterMutation.variables === item.id;
      const isBulkProcessing = pendingBulkWaterIds.has(item.id) || snappingIds.has(item.id);
      const needsWater = status !== 'healthy';

      const displayNumber = String(daysUntil);

      const numberColor =
        status === 'overdue'
          ? '#ff6b6b'
          : status === 'today'
            ? '#fbbf24'
            : '#ffffff';

      return (
        <Animated.View entering={FadeInDown.delay(index * 40).duration(300).springify()} layout={LinearTransition.springify().damping(11).stiffness(90)}>
          <View style={{ width: gridCellSize, height: gridCellSize, marginBottom: GRID_GAP }}>
            <Pressable
              onPress={() => router.push(`/plant/${item.id}`)}
              accessibilityRole="button"
              accessibilityLabel={t('a11y.openPlant', { name: item.name })}
              style={({ pressed }) => ({
                width: '100%',
                height: '100%',
                borderRadius: 8,
                overflow: 'hidden',
                borderLeftWidth: 3,
                borderLeftColor: accentColor,
                opacity: pressed ? 0.75 : 1,
                backgroundColor: '#000',
              })}
            >
              {item.photo_url ? (
                <Image
                  source={{ uri: item.photo_url }}
                  style={{ width: '100%', height: '100%', borderRadius: 8 }}
                  resizeMode="cover"
                />
              ) : (
                <View
                  style={{
                    flex: 1,
                    backgroundColor: colors.muted,
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <Ionicons name="leaf" size={28} color={colors.mutedForeground} />
                </View>
              )}

              {/* Gradient with name at bottom — full height to avoid edge artifacts */}
              <LinearGradient
                colors={['transparent', 'transparent', 'rgba(0,0,0,0.85)']}
                locations={[0, 0.45, 1]}
                style={{
                  position: 'absolute',
                  top: 0,
                  bottom: 0,
                  left: 0,
                  right: 0,
                  justifyContent: 'flex-end',
                  paddingLeft: 6,
                  paddingRight: 6,
                  paddingBottom: 4,
                }}
              >
                <Text
                  numberOfLines={2}
                  style={{ fontSize: 10, fontWeight: '700', color: '#fff', lineHeight: 13 }}
                >
                  {item.name}
                </Text>
              </LinearGradient>

              {/* Days counter — bottom-right corner */}
              <View
                style={{
                  position: 'absolute',
                  bottom: 5,
                  right: 6,
                  backgroundColor: 'rgba(0,0,0,0.6)',
                  paddingHorizontal: 5,
                  paddingVertical: 2,
                  borderRadius: 4,
                  minWidth: 20,
                  alignItems: 'center',
                }}
              >
                <Text
                  style={{
                    fontSize: 11,
                    fontWeight: '800',
                    color: numberColor,
                  }}
                >
                  {displayNumber}
                </Text>
              </View>
            </Pressable>

            {/* Water button — top right (only for overdue/today) */}
            {isBulkProcessing ? (
              <View
                style={{
                  position: 'absolute',
                  top: 4,
                  right: 4,
                  zIndex: 10,
                  width: 28,
                  height: 28,
                  borderRadius: 14,
                  backgroundColor: 'rgba(15, 23, 42, 0.72)',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <ActivityIndicator size="small" color={colors.primary} />
              </View>
            ) : needsWater && (
              <View style={{ position: 'absolute', top: 4, right: 4, zIndex: 10 }}>
                <WaterButtonWithParticles
                  compact
                  onWater={() => waterMutation.mutate(item.id)}
                  isWatering={isWatering}
                  accessibilityLabel={t('a11y.waterPlant', { name: item.name })}
                  colors={colors}
                />
              </View>
            )}
          </View>
        </Animated.View>
      );
    },
    [colors, router, gridCellSize, getStatusInfo, waterMutation, pendingBulkWaterIds, snappingIds],
  );

  // ---------------------------------------------------------------------------
  // Select render function (must be before any early returns — Rules of Hooks)
  // ---------------------------------------------------------------------------

  const renderItemFinal = useCallback(
    (props: { item: Plant; index: number }) => {
      const base =
        viewMode === 'card'
          ? renderCardItem(props)
          : viewMode === 'grid'
            ? renderGridItem(props)
            : renderListItem(props);

      if (snappingIds.has(props.item.id)) {
        return (
          <ThanosSnap snap delay={props.index * 70}>
            {base}
          </ThanosSnap>
        );
      }
      return base;
    },
    [viewMode, renderCardItem, renderGridItem, renderListItem, snappingIds],
  );

  // ---------------------------------------------------------------------------
  // Loading / error states
  // ---------------------------------------------------------------------------

  if (isLoading) {
    return (
      <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
        <View style={{ paddingHorizontal: 20, paddingTop: 16, paddingBottom: 12 }}>
          <Text style={{ fontSize: 26, fontWeight: '700', color: colors.foreground }}>
            {t('dashboard.myPlants')}
          </Text>
        </View>
        <SkeletonLoader viewMode={viewMode} colors={colors} screenWidth={screenWidth} />
      </SafeAreaView>
    );
  }

  if (error) {
    return (
      <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
        <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 }}>
          <Text style={{ color: colors.destructive, textAlign: 'center' }}>
            {(error as Error).message}
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  const hasPlants = (plants?.length ?? 0) > 0;
  const lastSyncedAgeMs = dataUpdatedAt > 0 ? Date.now() - dataUpdatedAt : 0;
  const showLastSyncedBanner = hasPlants && lastSyncedAgeMs >= 60_000;
  const showLastSyncedIcon = lastSyncedAgeMs >= 300_000;
  const lastSyncedLabel = showLastSyncedBanner
    ? t('dashboard.lastSynced', {
      time: new Date(dataUpdatedAt).toLocaleTimeString(undefined, {
        hour: '2-digit',
        minute: '2-digit',
      }),
    })
    : null;

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
      {/* Header */}
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          paddingHorizontal: 20,
          paddingTop: 16,
          paddingBottom: 12,
        }}
      >
        <View style={{ flex: 1 }}>
          <Text style={{ fontSize: 26, fontWeight: '700', color: colors.foreground }}>
            {t('dashboard.myPlants')}
          </Text>
          {user?.name ? (
            <Text style={{ fontSize: 14, color: colors.mutedForeground, marginTop: 2 }}>
              {user.name}
            </Text>
          ) : null}
          {showLastSyncedBanner && lastSyncedLabel ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 4 }}>
              {showLastSyncedIcon ? (
                <Ionicons name="cloud-offline-outline" size={12} color={colors.mutedForeground} />
              ) : null}
              <Text style={{ fontSize: 11, color: colors.mutedForeground }}>
                {lastSyncedLabel}
              </Text>
            </View>
          ) : null}
        </View>

        {hasPlants && (
          <View style={{ flexDirection: 'row', gap: 2, marginTop: 4 }}>
            <ViewModeButton
              icon="list"
              active={viewMode === 'list'}
              onPress={() => changeViewMode('list')}
              colors={colors}
              accessibilityLabel={t('a11y.viewModeList')}
            />
            <ViewModeButton
              icon="albums"
              active={viewMode === 'card'}
              onPress={() => changeViewMode('card')}
              colors={colors}
              accessibilityLabel={t('a11y.viewModeCard')}
            />
            <ViewModeButton
              icon="grid"
              active={viewMode === 'grid'}
              onPress={() => changeViewMode('grid')}
              colors={colors}
              accessibilityLabel={t('a11y.viewModeGrid')}
            />
          </View>
        )}
      </View>

      {hasPlants ? (
        <>
          {/* Search bar */}
          <View style={{ paddingHorizontal: 20, marginBottom: 12 }}>
            <View
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                backgroundColor: colors.card,
                borderColor: colors.cardBorder,
                borderWidth: 1,
                borderRadius: 12,
                paddingHorizontal: 14,
                gap: 10,
              }}
            >
              <Ionicons name="search" size={18} color={colors.mutedForeground} />
              <TextInput
                value={searchQuery}
                onChangeText={setSearchQuery}
                placeholder={t('dashboard.search')}
                placeholderTextColor={colors.mutedForeground}
                style={{
                  flex: 1,
                  fontSize: 15,
                  color: colors.foreground,
                  paddingVertical: 12,
                }}
              />
              {searchQuery.length > 0 && (
                <Pressable
                  onPress={() => setSearchQuery('')}
                  accessibilityRole="button"
                  accessibilityLabel={t('a11y.clearSearch')}
                >
                  <Ionicons name="close-circle" size={18} color={colors.mutedForeground} />
                </Pressable>
              )}
            </View>
          </View>

          {/* Filter tabs */}
          <View
            style={{
              flexDirection: 'row',
              paddingHorizontal: 20,
              gap: 8,
              marginBottom: 12,
            }}
          >
            <FilterTab
              label={t('dashboard.all')}
              active={filter === 'all'}
              onPress={() => setFilter('all')}
              colors={colors}
            />
            <FilterTab
              label={t('dashboard.needsWater')}
              count={needsWaterCount}
              active={filter === 'needsWater'}
              onPress={() => setFilter('needsWater')}
              colors={colors}
            />
            <FilterTab
              label={t('dashboard.healthy')}
              active={filter === 'healthy'}
              onPress={() => setFilter('healthy')}
              colors={colors}
            />
          </View>

          {/* Water All / Postpone All */}
          {needsWaterCount > 0 && filter === 'needsWater' && (
            <View
              style={{
                flexDirection: 'row',
                paddingHorizontal: 20,
                gap: 10,
                marginBottom: 12,
              }}
            >
              <Pressable
                onPress={() => waterAllMutation.mutate()}
                disabled={isBulkWaterBusy}
                accessibilityRole="button"
                accessibilityLabel={t('a11y.waterAll')}
                accessibilityState={{ disabled: isBulkWaterBusy, busy: isBulkWaterBusy }}
                style={({ pressed }) => ({
                  flex: 1,
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: 6,
                  backgroundColor: colors.primary,
                  borderRadius: 10,
                  paddingVertical: 10,
                  opacity: pressed || isBulkWaterBusy ? 0.7 : 1,
                })}
              >
                <Ionicons name="water" size={16} color={colors.primaryForeground} />
                <Text
                  style={{
                    fontSize: 13,
                    fontWeight: '600',
                    color: colors.primaryForeground,
                  }}
                >
                  {t('dashboard.waterAll')}
                </Text>
              </Pressable>

              <Pressable
                onPress={() => postponeAllMutation.mutate()}
                disabled={postponeAllMutation.isPending}
                accessibilityRole="button"
                accessibilityLabel={t('a11y.postponeAll')}
                accessibilityState={{
                  disabled: postponeAllMutation.isPending,
                  busy: postponeAllMutation.isPending,
                }}
                style={({ pressed }) => ({
                  flex: 1,
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: 6,
                  backgroundColor: colors.muted,
                  borderRadius: 10,
                  paddingVertical: 10,
                  opacity: pressed || postponeAllMutation.isPending ? 0.7 : 1,
                })}
              >
                <Ionicons name="time-outline" size={16} color={colors.mutedForeground} />
                <Text
                  style={{
                    fontSize: 13,
                    fontWeight: '600',
                    color: colors.mutedForeground,
                  }}
                >
                  {t('dashboard.postponeAll')}
                </Text>
              </Pressable>
            </View>
          )}

          {(bulkWaterPendingCount > 0 || waterAllSuccessCount !== null) && (
            <View style={{ paddingHorizontal: 20, marginBottom: 12 }}>
              <Animated.View
                entering={FadeInDown.duration(250)}
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: 10,
                  backgroundColor:
                    bulkWaterPendingCount > 0 ? colors.primary + '12' : colors.primary + '18',
                  borderColor: colors.primary + '24',
                  borderWidth: 1,
                  borderRadius: 12,
                  paddingHorizontal: 14,
                  paddingVertical: 10,
                }}
              >
                {bulkWaterPendingCount > 0 ? (
                  <ActivityIndicator size="small" color={colors.primary} />
                ) : (
                  <Ionicons name="checkmark-circle" size={18} color={colors.primary} />
                )}
                <Text
                  style={{
                    flex: 1,
                    fontSize: 13,
                    fontWeight: '600',
                    color: colors.foreground,
                  }}
                >
                  {bulkWaterPendingCount > 0
                    ? t('dashboard.wateringAllPending', { count: bulkWaterPendingCount })
                    : `${waterAllSuccessCount} ${t('dashboard.plantsWatered')}`}
                </Text>
              </Animated.View>
            </View>
          )}

          {/* Plant list / cards / grid — crossfade on mode switch */}
          {filteredPlants.length > 0 ? (
            <Animated.View
              key={viewMode}
              entering={FadeIn.duration(250)}
              style={{ flex: 1 }}
            >
              <Animated.FlatList
                data={filteredPlants}
                keyExtractor={(item: Plant) => item.id}
                renderItem={renderItemFinal}
                itemLayoutAnimation={viewMode === 'grid' ? LinearTransition.springify().damping(11).stiffness(90) : LinearTransition.springify()}
                numColumns={viewMode === 'grid' ? GRID_COLS : 1}
                {...(viewMode === 'grid' && {
                  columnWrapperStyle: {
                    gap: GRID_GAP,
                    paddingHorizontal: GRID_PADDING,
                  },
                })}
                contentContainerStyle={
                  viewMode === 'grid'
                    ? { paddingBottom: 100 }
                    : { paddingHorizontal: 20, paddingBottom: 100 }
                }
                showsVerticalScrollIndicator={false}
                bounces={true}
                overScrollMode="always"
                refreshControl={
                  <RefreshControl
                    refreshing={isManualRefreshing}
                    onRefresh={handleRefresh}
                    tintColor={colors.primary}
                    colors={[colors.primary]}
                    progressBackgroundColor={colors.card}
                  />
                }
              />
            </Animated.View>
          ) : (
            <View
              style={{
                flex: 1,
                justifyContent: 'center',
                alignItems: 'center',
                padding: 32,
                gap: 12,
              }}
            >
              <Ionicons name={searchQuery ? 'search' : 'funnel-outline'} size={40} color={colors.mutedForeground} />
              <Text style={{ fontSize: 16, fontWeight: '600', color: colors.foreground }}>
                {t('dashboard.noPlantsFound')}
              </Text>
              <Text
                style={{ fontSize: 14, color: colors.mutedForeground, textAlign: 'center' }}
              >
                {searchQuery ? t('dashboard.tryDifferentSearch') : t('dashboard.tryDifferentFilter')}
              </Text>
            </View>
          )}
        </>
      ) : (
        /* Empty state */
        <View
          style={{
            flex: 1,
            justifyContent: 'center',
            alignItems: 'center',
            padding: 32,
            gap: 16,
          }}
        >
          <View
            style={{
              width: 80,
              height: 80,
              borderRadius: 40,
              backgroundColor: colors.primary + '22',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Ionicons name="leaf" size={40} color={colors.primary} />
          </View>
          <Text style={{ fontSize: 18, fontWeight: '600', color: colors.foreground }}>
            {t('dashboard.noPlants')}
          </Text>
          <Text
            style={{
              fontSize: 14,
              color: colors.mutedForeground,
              textAlign: 'center',
              lineHeight: 20,
            }}
          >
            {t('dashboard.startTracking')}
          </Text>
          <Pressable
            onPress={() => router.push('/add-plant')}
            accessibilityRole="button"
            accessibilityLabel={t('a11y.addPlant')}
            style={({ pressed }) => ({
              backgroundColor: colors.primary,
              borderRadius: 10,
              paddingHorizontal: 24,
              paddingVertical: 12,
              flexDirection: 'row',
              alignItems: 'center',
              gap: 8,
              opacity: pressed ? 0.85 : 1,
            })}
          >
            <Ionicons name="add" size={20} color={colors.primaryForeground} />
            <Text
              style={{ color: colors.primaryForeground, fontSize: 15, fontWeight: '600' }}
            >
              {t('dashboard.addPlant')}
            </Text>
          </Pressable>
        </View>
      )}

      {/* FAB */}
      {hasPlants && (
        <Pressable
          onPress={() => router.push('/add-plant')}
          accessibilityRole="button"
          accessibilityLabel={t('a11y.addPlant')}
          style={({ pressed }) => ({
            position: 'absolute',
            bottom: 24,
            right: 24,
            width: 56,
            height: 56,
            borderRadius: 28,
            backgroundColor: colors.primary,
            alignItems: 'center',
            justifyContent: 'center',
            shadowColor: colors.primary,
            shadowOffset: { width: 0, height: 4 },
            shadowOpacity: 0.4,
            shadowRadius: 8,
            elevation: 8,
            opacity: pressed ? 0.85 : 1,
          })}
        >
          <Ionicons name="add" size={28} color={colors.primaryForeground} />
        </Pressable>
      )}
    </SafeAreaView>
  );
}

// =============================================================================
// Helper components
// =============================================================================

function ViewModeButton({
  icon,
  active,
  onPress,
  colors,
  accessibilityLabel,
}: {
  icon: 'list' | 'albums' | 'grid';
  active: boolean;
  onPress: () => void;
  colors: ReturnType<typeof useColors>;
  accessibilityLabel?: string;
}) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ selected: active }}
      style={{
        width: 34,
        height: 34,
        borderRadius: 8,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: active ? colors.primary + '20' : 'transparent',
      }}
    >
      <Ionicons
        name={active ? icon : (`${icon}-outline` as any)}
        size={18}
        color={active ? colors.primary : colors.mutedForeground}
      />
    </Pressable>
  );
}

function FilterTab({
  label,
  count,
  active,
  onPress,
  colors,
}: {
  label: string;
  count?: number;
  active: boolean;
  onPress: () => void;
  colors: ReturnType<typeof useColors>;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={{
        paddingHorizontal: 16,
        paddingVertical: 8,
        borderRadius: 20,
        backgroundColor: active ? colors.primary : colors.muted,
      }}
    >
      <Text
        style={{
          fontSize: 13,
          fontWeight: '600',
          color: active ? colors.primaryForeground : colors.mutedForeground,
        }}
      >
        {label}
        {count !== undefined && count > 0 ? ` (${count})` : ''}
      </Text>
    </Pressable>
  );
}
