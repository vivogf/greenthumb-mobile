/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './app/**/*.{js,jsx,ts,tsx}',
    './components/**/*.{js,jsx,ts,tsx}',
    './contexts/**/*.{js,jsx,ts,tsx}',
  ],
  presets: [require('nativewind/preset')],
  theme: {
    extend: {
      colors: {
        // Primary green — matches PWA hsl(142, 43%, 40%) dark / hsl(142, 43%, 32%) light
        primary: {
          DEFAULT: '#4a9a5a',
          light: '#2e7740',
          foreground: '#f0f9f2',
        },
        // Destructive red
        destructive: {
          DEFAULT: '#a33535',
          light: '#993333',
          foreground: '#fef2f2',
        },
        // Amber for warnings
        warn: {
          DEFAULT: '#d97706',
          bg: 'rgba(217, 119, 6, 0.1)',
        },
      },
    },
  },
  plugins: [],
};
