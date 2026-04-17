/**
 * Phase 5: Plant Details screen.
 *
 * Features:
 * - Full plant info (photo, name, location, care schedule)
 * - Inline name editing (tap name → TextInput)
 * - Care action buttons: Water / Fertilize / Repot / Prune
 * - Care settings modal (edit frequencies + last dates)
 * - Notes inline editing
 * - Delete with confirmation Alert
 */
import { useState, useRef } from 'react';
import { differenceInCalendarDays, addDays, addMonths, parseISO, startOfDay } from 'date-fns';
import {
  View,
  Text,
  Image,
  TextInput,
  ScrollView,
  Pressable,
  Alert,
  Modal,
  ActivityIndicator,
  useWindowDimensions,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Ionicons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import { useColors } from '../../hooks/useColors';
import {
  getDaysUntilWatering,
  getWateringStatus,
  todayString,
  formatDate,
  daysAgo,
  isDateToday,
} from '../../lib/utils';
import { apiRequest } from '../../lib/api';
import { WaterButtonWithParticles } from '../../components/WaterButtonWithParticles';
import { DatePickerInput } from '../../components/DatePickerInput';
import type { Plant } from '../../shared/schema';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Days until next care for day-based frequency (fertilize). */
function daysUntilCare(lastDate: string | null, frequencyDays: number): number | null {
  if (!lastDate) return null;
  const next = addDays(parseISO(lastDate), frequencyDays);
  return differenceInCalendarDays(startOfDay(next), startOfDay(new Date()));
}

/** Days until next care for month-based frequency (repot/prune). Uses calendar months, matches backend. */
function daysUntilMonthCare(lastDate: string | null, frequencyMonths: number): number | null {
  if (!lastDate) return null;
  const next = addMonths(parseISO(lastDate), frequencyMonths);
  return differenceInCalendarDays(startOfDay(next), startOfDay(new Date()));
}

function careStatusColor(
  days: number | null,
  colors: { primary: string; amber: string; destructive: string; mutedForeground: string },
): string {
  if (days === null) return colors.mutedForeground;
  if (days < 0) return colors.destructive;
  if (days === 0) return colors.amber;
  return colors.primary;
}

function careDaysText(days: number | null, t: (key: string, opts?: any) => string): string {
  if (days === null) return t('plantDetails.careNotSet');
  if (days < 0) return t('plant.overdue', { count: Math.abs(days) });
  if (days === 0) return t('plantDetails.today');
  return t('plant.daysLeft', { count: days });
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

export default function PlantDetailScreen() {
  const { t, i18n } = useTranslation();
  const colors = useColors();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { id } = useLocalSearchParams<{ id: string }>();
  const { width: screenWidth } = useWindowDimensions();

  // Inline name editing
  const [isEditingName, setIsEditingName] = useState(false);
  const [editedName, setEditedName] = useState('');
  const nameInputRef = useRef<TextInput>(null);

  // Notes editing
  const [isEditingNotes, setIsEditingNotes] = useState(false);
  const [editedNotes, setEditedNotes] = useState('');

  // Care settings modal
  const [showSettings, setShowSettings] = useState(false);
  const [settingsForm, setSettingsForm] = useState({
    water_frequency_days: '',
    last_watered_date: null as string | null,
    fertilize_frequency_days: '',
    last_fertilized_date: null as string | null,
    repot_frequency_months: '',
    last_repotted_date: null as string | null,
    prune_frequency_months: '',
    last_pruned_date: null as string | null,
  });

  // ---------------------------------------------------------------------------
  // Query — no individual endpoint on backend, derive from plants list cache
  // ---------------------------------------------------------------------------

  const { data: plants, isLoading } = useQuery<Plant[]>({
    queryKey: ['/api/plants'],
  });
  const plant = plants?.find((p) => p.id === id) ?? null;
  const error = !isLoading && !plant;

  // ---------------------------------------------------------------------------
  // Mutations
  // ---------------------------------------------------------------------------

  const patchMutation = useMutation({
    mutationFn: async (data: Partial<Plant>) => {
      await apiRequest('PATCH', `/api/plants/${id}`, data);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['/api/plants'] });
    },
    onError: (err: Error) => {
      Alert.alert(t('common.error'), err.message);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: async () => {
      await apiRequest('DELETE', `/api/plants/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['/api/plants'] });
      router.replace('/');
    },
    onError: (err: Error) => {
      Alert.alert(t('common.error'), err.message);
    },
  });

  // ---------------------------------------------------------------------------
  // Name editing
  // ---------------------------------------------------------------------------

  const startEditName = () => {
    setEditedName(plant?.name ?? '');
    setIsEditingName(true);
    setTimeout(() => nameInputRef.current?.focus(), 50);
  };

  const saveName = () => {
    const trimmed = editedName.trim();
    if (trimmed && trimmed !== plant?.name) {
      patchMutation.mutate({ name: trimmed });
    }
    setIsEditingName(false);
  };

  // ---------------------------------------------------------------------------
  // Notes editing
  // ---------------------------------------------------------------------------

  const startEditNotes = () => {
    setEditedNotes(plant?.notes ?? '');
    setIsEditingNotes(true);
  };

  const saveNotes = () => {
    if (editedNotes !== (plant?.notes ?? '')) {
      patchMutation.mutate({ notes: editedNotes });
    }
    setIsEditingNotes(false);
  };

  // ---------------------------------------------------------------------------
  // Care settings modal
  // ---------------------------------------------------------------------------

  const openSettings = () => {
    if (!plant) return;
    setSettingsForm({
      water_frequency_days: String(plant.water_frequency_days),
      last_watered_date: plant.last_watered_date ?? null,
      fertilize_frequency_days: plant.fertilize_frequency_days
        ? String(plant.fertilize_frequency_days)
        : '',
      last_fertilized_date: plant.last_fertilized_date ?? null,
      repot_frequency_months: plant.repot_frequency_months
        ? String(plant.repot_frequency_months)
        : '',
      last_repotted_date: plant.last_repotted_date ?? null,
      prune_frequency_months: plant.prune_frequency_months
        ? String(plant.prune_frequency_months)
        : '',
      last_pruned_date: plant.last_pruned_date ?? null,
    });
    setShowSettings(true);
  };

  const saveSettings = () => {
    const parse = (s: string) => {
      const n = parseInt(s, 10);
      return isNaN(n) || n < 1 ? undefined : n;
    };
    const patch: Partial<Plant> = {
      water_frequency_days: parse(settingsForm.water_frequency_days) ?? plant!.water_frequency_days,
    };
    if (settingsForm.last_watered_date)
      patch.last_watered_date = settingsForm.last_watered_date;
    const fd = parse(settingsForm.fertilize_frequency_days);
    if (fd !== undefined) {
      patch.fertilize_frequency_days = fd;
    } else if (plant!.fertilize_frequency_days !== null) {
      patch.fertilize_frequency_days = null;
    }
    if (settingsForm.last_fertilized_date)
      patch.last_fertilized_date = settingsForm.last_fertilized_date;
    const rm = parse(settingsForm.repot_frequency_months);
    if (rm !== undefined) {
      patch.repot_frequency_months = rm;
    } else if (plant!.repot_frequency_months !== null) {
      patch.repot_frequency_months = null;
    }
    if (settingsForm.last_repotted_date)
      patch.last_repotted_date = settingsForm.last_repotted_date;
    const pm = parse(settingsForm.prune_frequency_months);
    if (pm !== undefined) {
      patch.prune_frequency_months = pm;
    } else if (plant!.prune_frequency_months !== null) {
      patch.prune_frequency_months = null;
    }
    if (settingsForm.last_pruned_date)
      patch.last_pruned_date = settingsForm.last_pruned_date;

    patchMutation.mutate(patch);
    setShowSettings(false);
  };

  // ---------------------------------------------------------------------------
  // Delete
  // ---------------------------------------------------------------------------

  const confirmDelete = () => {
    Alert.alert(
      t('plantDetails.deleteTitle'),
      t('plantDetails.deleteDescription', { name: plant?.name ?? '' }),
      [
        { text: t('plantDetails.cancel'), style: 'cancel' },
        {
          text: t('plantDetails.delete'),
          style: 'destructive',
          onPress: () => deleteMutation.mutate(),
        },
      ],
    );
  };

  // ---------------------------------------------------------------------------
  // Loading / error states
  // ---------------------------------------------------------------------------

  if (isLoading) {
    return (
      <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <ActivityIndicator color={colors.primary} size="large" />
        </View>
      </SafeAreaView>
    );
  }

  if (error || !plant) {
    return (
      <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: 32, gap: 16 }}>
          <Text style={{ color: colors.destructive, textAlign: 'center' }}>
            {t('plantDetails.plantNotFound')}
          </Text>
          <Pressable onPress={() => router.replace('/')}>
            <Text style={{ color: colors.primary }}>{t('plantDetails.goHome')}</Text>
          </Pressable>
        </View>
      </SafeAreaView>
    );
  }

  // ---------------------------------------------------------------------------
  // Derived values
  // ---------------------------------------------------------------------------

  const lang = i18n.language;
  const waterDays = getDaysUntilWatering(plant.last_watered_date, plant.water_frequency_days);
  const waterStatus = getWateringStatus(plant.last_watered_date, plant.water_frequency_days);
  const waterColor =
    waterStatus === 'overdue'
      ? colors.destructive
      : waterStatus === 'today'
        ? colors.amber
        : colors.primary;

  const wateredDaysAgo = daysAgo(plant.last_watered_date);
  const lastWateredLabel = isDateToday(plant.last_watered_date)
    ? t('plantDetails.today')
    : wateredDaysAgo === 1
      ? t('plantDetails.yesterday')
      : t('plantDetails.daysAgo', { count: wateredDaysAgo });

  const fertilizeDays =
    plant.fertilize_frequency_days
      ? daysUntilCare(plant.last_fertilized_date, plant.fertilize_frequency_days)
      : null;
  const repotDays =
    plant.repot_frequency_months
      ? daysUntilMonthCare(plant.last_repotted_date, plant.repot_frequency_months)
      : null;
  const pruneDays =
    plant.prune_frequency_months
      ? daysUntilMonthCare(plant.last_pruned_date, plant.prune_frequency_months)
      : null;

  const photoHeight = Math.round(screenWidth * 0.75);

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }} edges={['bottom']}>
      <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={{ paddingBottom: 40 }}>

        {/* ── Photo header ── */}
        <View style={{ height: photoHeight, backgroundColor: colors.muted }}>
          {plant.photo_url ? (
            <Image
              source={{ uri: plant.photo_url }}
              style={{ width: '100%', height: '100%' }}
              resizeMode="cover"
            />
          ) : (
            <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
              <Ionicons name="leaf" size={64} color={colors.mutedForeground} />
            </View>
          )}

          {/* Gradient overlay with back button + name */}
          <LinearGradient
            colors={['rgba(0,0,0,0.35)', 'transparent', 'rgba(0,0,0,0.7)']}
            style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
          >
            {/* Back button */}
            <SafeAreaView edges={['top']}>
              <Pressable
                onPress={() => router.back()}
                style={({ pressed }) => ({
                  margin: 16,
                  width: 38,
                  height: 38,
                  borderRadius: 19,
                  backgroundColor: 'rgba(0,0,0,0.4)',
                  alignItems: 'center',
                  justifyContent: 'center',
                  opacity: pressed ? 0.7 : 1,
                })}
              >
                <Ionicons name="arrow-back" size={20} color="#fff" />
              </Pressable>
            </SafeAreaView>

            {/* Plant name — tap to edit */}
            <View
              style={{
                position: 'absolute',
                bottom: 0,
                left: 0,
                right: 0,
                paddingHorizontal: 20,
                paddingBottom: 18,
              }}
            >
              {isEditingName ? (
                <TextInput
                  ref={nameInputRef}
                  value={editedName}
                  onChangeText={setEditedName}
                  onBlur={saveName}
                  onSubmitEditing={saveName}
                  returnKeyType="done"
                  style={{
                    fontSize: 26,
                    fontWeight: '700',
                    color: '#fff',
                    borderBottomWidth: 2,
                    borderBottomColor: colors.primary,
                    paddingBottom: 2,
                  }}
                />
              ) : (
                <Pressable onPress={startEditName} style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                  <Text style={{ fontSize: 26, fontWeight: '700', color: '#fff', flex: 1 }} numberOfLines={2}>
                    {plant.name}
                  </Text>
                  <Ionicons name="pencil" size={16} color="rgba(255,255,255,0.7)" />
                </Pressable>
              )}

              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 5, marginTop: 4 }}>
                <Ionicons name="location-outline" size={13} color="rgba(255,255,255,0.75)" />
                <Text style={{ fontSize: 13, color: 'rgba(255,255,255,0.75)' }}>
                  {plant.location}
                </Text>
              </View>
            </View>
          </LinearGradient>
        </View>

        <View style={{ padding: 20, gap: 20 }}>

          {/* ── Watering status ── */}
          <View
            style={{
              backgroundColor: colors.card,
              borderColor: colors.cardBorder,
              borderWidth: 1,
              borderRadius: 16,
              padding: 16,
              gap: 12,
            }}
          >
            <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
              <View style={{ gap: 3 }}>
                <Text style={{ fontSize: 12, color: colors.mutedForeground, fontWeight: '600', textTransform: 'uppercase', letterSpacing: 0.5 }}>
                  {t('plantDetails.lastWatered')}
                </Text>
                <Text style={{ fontSize: 15, color: colors.foreground, fontWeight: '600' }}>
                  {lastWateredLabel} · {formatDate(plant.last_watered_date, lang)}
                </Text>
              </View>
              <View
                style={{
                  paddingHorizontal: 12,
                  paddingVertical: 6,
                  borderRadius: 20,
                  backgroundColor: waterColor + '18',
                }}
              >
                <Text style={{ fontSize: 13, fontWeight: '700', color: waterColor }}>
                  {waterStatus === 'overdue'
                    ? t('plantDetails.daysAgo', { count: Math.abs(waterDays) })
                    : waterStatus === 'today'
                      ? t('plantDetails.today')
                      : t('plant.daysLeft', { count: waterDays })}
                </Text>
              </View>
            </View>

            <WaterButtonWithParticles
              onWater={() => patchMutation.mutate({ last_watered_date: todayString() })}
              isWatering={patchMutation.isPending && 'last_watered_date' in (patchMutation.variables ?? {})}
              label={t('plantDetails.waterPlant')}
              colors={colors}
            />
          </View>

          {/* ── Care actions 2×2 grid ── */}
          {(plant.fertilize_frequency_days ||
            plant.repot_frequency_months ||
            plant.prune_frequency_months) && (
            <View style={{ gap: 10 }}>
              <Text style={{ fontSize: 15, fontWeight: '700', color: colors.foreground }}>
                {t('plantDetails.advancedCare')}
              </Text>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
                {plant.fertilize_frequency_days ? (
                  <CareActionCard
                    icon="🌿"
                    title={t('plantDetails.fertilizing')}
                    daysText={careDaysText(fertilizeDays, t)}
                    statusColor={careStatusColor(fertilizeDays, colors)}
                    onPress={() => patchMutation.mutate({ last_fertilized_date: todayString() })}
                    isPending={patchMutation.isPending && 'last_fertilized_date' in (patchMutation.variables ?? {})}
                    colors={colors}
                  />
                ) : null}
                {plant.repot_frequency_months ? (
                  <CareActionCard
                    icon="🪴"
                    title={t('plantDetails.repotting')}
                    daysText={careDaysText(repotDays, t)}
                    statusColor={careStatusColor(repotDays, colors)}
                    onPress={() => patchMutation.mutate({ last_repotted_date: todayString() })}
                    isPending={patchMutation.isPending && 'last_repotted_date' in (patchMutation.variables ?? {})}
                    colors={colors}
                  />
                ) : null}
                {plant.prune_frequency_months ? (
                  <CareActionCard
                    icon="✂️"
                    title={t('plantDetails.pruning')}
                    daysText={careDaysText(pruneDays, t)}
                    statusColor={careStatusColor(pruneDays, colors)}
                    onPress={() => patchMutation.mutate({ last_pruned_date: todayString() })}
                    isPending={patchMutation.isPending && 'last_pruned_date' in (patchMutation.variables ?? {})}
                    colors={colors}
                  />
                ) : null}
              </View>
            </View>
          )}

          {/* ── Care settings button ── */}
          <Pressable
            onPress={openSettings}
            style={({ pressed }) => ({
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'space-between',
              backgroundColor: colors.card,
              borderColor: colors.cardBorder,
              borderWidth: 1,
              borderRadius: 12,
              padding: 14,
              opacity: pressed ? 0.8 : 1,
            })}
          >
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
              <Ionicons name="settings-outline" size={20} color={colors.primary} />
              <Text style={{ fontSize: 15, fontWeight: '600', color: colors.foreground }}>
                {t('plantDetails.careSettings')}
              </Text>
            </View>
            <Ionicons name="chevron-forward" size={18} color={colors.mutedForeground} />
          </Pressable>

          {/* ── Notes ── */}
          <View
            style={{
              backgroundColor: colors.card,
              borderColor: colors.cardBorder,
              borderWidth: 1,
              borderRadius: 16,
              padding: 16,
              gap: 10,
            }}
          >
            <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
              <Text style={{ fontSize: 15, fontWeight: '700', color: colors.foreground }}>
                {t('plantDetails.notes')}
              </Text>
              {!isEditingNotes && (
                <Pressable onPress={startEditNotes}>
                  <Text style={{ fontSize: 13, color: colors.primary, fontWeight: '600' }}>
                    {t('plantDetails.edit')}
                  </Text>
                </Pressable>
              )}
            </View>

            {isEditingNotes ? (
              <>
                <TextInput
                  value={editedNotes}
                  onChangeText={setEditedNotes}
                  multiline
                  style={{
                    backgroundColor: colors.muted,
                    borderRadius: 8,
                    padding: 12,
                    fontSize: 14,
                    color: colors.foreground,
                    minHeight: 80,
                    textAlignVertical: 'top',
                  }}
                  placeholder={t('plantDetails.notesPlaceholder')}
                  placeholderTextColor={colors.mutedForeground}
                />
                <View style={{ flexDirection: 'row', gap: 10, justifyContent: 'flex-end' }}>
                  <Pressable
                    onPress={() => setIsEditingNotes(false)}
                    style={({ pressed }) => ({
                      paddingHorizontal: 16,
                      paddingVertical: 8,
                      borderRadius: 8,
                      backgroundColor: colors.muted,
                      opacity: pressed ? 0.7 : 1,
                    })}
                  >
                    <Text style={{ color: colors.mutedForeground, fontWeight: '600' }}>
                      {t('plantDetails.cancel')}
                    </Text>
                  </Pressable>
                  <Pressable
                    onPress={saveNotes}
                    style={({ pressed }) => ({
                      paddingHorizontal: 16,
                      paddingVertical: 8,
                      borderRadius: 8,
                      backgroundColor: colors.primary,
                      opacity: pressed ? 0.7 : 1,
                    })}
                  >
                    <Text style={{ color: colors.primaryForeground, fontWeight: '600' }}>
                      {t('plantDetails.save')}
                    </Text>
                  </Pressable>
                </View>
              </>
            ) : (
              <Text
                style={{
                  fontSize: 14,
                  color: plant.notes ? colors.foreground : colors.mutedForeground,
                  lineHeight: 20,
                }}
              >
                {plant.notes || t('plantDetails.noNotes')}
              </Text>
            )}
          </View>

          {/* ── Delete ── */}
          <Pressable
            onPress={confirmDelete}
            disabled={deleteMutation.isPending}
            style={({ pressed }) => ({
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 8,
              paddingVertical: 14,
              borderRadius: 12,
              borderWidth: 1,
              borderColor: colors.destructive + '40',
              backgroundColor: colors.destructive + '10',
              opacity: pressed || deleteMutation.isPending ? 0.7 : 1,
            })}
          >
            {deleteMutation.isPending ? (
              <ActivityIndicator color={colors.destructive} />
            ) : (
              <>
                <Ionicons name="trash-outline" size={18} color={colors.destructive} />
                <Text style={{ color: colors.destructive, fontSize: 15, fontWeight: '600' }}>
                  {t('plant.delete')}
                </Text>
              </>
            )}
          </Pressable>
        </View>
      </ScrollView>

      {/* ── Care Settings Modal ── */}
      <Modal visible={showSettings} animationType="slide" presentationStyle="pageSheet" onRequestClose={() => setShowSettings(false)}>
        <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'space-between',
              paddingHorizontal: 20,
              paddingVertical: 14,
              borderBottomWidth: 1,
              borderBottomColor: colors.border,
            }}
          >
            <Pressable onPress={() => setShowSettings(false)}>
              <Text style={{ color: colors.mutedForeground, fontSize: 16 }}>
                {t('plantDetails.cancel')}
              </Text>
            </Pressable>
            <Text style={{ fontSize: 16, fontWeight: '700', color: colors.foreground }}>
              {t('plantDetails.careSettings')}
            </Text>
            <Pressable onPress={saveSettings} disabled={patchMutation.isPending}>
              {patchMutation.isPending ? (
                <ActivityIndicator color={colors.primary} />
              ) : (
                <Text style={{ color: colors.primary, fontSize: 16, fontWeight: '600' }}>
                  {t('plantDetails.save')}
                </Text>
              )}
            </Pressable>
          </View>

          <ScrollView
            contentContainerStyle={{ padding: 20, gap: 20 }}
            showsVerticalScrollIndicator={false}
            keyboardShouldPersistTaps="handled"
          >
            {/* Water frequency + last watered date */}
            <View style={{ gap: 10 }}>
              <SettingsField
                label={`💧 ${t('plantDetails.wateringFrequency')}`}
                value={settingsForm.water_frequency_days}
                onChangeText={(v) =>
                  setSettingsForm((f) => ({ ...f, water_frequency_days: v }))
                }
                colors={colors}
              />
              <View>
                <Text style={{ fontSize: 12, color: colors.mutedForeground, marginBottom: 4 }}>
                  {t('plantDetails.lastWatered')}
                </Text>
                <DatePickerInput
                  value={settingsForm.last_watered_date}
                  onChange={(d) => setSettingsForm((f) => ({ ...f, last_watered_date: d }))}
                  placeholder={t('addPlant.notSet')}
                />
              </View>
            </View>

            {/* Fertilize */}
            <View style={{ gap: 10 }}>
              <SettingsField
                label={`🌱 ${t('plantDetails.fertilizingFrequency')}`}
                hint={t('plantDetails.leaveEmpty')}
                value={settingsForm.fertilize_frequency_days}
                onChangeText={(v) =>
                  setSettingsForm((f) => ({ ...f, fertilize_frequency_days: v }))
                }
                colors={colors}
              />
              <View>
                <Text style={{ fontSize: 12, color: colors.mutedForeground, marginBottom: 4 }}>
                  {t('plantDetails.fertilizing')} · {t('plantDetails.lastTime')}
                </Text>
                <DatePickerInput
                  value={settingsForm.last_fertilized_date}
                  onChange={(d) => setSettingsForm((f) => ({ ...f, last_fertilized_date: d }))}
                  placeholder={t('addPlant.notSet')}
                />
              </View>
            </View>

            {/* Repot */}
            <View style={{ gap: 10 }}>
              <SettingsField
                label={`🪴 ${t('plantDetails.repottingFrequency')}`}
                hint={t('plantDetails.leaveEmpty')}
                value={settingsForm.repot_frequency_months}
                onChangeText={(v) =>
                  setSettingsForm((f) => ({ ...f, repot_frequency_months: v }))
                }
                colors={colors}
              />
              <View>
                <Text style={{ fontSize: 12, color: colors.mutedForeground, marginBottom: 4 }}>
                  {t('plantDetails.repotting')} · {t('plantDetails.lastTime')}
                </Text>
                <DatePickerInput
                  value={settingsForm.last_repotted_date}
                  onChange={(d) => setSettingsForm((f) => ({ ...f, last_repotted_date: d }))}
                  placeholder={t('addPlant.notSet')}
                />
              </View>
            </View>

            {/* Prune */}
            <View style={{ gap: 10 }}>
              <SettingsField
                label={`✂️ ${t('plantDetails.pruningFrequency')}`}
                hint={t('plantDetails.leaveEmpty')}
                value={settingsForm.prune_frequency_months}
                onChangeText={(v) =>
                  setSettingsForm((f) => ({ ...f, prune_frequency_months: v }))
                }
                colors={colors}
              />
              <View>
                <Text style={{ fontSize: 12, color: colors.mutedForeground, marginBottom: 4 }}>
                  {t('plantDetails.pruning')} · {t('plantDetails.lastTime')}
                </Text>
                <DatePickerInput
                  value={settingsForm.last_pruned_date}
                  onChange={(d) => setSettingsForm((f) => ({ ...f, last_pruned_date: d }))}
                  placeholder={t('addPlant.notSet')}
                />
              </View>
            </View>
          </ScrollView>
        </SafeAreaView>
      </Modal>
    </SafeAreaView>
  );
}

// =============================================================================
// Helper components
// =============================================================================

function CareActionCard({
  icon,
  title,
  daysText,
  statusColor,
  onPress,
  isPending,
  colors,
}: {
  icon: string;
  title: string;
  daysText: string;
  statusColor: string;
  onPress: () => void;
  isPending: boolean;
  colors: ReturnType<typeof import('../../hooks/useColors').useColors>;
}) {
  return (
    <Pressable
      onPress={onPress}
      disabled={isPending}
      style={({ pressed }) => ({
        flex: 1,
        minWidth: '45%',
        backgroundColor: colors.card,
        borderColor: colors.cardBorder,
        borderWidth: 1,
        borderRadius: 12,
        padding: 14,
        alignItems: 'center',
        gap: 6,
        opacity: pressed || isPending ? 0.7 : 1,
      })}
    >
      {isPending ? (
        <ActivityIndicator color={colors.primary} />
      ) : (
        <>
          <Text style={{ fontSize: 22 }}>{icon}</Text>
          <Text
            style={{
              fontSize: 12,
              fontWeight: '600',
              color: colors.foreground,
              textAlign: 'center',
            }}
          >
            {title}
          </Text>
          <Text style={{ fontSize: 11, color: statusColor, textAlign: 'center' }}>
            {daysText}
          </Text>
        </>
      )}
    </Pressable>
  );
}

function SettingsField({
  label,
  hint,
  value,
  onChangeText,
  colors,
}: {
  label: string;
  hint?: string;
  value: string;
  onChangeText: (v: string) => void;
  colors: ReturnType<typeof import('../../hooks/useColors').useColors>;
}) {
  return (
    <View>
      <Text
        style={{
          fontSize: 13,
          fontWeight: '600',
          color: colors.foreground,
          marginBottom: 4,
        }}
      >
        {label}
      </Text>
      {hint && (
        <Text style={{ fontSize: 11, color: colors.mutedForeground, marginBottom: 4 }}>
          {hint}
        </Text>
      )}
      <TextInput
        value={value}
        onChangeText={onChangeText}
        keyboardType="numeric"
        returnKeyType="done"
        style={{
          backgroundColor: colors.muted,
          borderColor: colors.border,
          borderWidth: 1,
          borderRadius: 8,
          paddingHorizontal: 12,
          paddingVertical: 10,
          fontSize: 15,
          color: colors.foreground,
        }}
      />
    </View>
  );
}
