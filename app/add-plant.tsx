/**
 * Phase 4: Add Plant form.
 *
 * react-hook-form + zod validation, photo picker, date pickers,
 * collapsible advanced care section, FormData submission.
 */
import { useState, type ReactNode } from 'react';
import {
  View,
  Text,
  TextInput,
  ScrollView,
  Pressable,
  KeyboardAvoidingView,
  Platform,
  ActivityIndicator,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Ionicons } from '@expo/vector-icons';
import { useColors } from '../hooks/useColors';
import { useAlertDialog } from '../components/AlertDialog';
import { useUserScopedQueryKey } from '../hooks/useUserScopedQueryKey';
import { apiRequest } from '../lib/api';
import { todayString } from '../lib/utils';
import { DatePickerInput } from '../components/DatePickerInput';
import { ImagePickerField } from '../components/ImagePickerField';

// ---------------------------------------------------------------------------
// Local form schema — uses z.preprocess so TextInput string values coerce to
// numbers automatically before zod validation runs.
// ---------------------------------------------------------------------------

const optNum = z.preprocess(
  (v) => (v === '' || v === undefined || v === null ? undefined : Number(v)),
  z.number().int().min(1).optional(),
);

const formSchema = z.object({
  name: z.string().min(1, 'Введите название'),
  location: z.string().optional().default(''),
  water_frequency_days: z.preprocess(
    (v) => (v === '' ? NaN : Number(v)),
    z.number({ invalid_type_error: 'Введите число' }).int().min(1, 'Минимум 1 день'),
  ),
  last_watered_date: z.string().min(1, 'Выберите дату'),
  notes: z.string().optional(),
  fertilize_frequency_days: optNum,
  last_fertilized_date: z.string().optional(),
  repot_frequency_months: optNum,
  last_repotted_date: z.string().optional(),
  prune_frequency_months: optNum,
  last_pruned_date: z.string().optional(),
});

type PlantFormData = z.infer<typeof formSchema>;

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

