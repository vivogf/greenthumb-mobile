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
  Alert,
  ActivityIndicator,
} from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import * as ImageManipulator from 'expo-image-manipulator';
import { Ionicons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';
import { useColors } from '../hooks/useColors';

interface Props {
  uri: string | null;
  onSelect: (uri: string, base64: string) => void;
  onClear: () => void;
}

export function ImagePickerField({ uri, onSelect, onClear }: Props) {
  const colors = useColors();
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);

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
    const { granted } = await ImagePicker.requestCameraPermissionsAsync();
    if (!granted) {
      Alert.alert(t('common.permissionDeniedTitle'), t('common.cameraPermissionDenied'));
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
    const { granted } = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!granted) {
      Alert.alert(t('common.permissionDeniedTitle'), t('common.galleryPermissionDenied'));
      return;
    }
    const result = await ImagePicker.launchImageLibraryAsync({
      allowsEditing: true,
      aspect: [1, 1],
      quality: 1,
    });
    await processAndSelect(result);
  };

  const handlePress = () => {
    Alert.alert(
      'Фото растения',
      undefined,
      [
        { text: 'Камера', onPress: openCamera },
        { text: 'Галерея', onPress: openGallery },
        ...(uri
          ? [{ text: 'Удалить фото', style: 'destructive' as const, onPress: onClear }]
          : []),
        { text: 'Отмена', style: 'cancel' as const },
      ],
    );
  };

  return (
    <Pressable
      onPress={handlePress}
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
            Добавить фото
          </Text>
        </View>
      )}
    </Pressable>
  );
}
