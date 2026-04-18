import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import {
  Modal,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from 'react-native';
import Animated, {
  FadeIn,
  FadeOut,
  SlideInDown,
  SlideOutDown,
} from 'react-native-reanimated';
import { useColors } from '../hooks/useColors';

// ---------------------------------------------------------------------------
// Types — mirror react-native's Alert.alert API so migration is 1:1.
// ---------------------------------------------------------------------------

export type AlertButtonStyle = 'default' | 'cancel' | 'destructive';

export interface AlertButton {
  text: string;
  onPress?: () => void | Promise<void>;
  style?: AlertButtonStyle;
}

interface AlertOptions {
  title: string;
  message?: string;
  buttons?: AlertButton[];
  cancelable?: boolean;
}

// ---------------------------------------------------------------------------
// Context — hook-based imperative API: showAlert(title, message, buttons)
// ---------------------------------------------------------------------------

interface AlertDialogContextValue {
  showAlert: (title: string, message?: string, buttons?: AlertButton[]) => void;
}

const AlertDialogContext = createContext<AlertDialogContextValue | null>(null);

export function useAlertDialog(): AlertDialogContextValue {
  const ctx = useContext(AlertDialogContext);
  if (!ctx) {
    throw new Error('useAlertDialog must be used within <AlertDialogProvider>');
  }
  return ctx;
}

// ---------------------------------------------------------------------------
// Provider — holds state for the single global dialog, renders it.
// ---------------------------------------------------------------------------

export function AlertDialogProvider({ children }: { children: ReactNode }) {
  const [options, setOptions] = useState<AlertOptions | null>(null);
  const colors = useColors();
  const { width } = useWindowDimensions();

  const close = useCallback(() => setOptions(null), []);

  const showAlert = useCallback<AlertDialogContextValue['showAlert']>(
    (title, message, buttons) => {
      setOptions({ title, message, buttons });
    },
    [],
  );

  const value = useMemo(() => ({ showAlert }), [showAlert]);

  const buttons: AlertButton[] = options?.buttons?.length
    ? options.buttons
    : [{ text: 'OK', style: 'default' }];

  // Hardware back = dismiss (mirrors native Alert behavior on Android).
  const onRequestClose = useCallback(() => {
    const cancelBtn = buttons.find((b) => b.style === 'cancel');
    if (cancelBtn) cancelBtn.onPress?.();
    close();
    return true;
  }, [buttons, close]);

  const handlePress = useCallback(
    (btn: AlertButton) => {
      // Close first, then fire callback, so any navigation inside onPress
      // happens on top of a clean modal state.
      close();
      btn.onPress?.();
    },
    [close],
  );

  const dialogWidth = Math.min(width - 48, 340);

  return (
    <AlertDialogContext.Provider value={value}>
      {children}
      <Modal
        visible={options !== null}
        transparent
        animationType="none"
        onRequestClose={onRequestClose}
        statusBarTranslucent
      >
        {options !== null && (
          <Animated.View
            entering={FadeIn.duration(150)}
            exiting={FadeOut.duration(120)}
            style={styles.backdrop}
          >
            <Pressable style={StyleSheet.absoluteFill} onPress={onRequestClose} />
            <Animated.View
              entering={SlideInDown.springify().damping(18).mass(0.8)}
              exiting={SlideOutDown.duration(150)}
              style={[
                styles.card,
                {
                  width: dialogWidth,
                  backgroundColor: colors.card,
                  borderColor: colors.cardBorder,
                },
              ]}
            >
              <Text style={[styles.title, { color: colors.foreground }]}>
                {options.title}
              </Text>
              {options.message ? (
                <Text style={[styles.message, { color: colors.mutedForeground }]}>
                  {options.message}
                </Text>
              ) : null}
              <View
                style={[
                  styles.buttonRow,
                  { borderTopColor: colors.border },
                ]}
              >
                {buttons.map((btn, i) => {
                  const color =
                    btn.style === 'destructive'
                      ? colors.destructive
                      : btn.style === 'cancel'
                        ? colors.mutedForeground
                        : colors.primary;
                  const isLast = i === buttons.length - 1;
                  return (
                    <Pressable
                      key={`${btn.text}-${i}`}
                      onPress={() => handlePress(btn)}
                      android_ripple={{ color: colors.muted }}
                      style={({ pressed }) => [
                        styles.button,
                        !isLast && {
                          borderRightWidth: StyleSheet.hairlineWidth,
                          borderRightColor: colors.border,
                        },
                        Platform.OS === 'ios' && pressed && { opacity: 0.6 },
                      ]}
                      accessibilityRole="button"
                      accessibilityLabel={btn.text}
                    >
                      <Text
                        style={[
                          styles.buttonText,
                          { color },
                          btn.style === 'cancel' && { fontWeight: '500' },
                        ]}
                      >
                        {btn.text}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>
            </Animated.View>
          </Animated.View>
        )}
      </Modal>
    </AlertDialogContext.Provider>
  );
}

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  card: {
    borderRadius: 16,
    borderWidth: 1,
    overflow: 'hidden',
    // Elevation + shadow for depth
    elevation: 8,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.25,
    shadowRadius: 16,
  },
  title: {
    fontSize: 17,
    fontWeight: '700',
    paddingHorizontal: 20,
    paddingTop: 20,
    paddingBottom: 8,
  },
  message: {
    fontSize: 14,
    lineHeight: 20,
    paddingHorizontal: 20,
    paddingBottom: 20,
  },
  buttonRow: {
    flexDirection: 'row',
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  button: {
    flex: 1,
    paddingVertical: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: {
    fontSize: 16,
    fontWeight: '600',
  },
});
