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
  FlatList,
  Pressable,
  Image,
  TextInput,
  Alert,
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
import Animated, { FadeIn, FadeInDown } from 'react-native-reanimated';
import { LinearGradient } from 'expo-linear-gradient';
import { useColors } from '../../hooks/useColors';
import { useAuth } from '../../contexts/AuthContext';
import { getDaysUntilWatering, getWateringStatus, todayString } from '../../lib/utils';
import { apiRequest, TimeoutError, NetworkError } from '../../lib/api';
import { LAYOUT_MODE_STORE_KEY } from '../../lib/constants';
import { SkeletonLoader } from '../../components/SkeletonPlaceholder';
import { WaterButtonWithParticles } from '../../components/WaterButtonWithParticles';
import type { Plant } from '../../shared/schema';

type Filter = 'all' | 'needsWater' | 'healthy';
type ViewMode = 'list' | 'card' | 'grid';

const GRID_PADDING = 16;
const GRID_GAP = 10;
const GRID_COLS = 3;

export default function DashboardScreen() {
  const { t } = useTranslation();
  const colors = useColors();
  const router = useRouter();
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const { width: screenWidth } = useWindowDimensions();

  const [searchQuery, setSearchQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [viewMode, setViewMode] = useState<ViewMode>('list');

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

  // ---------------------------------------------------------------------------
  // Queries & mutations
  // ---------------------------------------------------------------------------

  const { data: plants, isLoading, error, refetch, isRefetching } = useQuery<Plant[]>({
    queryKey: ['/api/plants'],
  });

  const handleMutationError = useCallback((err: unknown) => {
    const msg =
      err instanceof TimeoutError
        ? t('errors.timeout')
        : err instanceof NetworkError
          ? t('errors.network')
          : (err as Error)?.message || t('errors.unknown');
    Alert.alert(t('errors.actionFailed'), msg);
  }, [t]);

  const waterMutation = useMutation({
    mutationFn: async (plantId: string) => {
      await apiRequest('PATCH', `/api/plants/${plantId}`, {
        last_watered_date: todayString(),
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['/api/plants'] });
    },
    onError: handleMutationError,
  });

  const waterAllMutation = useMutation({
    mutationFn: async () => {
      const res = await apiRequest('POST', '/api/plants/water-all');
      return res.json() as Promise<{ count: number }>;
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['/api/plants'] });
      Alert.alert(t('plant.watered'), t('dashboard.plantsWatered', { count: data.count }));
    },
    onError: handleMutationError,
  });

  const postponeAllMutation = useMutation({
    mutationFn: async () => {
      const res = await apiRequest('POST', '/api/plants/postpone-all');
      return res.json() as Promise<{ count: number }>;
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['/api/plants'] });
      Alert.alert(t('common.done'), t('dashboard.plantsPostponed', { count: data.count }));
    },
    onError: handleMutationError,
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

      return (
        <Animated.View entering={FadeInDown.delay(index * 60).duration(350).springify()}>
          <Pressable
            onPress={() => router.push(`/plant/${item.id}`)}
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
                  backgroundColor: statusBg,
                  paddingHorizontal: 8,
                  paddingVertical: 3,
                  borderRadius: 6,
                  alignSelf: 'flex-start',
                }}
              >
                <Ionicons name="water-outline" size={11} color={statusColor} />
                <Text style={{ fontSize: 11, fontWeight: '600', color: statusColor }}>
                  {statusText}
                </Text>
              </View>
            </View>

            {status !== 'healthy' ? (
              <WaterButtonWithParticles
                compact
                onWater={() => waterMutation.mutate(item.id)}
                isWatering={isWatering}
                colors={colors}
              />
            ) : (
              <Ionicons name="checkmark-circle" size={22} color={colors.primary + '66'} />
            )}
          </Pressable>
        </Animated.View>
      );
    },
    [colors, t, router, waterMutation, getStatusInfo],
  );

  // ---------------------------------------------------------------------------
  // Render: CARD mode (gradient overlay on photo)
  // ---------------------------------------------------------------------------

  const renderCardItem = useCallback(
    ({ item, index }: { item: Plant; index: number }) => {
      const { status, statusColor, statusBg, statusText } = getStatusInfo(item);
      const isWatering = waterMutation.isPending && waterMutation.variables === item.id;

      return (
        <Animated.View entering={FadeInDown.delay(index * 80).duration(400).springify()}>
          <Pressable
            onPress={() => router.push(`/plant/${item.id}`)}
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
                    backgroundColor: statusBg,
                    paddingHorizontal: 10,
                    paddingVertical: 4,
                    borderRadius: 8,
                  }}
                >
                  <Ionicons name="water-outline" size={12} color={statusColor} />
                  <Text style={{ fontSize: 12, fontWeight: '600', color: statusColor }}>
                    {statusText}
                  </Text>
                </View>
              </View>

              {/* Full-width water button with particles */}
              {status !== 'healthy' && (
                <WaterButtonWithParticles
                  onWater={() => waterMutation.mutate(item.id)}
                  isWatering={isWatering}
                  label={t('plant.water')}
                  colors={colors}
                />
              )}
            </View>
          </Pressable>
        </Animated.View>
      );
    },
    [colors, t, router, waterMutation, getStatusInfo],
  );

  // ---------------------------------------------------------------------------
  // Render: GRID mode (diegetic)
  // ---------------------------------------------------------------------------

  const renderGridItem = useCallback(
    ({ item, index }: { item: Plant; index: number }) => {
      const { daysUntil, status, accentColor } = getStatusInfo(item);

      // Grid badge: overdue → "!5" (days late), today → "💧", healthy → remaining days.
      let badgeText: string;
      let badgeColor: string;
      if (status === 'overdue') {
        badgeText = `!${Math.abs(daysUntil)}`;
        badgeColor = '#ff6b6b';
      } else if (status === 'today') {
        badgeText = '💧';
        badgeColor = '#fbbf24';
      } else {
        badgeText = String(daysUntil);
        badgeColor = '#ffffff';
      }

      return (
        <Animated.View entering={FadeInDown.delay(index * 40).duration(300).springify()}>
          <Pressable
            onPress={() => router.push(`/plant/${item.id}`)}
            style={({ pressed }) => ({
              width: gridCellSize,
              height: gridCellSize,
              borderRadius: 8,
              overflow: 'hidden',
              borderLeftWidth: 3,
              borderLeftColor: accentColor,
              opacity: pressed ? 0.75 : 1,
              marginBottom: GRID_GAP,
            })}
          >
            {item.photo_url ? (
              <Image
                source={{ uri: item.photo_url }}
                style={{ width: '100%', height: '100%' }}
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

            <View
              style={{
                position: 'absolute',
                bottom: 5,
                right: 5,
                backgroundColor: 'rgba(0,0,0,0.65)',
                paddingHorizontal: 6,
                paddingVertical: 3,
                borderRadius: 5,
                minWidth: 22,
                alignItems: 'center',
              }}
            >
              <Text
                style={{
                  fontSize: 12,
                  fontWeight: '800',
                  color: badgeColor,
                }}
              >
                {badgeText}
              </Text>
            </View>
          </Pressable>
        </Animated.View>
      );
    },
    [colors, router, gridCellSize, getStatusInfo],
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
    const isTimeout = error instanceof TimeoutError;
    const isNetwork = error instanceof NetworkError;
    const iconName = isTimeout ? 'time-outline' : isNetwork ? 'cloud-offline-outline' : 'alert-circle-outline';
    const messageKey = isTimeout ? 'errors.timeout' : isNetwork ? 'errors.network' : 'errors.loadFailed';

    return (
      <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
        <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center', padding: 32, gap: 16 }}>
          <Ionicons name={iconName} size={56} color={colors.mutedForeground} />
          <Text
            style={{ fontSize: 16, color: colors.foreground, textAlign: 'center', lineHeight: 22 }}
          >
            {t(messageKey)}
          </Text>
          <Pressable
            onPress={() => refetch()}
            disabled={isRefetching}
            style={({ pressed }) => ({
              flexDirection: 'row',
              alignItems: 'center',
              gap: 8,
              backgroundColor: colors.primary,
              borderRadius: 10,
              paddingHorizontal: 22,
              paddingVertical: 12,
              opacity: pressed || isRefetching ? 0.75 : 1,
              marginTop: 4,
            })}
          >
            {isRefetching ? (
              <ActivityIndicator color={colors.primaryForeground} size="small" />
            ) : (
              <Ionicons name="refresh" size={18} color={colors.primaryForeground} />
            )}
            <Text style={{ color: colors.primaryForeground, fontSize: 15, fontWeight: '600' }}>
              {t('common.retry')}
            </Text>
          </Pressable>
        </View>
      </SafeAreaView>
    );
  }

  // ---------------------------------------------------------------------------
  // Select render function
  // ---------------------------------------------------------------------------

  const renderItem =
    viewMode === 'card'
      ? renderCardItem
      : viewMode === 'grid'
        ? renderGridItem
        : renderListItem;

  const hasPlants = plants && plants.length > 0;

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
        </View>

        {hasPlants && (
          <View style={{ flexDirection: 'row', gap: 2, marginTop: 4 }}>
            <ViewModeButton
              icon="list"
              active={viewMode === 'list'}
              onPress={() => changeViewMode('list')}
              colors={colors}
            />
            <ViewModeButton
              icon="albums"
              active={viewMode === 'card'}
              onPress={() => changeViewMode('card')}
              colors={colors}
            />
            <ViewModeButton
              icon="grid"
              active={viewMode === 'grid'}
              onPress={() => changeViewMode('grid')}
              colors={colors}
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
                <Pressable onPress={() => setSearchQuery('')}>
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
                disabled={waterAllMutation.isPending}
                style={({ pressed }) => ({
                  flex: 1,
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: 6,
                  backgroundColor: colors.primary,
                  borderRadius: 10,
                  paddingVertical: 10,
                  opacity: pressed || waterAllMutation.isPending ? 0.7 : 1,
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

          {/* Plant list / cards / grid — crossfade on mode switch */}
          {filteredPlants.length > 0 ? (
            <Animated.View
              key={viewMode}
              entering={FadeIn.duration(250)}
              style={{ flex: 1 }}
            >
              <FlatList
                data={filteredPlants}
                keyExtractor={(item) => item.id}
                renderItem={renderItem}
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
                refreshControl={
                  <RefreshControl
                    refreshing={isRefetching}
                    onRefresh={refetch}
                    tintColor={colors.primary}
                    colors={[colors.primary]}
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
}: {
  icon: 'list' | 'albums' | 'grid';
  active: boolean;
  onPress: () => void;
  colors: ReturnType<typeof useColors>;
}) {
  return (
    <Pressable
      onPress={onPress}
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
