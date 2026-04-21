/**
 * Square image picker with camera / gallery / remove actions.
 * Uses expo-image-picker for selection and expo-image-manipulator
 * to resize to 800×800 JPEG before returning the local URI.
 */
import { useState } from 'react';
import {
  View,
  Text,
  Pressable,
  Image,
  Modal,
  ActivityIndicator,
} from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import * as ImageManipulator from 'expo-image-manipulator';
import { Ionicons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';
import { useColors } from '../hooks/useColors';
import { useAlertDialog } from './AlertDialog';

interface Props {
  uri: string | null;
  onSelect: (uri: string, base64: string) => void;
  onClear: () => void;
}

export function ImagePickerField({ uri, onSelect, onClear }: Props) {
  const colors = useColors();
  const { t } = useTranslation();
  const { showAlert } = useAlertDialog();
  const [loading, setLoading] = useState(false);
  const [pickerVisible, setPickerVisible] = useState(false);

  const processAndSelect = async (result: ImagePicker.ImagePickerResult) => {
    if (result.canceled || !result.assets?.[0]) return;
    setLoading(true);
    try {
      const manipulated = await ImageManipulator.manipulateAsync(
        result.assets[0].uri,
        [{ resize: { width: 800, height: 800 } }],
        { compress: 0.8, format: ImageManipulator.SaveFormat.JPEG, base64: true },
      );
      onSelect(manipulated.uri, manipulated.base64 ?? '');
    } finally {
      setLoading(false);
    }
  };

  const openCamera = async () => {
    setPickerVisible(false);
    const { granted } = await ImagePicker.requestCameraPermissionsAsync();
    if (!granted) {
      showAlert(t('common.permissionDeniedTitle'), t('common.cameraPermissionDenied'));
      return;
    }
    const result = await ImagePicker.launchCameraAsync({
      allowsEditing: true,
      aspect: [1, 1],
      quality: 1,
    });
    await processAndSelect(result);
  };

  const openGallery = async () => {
    setPickerVisible(false);
    const { granted } = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!granted) {
      showAlert(t('common.permissionDeniedTitle'), t('common.galleryPermissionDenied'));
      return;
    }
    const result = await ImagePicker.launchImageLibraryAsync({
      allowsEditing: true,
      aspect: [1, 1],
      quality: 1,
    });
    await processAndSelect(result);
  };

  const handleRemove = () => {
    setPickerVisible(false);
    onClear();
  };

  const handlePress = () => setPickerVisible(true);

  const optionRow = (
    icon: keyof typeof Ionicons.glyphMap,
    label: string,
    onPress: () => void,
    danger?: boolean,
  ) => (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={label}
      style={({ pressed }) => ({
        flexDirection: 'row',
        alignItems: 'center',
        gap: 14,
        padding: 14,
        borderRadius: 10,
        backgroundColor: pressed ? colors.muted : 'transparent',
      })}
    >
      <Ionicons
        name={icon}
        size={22}
        color={danger ? colors.destructive : colors.primary}
      />
      <Text
        style={{
          fontSize: 15,
          color: danger ? colors.destructive : colors.foreground,
          fontWeight: '500',
        }}
      >
        {label}
      </Text>
    </Pressable>
  );

  return (
    <>
      <Pressable
        onPress={handlePress}
        accessibilityRole="button"
        accessibilityLabel={t('addPlant.photoLabel')}
        style={({ pressed }) => ({
          width: 110,
          height: 110,
          borderRadius: 14,
          overflow: 'hidden',
          backgroundColor: colors.muted,
          borderWidth: 2,
          borderColor: uri ? colors.primary + '55' : colors.border,
          borderStyle: uri ? 'solid' : 'dashed',
          alignItems: 'center',
          justifyContent: 'center',
          opacity: pressed ? 0.85 : 1,
        })}
      >
        {loading ? (
          <ActivityIndicator color={colors.primary} />
        ) : uri ? (
          <>
            <Image
              source={{ uri }}
              style={{ width: '100%', height: '100%' }}
              resizeMode="cover"
            />
            <View
              style={{
                position: 'absolute',
                bottom: 0,
                left: 0,
                right: 0,
                backgroundColor: 'rgba(0,0,0,0.45)',
                paddingVertical: 6,
                alignItems: 'center',
              }}
            >
              <Ionicons name="camera" size={14} color="#fff" />
            </View>
          </>
        ) : (
          <View style={{ alignItems: 'center', gap: 6 }}>
            <Ionicons name="camera-outline" size={26} color={colors.mutedForeground} />
            <Text
              style={{
                fontSize: 10,
                color: colors.mutedForeground,
                textAlign: 'center',
                paddingHorizontal: 8,
              }}
            >
              {t('addPlant.addPhoto')}
            </Text>
          </View>
        )}
      </Pressable>

      <Modal
        visible={pickerVisible}
        transparent
        animationType="fade"
        onRequestClose={() => setPickerVisible(false)}
      >
        <Pressable
          style={{
            flex: 1,
            backgroundColor: 'rgba(0,0,0,0.5)',
            justifyContent: 'center',
            alignItems: 'center',
          }}
          onPress={() => setPickerVisible(false)}
        >
          <Pressable
            style={{
              backgroundColor: colors.card,
              borderColor: colors.cardBorder,
              borderWidth: 1,
              borderRadius: 16,
              width: '80%',
              padding: 20,
              gap: 6,
            }}
          >
            <Text
              style={{
                fontSize: 18,
                fontWeight: '700',
                color: colors.foreground,
                textAlign: 'center',
                marginBottom: 8,
              }}
            >
              {t('addPlant.photoSourceTitle')}
            </Text>

            {optionRow('camera-outline', t('common.camera'), openCamera)}
            {optionRow('images-outline', t('common.gallery'), openGallery)}
            {uri
              ? optionRow('trash-outline', t('addPlant.removePhoto'), handleRemove, true)
              : null}

            <Pressable
              onPress={() => setPickerVisible(false)}
              accessibilityRole="button"
              accessibilityLabel={t('common.cancel')}
              style={({ pressed }) => ({
                padding: 14,
                borderRadius: 10,
                alignItems: 'center',
                backgroundColor: pressed ? colors.muted : 'transparent',
                marginTop: 4,
              })}
            >
              <Text
                style={{
                  fontSize: 15,
                  color: colors.mutedForeground,
                  fontWeight: '500',
                }}
              >
                {t('common.cancel')}
              </Text>
            </Pressable>
          </Pressable>
        </Pressable>
      </Modal>
    </>
  );
}