export default function AddPlantScreen() {
  const { t } = useTranslation();
  const colors = useColors();
  const router = useRouter();
  const { showAlert } = useAlertDialog();
  const getUserScopedQueryKey = useUserScopedQueryKey();
  const queryClient = useQueryClient();
  const plantsQueryKey = getUserScopedQueryKey('/api/plants');

  const [photoUri, setPhotoUri] = useState<string | null>(null);
  const [photoBase64, setPhotoBase64] = useState<string | null>(null);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const {
    control,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<PlantFormData>({
    resolver: zodResolver(formSchema) as any,
    defaultValues: {
      name: '',
      location: '',
      water_frequency_days: '' as any,
      last_watered_date: todayString(),
      notes: '',
    },
  });

  // ---------------------------------------------------------------------------
  // Submit — sends FormData so the photo file is included as multipart
  // ---------------------------------------------------------------------------

  const onSubmit = async (data: PlantFormData) => {
    setSubmitting(true);
    try {
      // Empty string when no photo — backend defaults to '' and views
      // render a leaf-icon placeholder.
      const photo_url = photoBase64
        ? `data:image/jpeg;base64,${photoBase64}`
        : '';

      const body: Record<string, any> = {
        name: data.name,
        location: data.location || '',
        photo_url,
        water_frequency_days: data.water_frequency_days,
        last_watered_date: data.last_watered_date,
        notes: data.notes || '',
      };
      if (data.fertilize_frequency_days) body.fertilize_frequency_days = data.fertilize_frequency_days;
      if (data.last_fertilized_date) body.last_fertilized_date = data.last_fertilized_date;
      if (data.repot_frequency_months) body.repot_frequency_months = data.repot_frequency_months;
      if (data.last_repotted_date) body.last_repotted_date = data.last_repotted_date;
      if (data.prune_frequency_months) body.prune_frequency_months = data.prune_frequency_months;
      if (data.last_pruned_date) body.last_pruned_date = data.last_pruned_date;

      await apiRequest('POST', '/api/plants', body);

      await queryClient.invalidateQueries({ queryKey: plantsQueryKey });
      router.back();
    } catch (err: any) {
      showAlert(t('addPlant.uploadFailed'), err?.message || String(err));
    } finally {
      setSubmitting(false);
    }
  };

  // ---------------------------------------------------------------------------
  // Style helpers
  // ---------------------------------------------------------------------------

  const inputStyle = {
    backgroundColor: colors.card,
    borderColor: colors.border,
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 13,
    fontSize: 15,
    color: colors.foreground,
  } as const;

  const labelStyle = {
    fontSize: 12,
    fontWeight: '600' as const,
    color: colors.mutedForeground,
    marginBottom: 6,
    textTransform: 'uppercase' as const,
    letterSpacing: 0.5,
  };

  const errorStyle = { fontSize: 12, color: colors.destructive, marginTop: 4 };

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }} edges={['bottom']}>
      <KeyboardAvoidingView
        style={{ flex: 1 }}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        {/* Header */}
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            paddingHorizontal: 16,
            paddingVertical: 14,
            borderBottomWidth: 1,
            borderBottomColor: colors.border,
            gap: 12,
          }}
        >
          <Pressable
            onPress={() => router.back()}
            style={({ pressed }) => ({ opacity: pressed ? 0.6 : 1 })}
          >
            <Ionicons name="arrow-back" size={24} color={colors.foreground} />
          </Pressable>
          <Text style={{ fontSize: 18, fontWeight: '700', color: colors.foreground, flex: 1 }}>
            {t('addPlant.title')}
          </Text>
        </View>

        <ScrollView
          contentContainerStyle={{ padding: 20, paddingBottom: 40, gap: 20 }}
          showsVerticalScrollIndicator={false}
          keyboardShouldPersistTaps="handled"
        >
          {/* ── Photo ── */}
          <View style={{ alignItems: 'center' }}>
            <ImagePickerField
              uri={photoUri}
              onSelect={(uri, b64) => { setPhotoUri(uri); setPhotoBase64(b64); }}
              onClear={() => { setPhotoUri(null); setPhotoBase64(null); }}
            />
          </View>

          {/* ── Name ── */}
          <View>
            <Text style={labelStyle}>{t('addPlant.nameLabel')}</Text>
            <Controller
              control={control}
              name="name"
              render={({ field: { onChange, onBlur, value } }) => (
                <TextInput
                  style={[inputStyle, errors.name && { borderColor: colors.destructive }]}
                  placeholder={t('addPlant.namePlaceholder')}
                  placeholderTextColor={colors.mutedForeground}
                  value={value}
                  onChangeText={onChange}
                  onBlur={onBlur}
                  returnKeyType="next"
                />
              )}
            />
            {errors.name && <Text style={errorStyle}>{errors.name.message}</Text>}
          </View>

          {/* ── Location ── */}
          <View>
            <Text style={labelStyle}>{t('addPlant.locationLabel')}</Text>
            <Controller
              control={control}
              name="location"
              render={({ field: { onChange, onBlur, value } }) => (
                <TextInput
                  style={inputStyle}
                  placeholder={t('addPlant.locationPlaceholder')}
                  placeholderTextColor={colors.mutedForeground}
                  value={value}
                  onChangeText={onChange}
                  onBlur={onBlur}
                  returnKeyType="next"
                />
              )}
            />
          </View>

          {/* ── Watering ── */}
          <SectionDivider label="💧 Полив" colors={colors} />
          <View style={{ gap: 14 }}>
            <View>
              <Text style={labelStyle}>{t('addPlant.wateringLabel')}</Text>
              <Controller
                control={control}
                name="water_frequency_days"
                render={({ field: { onChange, onBlur, value } }) => (
                  <TextInput
                    style={[
                      inputStyle,
                      errors.water_frequency_days && { borderColor: colors.destructive },
                    ]}
                    placeholder="7"
                    placeholderTextColor={colors.mutedForeground}
                    value={value !== undefined && value !== ('' as any) ? String(value) : ''}
                    onChangeText={onChange}
                    onBlur={onBlur}
                    keyboardType="numeric"
                    returnKeyType="done"
                  />
                )}
              />
              {errors.water_frequency_days && (
                <Text style={errorStyle}>{errors.water_frequency_days.message}</Text>
              )}
            </View>

            <View>
              <Text style={labelStyle}>{t('addPlant.lastWateredLabel')}</Text>
              <Controller
                control={control}
                name="last_watered_date"
                render={({ field: { value } }) => (
                  <DatePickerInput
                    value={value || null}
                    onChange={(date) => setValue('last_watered_date', date)}
                    placeholder={t('addPlant.pickDate')}
                  />
                )}
              />
              {errors.last_watered_date && (
                <Text style={errorStyle}>{errors.last_watered_date.message}</Text>
              )}
            </View>
          </View>

          {/* ── Advanced care (collapsible) ── */}
          <SectionDivider label="" colors={colors} />
          <Pressable
            onPress={() => setAdvancedOpen((v) => !v)}
            style={({ pressed }) => ({
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'space-between',
              opacity: pressed ? 0.7 : 1,
            })}
          >
            <View style={{ flex: 1, gap: 2 }}>
              <Text style={{ fontSize: 15, fontWeight: '700', color: colors.foreground }}>
                {t('addPlant.additionalCare')}
              </Text>
              <Text style={{ fontSize: 12, color: colors.mutedForeground }}>
                {t('addPlant.additionalCareHint')}
              </Text>
            </View>
            <Ionicons
              name={advancedOpen ? 'chevron-up' : 'chevron-down'}
              size={20}
              color={colors.mutedForeground}
            />
          </Pressable>

          {advancedOpen && (
            <View style={{ gap: 20 }}>
              {/* Fertilize */}
              <CareSubSection label="🌱 Удобрение" colors={colors}>
                <View>
                  <Text style={labelStyle}>{t('addPlant.fertilizing')}</Text>
                  <Controller
                    control={control}
                    name="fertilize_frequency_days"
                    render={({ field: { onChange, onBlur, value } }) => (
                      <TextInput
                        style={inputStyle}
                        placeholder={t('addPlant.fertilizePlaceholder')}
                        placeholderTextColor={colors.mutedForeground}
                        value={value !== undefined ? String(value) : ''}
                        onChangeText={onChange}
                        onBlur={onBlur}
                        keyboardType="numeric"
                        returnKeyType="done"
                      />
                    )}
                  />
                </View>
                <View>
                  <Text style={labelStyle}>{t('addPlant.lastFertilized')}</Text>
                  <Controller
                    control={control}
                    name="last_fertilized_date"
                    render={({ field: { value } }) => (
                      <DatePickerInput
                        value={value || null}
                        onChange={(date) => setValue('last_fertilized_date', date)}
                        placeholder={t('addPlant.pickDate')}
                      />
                    )}
                  />
                </View>
              </CareSubSection>

              {/* Repot */}
              <CareSubSection label="🪴 Пересадка" colors={colors}>
                <View>
                  <Text style={labelStyle}>{t('addPlant.repotting')}</Text>
                  <Controller
                    control={control}
                    name="repot_frequency_months"
                    render={({ field: { onChange, onBlur, value } }) => (
                      <TextInput
                        style={inputStyle}
                        placeholder={t('addPlant.repotPlaceholder')}
                        placeholderTextColor={colors.mutedForeground}
                        value={value !== undefined ? String(value) : ''}
                        onChangeText={onChange}
                        onBlur={onBlur}
                        keyboardType="numeric"
                        returnKeyType="done"
                      />
                    )}
                  />
                </View>
                <View>
                  <Text style={labelStyle}>{t('addPlant.lastRepotted')}</Text>
                  <Controller
                    control={control}
                    name="last_repotted_date"
                    render={({ field: { value } }) => (
                      <DatePickerInput
                        value={value || null}
                        onChange={(date) => setValue('last_repotted_date', date)}
                        placeholder={t('addPlant.pickDate')}
                      />
                    )}
                  />
                </View>
              </CareSubSection>

              {/* Prune */}
              <CareSubSection label="✂️ Обрезка" colors={colors}>
                <View>
                  <Text style={labelStyle}>{t('addPlant.pruning')}</Text>
                  <Controller
                    control={control}
                    name="prune_frequency_months"
                    render={({ field: { onChange, onBlur, value } }) => (
                      <TextInput
                        style={inputStyle}
                        placeholder={t('addPlant.prunePlaceholder')}
                        placeholderTextColor={colors.mutedForeground}
                        value={value !== undefined ? String(value) : ''}
                        onChangeText={onChange}
                        onBlur={onBlur}
                        keyboardType="numeric"
                        returnKeyType="done"
                      />
                    )}
                  />
                </View>
                <View>
                  <Text style={labelStyle}>{t('addPlant.lastPruned')}</Text>
                  <Controller
                    control={control}
                    name="last_pruned_date"
                    render={({ field: { value } }) => (
                      <DatePickerInput
                        value={value || null}
                        onChange={(date) => setValue('last_pruned_date', date)}
                        placeholder={t('addPlant.pickDate')}
                      />
                    )}
                  />
                </View>
              </CareSubSection>
            </View>
          )}

          {/* ── Notes ── */}
          <SectionDivider label={t('addPlant.notesLabel')} colors={colors} />
          <Controller
            control={control}
            name="notes"
            render={({ field: { onChange, onBlur, value } }) => (
              <TextInput
                style={[inputStyle, { minHeight: 90, textAlignVertical: 'top', paddingTop: 12 }]}
                placeholder={t('addPlant.notesPlaceholder')}
                placeholderTextColor={colors.mutedForeground}
                value={value}
                onChangeText={onChange}
                onBlur={onBlur}
                multiline
                numberOfLines={4}
              />
            )}
          />

          {/* ── Submit ── */}
          <Pressable
            onPress={handleSubmit(onSubmit)}
            disabled={submitting}
            style={({ pressed }) => ({
              backgroundColor: colors.primary,
              borderRadius: 12,
              paddingVertical: 15,
              alignItems: 'center',
              justifyContent: 'center',
              flexDirection: 'row',
              gap: 8,
              opacity: pressed || submitting ? 0.75 : 1,
              marginTop: 4,
            })}
          >
            {submitting ? (
              <ActivityIndicator color={colors.primaryForeground} />
            ) : (
              <>
                <Ionicons name="leaf" size={18} color={colors.primaryForeground} />
                <Text
                  style={{ color: colors.primaryForeground, fontSize: 16, fontWeight: '700' }}
                >
                  {t('addPlant.add')}
                </Text>
              </>
            )}
          </Pressable>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

// =============================================================================
// Helper components
// =============================================================================

function SectionDivider({
  label,
  colors,
}: {
  label: string;
  colors: ReturnType<typeof import('../hooks/useColors').useColors>;
}) {
  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        gap: 10,
        marginTop: 4,
      }}
    >
      {label ? (
        <Text style={{ fontSize: 15, fontWeight: '700', color: colors.foreground }}>
          {label}
        </Text>
      ) : null}
      <View style={{ flex: 1, height: 1, backgroundColor: colors.border }} />
    </View>
  );
}

function CareSubSection({
  label,
  children,
  colors,
}: {
  label: string;
  children: ReactNode;
  colors: ReturnType<typeof import('../hooks/useColors').useColors>;
}) {
  return (
    <View
      style={{
        backgroundColor: colors.card,
        borderColor: colors.cardBorder,
        borderWidth: 1,
        borderRadius: 12,
        padding: 14,
        gap: 12,
      }}
    >
      <Text style={{ fontSize: 14, fontWeight: '600', color: colors.foreground }}>
        {label}
      </Text>
      {children}
    </View>
  );
}
