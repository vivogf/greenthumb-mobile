/**
 * Cross-platform date picker button.
 * Android: shows native dialog.
 * iOS: shows spinner in a bottom modal.
 *
 * Props:
 *   value    — YYYY-MM-DD string or null
 *   onChange — called with YYYY-MM-DD string when user picks a date
 *   placeholder — text shown when value is null
 *   maximumDate — upper limit (defaults to today)
 */
import { useState } from 'react';
import {
  View,
  Text,
  Pressable,
  Platform,
  Modal,
} from 'react-native';
import DateTimePicker, { DateTimePickerEvent } from '@react-native-community/datetimepicker';
import { Ionicons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';
import { useColors } from '../hooks/useColors';

interface Props {
  value: string | null;
  onChange: (date: string) => void;
  placeholder?: string;
  maximumDate?: Date;
}

function toDateString(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/** Normalizes any date string to YYYY-MM-DD (strips time from ISO timestamps). */
function normalizeDate(val: string | null): string | null {
  if (!val) return null;
  if (val.includes('T')) return val.split('T')[0];
  return val;
}

export function DatePickerInput({
  value,
  onChange,
  placeholder = 'Выберите дату',
  maximumDate = new Date(),
}: Props) {
  const colors = useColors();
  const { t } = useTranslation();
  const [showPicker, setShowPicker] = useState(false);
  const normalized = normalizeDate(value);
  const [tempDate, setTempDate] = useState<Date>(normalized ? new Date(normalized + 'T12:00:00') : new Date());

  const displayText = normalized
    ? new Date(normalized + 'T12:00:00').toLocaleDateString('ru-RU', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
      })
    : placeholder;

  const handleOpen = () => {
    setTempDate(normalized ? new Date(normalized + 'T12:00:00') : new Date());
    setShowPicker(true);
  };

  const handleAndroidChange = (_evt: DateTimePickerEvent, selectedDate?: Date) => {
    setShowPicker(false);
    if (selectedDate) onChange(toDateString(selectedDate));
  };

  const handleIOSChange = (_evt: DateTimePickerEvent, selectedDate?: Date) => {
    if (selectedDate) setTempDate(selectedDate);
  };

  const handleIOSCancel = () => {
    setShowPicker(false);
  };

  const handleIOSSave = () => {
    setShowPicker(false);
    onChange(toDateString(tempDate));
  };

  return (
    <View>
      <Pressable
        onPress={handleOpen}
        style={({ pressed }) => ({
          flexDirection: 'row',
          alignItems: 'center',
          backgroundColor: colors.card,
          borderColor: colors.border,
          borderWidth: 1,
          borderRadius: 10,
          paddingHorizontal: 14,
          paddingVertical: 13,
          gap: 10,
          opacity: pressed ? 0.8 : 1,
        })}
      >
        <Ionicons name="calendar-outline" size={18} color={colors.mutedForeground} />
        <Text
          style={{
            flex: 1,
            fontSize: 15,
            color: normalized ? colors.foreground : colors.mutedForeground,
          }}
        >
          {displayText}
        </Text>
        <Ionicons name="chevron-down" size={16} color={colors.mutedForeground} />
      </Pressable>

      {/* Android: dialog appears directly */}
      {Platform.OS === 'android' && showPicker && (
        <DateTimePicker
          value={tempDate}
          mode="date"
          display="default"
          maximumDate={maximumDate}
          onChange={handleAndroidChange}
        />
      )}

      {/* iOS: spinner inside a bottom modal */}
      {Platform.OS === 'ios' && (
        <Modal visible={showPicker} transparent animationType="slide" onRequestClose={handleIOSCancel}>
          <Pressable
            style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.45)' }}
            onPress={handleIOSCancel}
          />
          <View style={{ backgroundColor: colors.card, paddingBottom: 32 }}>
            <View
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'space-between',
                paddingHorizontal: 20,
                paddingTop: 14,
                paddingBottom: 4,
              }}
            >
              <Pressable onPress={handleIOSCancel}>
                <Text style={{ color: colors.mutedForeground, fontSize: 16, fontWeight: '600' }}>
                  {t('common.cancel')}
                </Text>
              </Pressable>
              <Pressable onPress={handleIOSSave}>
                <Text style={{ color: colors.primary, fontSize: 16, fontWeight: '600' }}>
                  {t('common.save')}
                </Text>
              </Pressable>
            </View>
            <DateTimePicker
              value={tempDate}
              mode="date"
              display="spinner"
              maximumDate={maximumDate}
              onChange={handleIOSChange}
              style={{ height: 200 }}
            />
          </View>
        </Modal>
      )}
    </View>
  );
}
