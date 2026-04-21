// Backend API URL — the same server used by the PWA
export const API_BASE_URL = 'https://greenthumb.xmpp.site';

// SecureStore key for persisting the recovery key between sessions
export const RECOVERY_KEY_STORE_KEY = 'greenthumb_recovery_key';

// AsyncStorage key for language preference
export const LANGUAGE_STORE_KEY = 'greenthumb_language';

// AsyncStorage key for dashboard layout mode (list / card / grid)
export const LAYOUT_MODE_STORE_KEY = 'greenthumb_layout_mode';

// AsyncStorage key for theme preference (light / dark / auto)
export const THEME_STORE_KEY = 'greenthumb_theme';

// AsyncStorage key for "user has completed the welcome carousel" flag
export const INTRO_SEEN_STORE_KEY = 'greenthumb_intro_seen';

// Support email shown in privacy blocks and bug-report CTAs
export const SUPPORT_EMAIL = 'greenthumb.taunt861@passmail.net';

// Semantic colors for light and dark mode
// Derived from PWA CSS variables in client/src/index.css
export const Colors = {
  dark: {
    background: '#15130e',     // hsl(30, 8%, 8%)
    card: '#1c1a14',           // hsl(30, 8%, 10%)
    cardBorder: '#272420',     // hsl(30, 6%, 14%)
    foreground: '#f3f2ef',     // hsl(35, 10%, 95%)
    border: '#302d27',         // hsl(30, 6%, 18%)
    muted: '#2a2822',          // hsl(35, 6%, 18%)
    mutedForeground: '#a89f92', // hsl(35, 8%, 65%)
    primary: '#4a9a5a',        // hsl(142, 43%, 40%)
    primaryForeground: '#f0f9f2',
    destructive: '#a33535',    // hsl(0, 62%, 40%)
    destructiveForeground: '#fef2f2',
    input: '#3d3b35',          // hsl(30, 6%, 25%)
    ring: '#4a9a5a',
    amber: '#d97706',
    amberBg: 'rgba(217, 119, 6, 0.12)',
    amberBorder: 'rgba(217, 119, 6, 0.25)',
  },
  light: {
    background: '#ffffff',
    card: '#fafafa',
    cardBorder: '#f0f0f0',
    foreground: '#1a1a1a',
    border: '#e5e5e5',
    muted: '#ebebea',
    mutedForeground: '#8a8a85',
    primary: '#2e7740',        // hsl(142, 43%, 32%)
    primaryForeground: '#f0f9f2',
    destructive: '#993333',
    destructiveForeground: '#fef2f2',
    input: '#c0c0c0',
    ring: '#2e7740',
    amber: '#d97706',
    amberBg: 'rgba(217, 119, 6, 0.08)',
    amberBorder: 'rgba(217, 119, 6, 0.20)',
  },
} as const;
