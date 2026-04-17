# Phase 7.5 — Manual Test Checklist

Run in Expo Go (`npx expo start` → scan QR).
Needs: working internet, at least 1 plant in account.

---

## QW1: Cache isolation on signOut

1. Log in with Account A (has plants)
2. See plants on dashboard
3. Go to Profile → Sign Out
4. Log in with Account B (different key)
5. **Check:** dashboard shows Account B's plants, NOT Account A's
6. Kill app completely (swipe from recents), reopen
7. **Check:** still Account B's data, no leakage from A

## QW2: Push notification handler

> Requires EAS Build (not testable in Expo Go).
> Partial check: app should not crash on startup (handler init is safe in Expo Go).

1. Open app
2. **Check:** no crash on launch (initNotificationHandler gracefully skips in Expo Go)

## QW3: No Unsplash fallback

1. Go to Add Plant
2. Fill name + watering frequency, do NOT add a photo
3. Save the plant
4. **Check:** plant card shows leaf-icon placeholder, NOT an Unsplash photo
5. **Check:** no network request to `images.unsplash.com` (can verify in Expo dev tools network tab)

## QW4: Auto-refresh on app resume

1. Open dashboard, note a plant's watering status
2. From another device/browser: water that plant on the web PWA
3. Switch away from the app (home button), wait 5 seconds
4. Return to the app
5. **Check:** dashboard refreshes and shows updated watering status (may take 1-2 seconds)

## QW5: Accessibility (TalkBack)

> Enable TalkBack: Settings → Accessibility → TalkBack → On

1. Navigate dashboard with TalkBack
2. **Check:** plant cards announce name, location, watering status
3. **Check:** FAB "+" announces "Add a new plant" (or Russian equivalent)
4. **Check:** view mode buttons (list/card/grid) announce their mode
5. **Check:** water button announces "Water plant [name]"
6. Go to Plant Details
7. **Check:** back button, camera button, care action cards are announced
8. Go to Profile
9. **Check:** sign out, language switcher, notification toggle are announced

## P0-1: User-scoped cache

1. Log in as Account A, see plants
2. Sign out
3. Log in as Account B
4. **Check:** no flash of Account A's plants before Account B loads
5. Sign out, log back in as Account A
6. **Check:** Account A sees only their plants

## P0-2: Offline cache + banner

1. Open app with internet, load dashboard
2. Note the time shown (or no banner if data is fresh)
3. Kill the app completely
4. Turn OFF internet (airplane mode)
5. Reopen the app
6. **Check:** dashboard loads from cache (shows plants, not empty/error)
7. **Check:** banner shows "Updated at HH:mm" with stale time
8. **Check:** if >5 min since last sync, cloud-offline icon appears
9. Turn internet back ON
10. Pull-to-refresh
11. **Check:** data refreshes, banner disappears or updates

## P0-3: Network timeout + error handling

### Timeout test (hard to simulate, optional):
1. If you can simulate slow network (throttle in dev tools):
   - Set network to very slow
   - Try to add a plant or water
   - **Check:** error appears after ~10 seconds, not hanging forever

### Network error test:
1. Turn off internet (airplane mode)
2. Try to water a plant
3. **Check:** optimistic update shows, then rolls back with error alert
4. Try to add a plant
5. **Check:** error message appears (not blank crash)
6. Turn internet back on
7. Water a plant
8. **Check:** works normally

---

## Quick smoke test (covers most fixes in 2 minutes):

1. Open app → dashboard loads ✓ (QW2: no crash)
2. Pull-to-refresh → data updates ✓ (QW4: refresh works)
3. Add plant without photo → leaf placeholder ✓ (QW3)
4. Airplane mode → kill → reopen → plants visible ✓ (P0-2: cache)
5. Banner shows "Updated at..." ✓ (P0-2: banner)
6. Internet on → water a plant → instant update ✓ (P0-3: baseFetch works)
7. Sign out → sign in different account → no old data ✓ (QW1 + P0-1)
