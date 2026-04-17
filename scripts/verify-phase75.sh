#!/bin/bash
# Phase 7.5 verification script
# Automated checks for all 8 fixes (QW1-5, P0-1/2/3)
# Run: bash scripts/verify-phase75.sh

cd "$(dirname "$0")/.."

PASS=0
FAIL=0
WARN=0

ok()   { echo "  ✅ $1"; PASS=$((PASS+1)); }
fail() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
warn() { echo "  ⚠️  $1"; WARN=$((WARN+1)); }

echo "=== Phase 7.5 Verification ==="
echo ""

# ─── QW1: queryClient.clear() in signOut ───
echo "QW1: Cache cleanup on signOut"
if grep -q 'queryClient\.clear()' contexts/AuthContext.tsx; then
  ok "queryClient.clear() present in signOut"
else
  fail "queryClient.clear() missing in AuthContext signOut"
fi
if grep -q 'persister\.removeClient()' contexts/AuthContext.tsx; then
  ok "persister.removeClient() present in signOut"
else
  fail "persister.removeClient() missing in AuthContext signOut"
fi
echo ""

# ─── QW2: Notification handler in _layout.tsx ───
echo "QW2: Push notification handler"
if grep -q 'initNotificationHandler' app/_layout.tsx; then
  ok "initNotificationHandler() called in _layout.tsx"
else
  fail "initNotificationHandler() missing in _layout.tsx"
fi
if grep -q 'addNotificationResponseListener' app/_layout.tsx; then
  ok "addNotificationResponseListener() present in _layout.tsx"
else
  fail "Notification response listener missing in _layout.tsx"
fi
echo ""

# ─── QW3: No Unsplash fallback ───
echo "QW3: Unsplash fallback removed"
if grep -rq 'unsplash\.com' app/ components/ lib/; then
  fail "Unsplash URL still found in source code"
else
  ok "No Unsplash URLs in app/, components/, lib/"
fi
echo ""

# ─── QW4: refetchOnWindowFocus + staleTime ───
echo "QW4: React Query freshness settings"
if grep -q 'refetchOnWindowFocus.*true' lib/queryClient.ts; then
  ok "refetchOnWindowFocus: true"
else
  fail "refetchOnWindowFocus not set to true in queryClient.ts"
fi
if grep -q 'refetchOnReconnect.*true' lib/queryClient.ts; then
  ok "refetchOnReconnect: true"
else
  fail "refetchOnReconnect not set to true in queryClient.ts"
fi
if grep -q 'staleTime.*Infinity' lib/queryClient.ts; then
  fail "staleTime still Infinity"
else
  ok "staleTime is not Infinity"
fi
if grep -q 'focusManager' app/_layout.tsx; then
  ok "focusManager bridge in _layout.tsx"
else
  fail "AppState → focusManager bridge missing in _layout.tsx"
fi
echo ""

# ─── QW5: Accessibility labels ───
echo "QW5: Accessibility props"
A11Y_COUNT=$(grep -r 'accessibilityLabel\|accessibilityRole\|accessibilityState' app/ components/ --include='*.tsx' | grep -v node_modules | wc -l)
if [ "$A11Y_COUNT" -ge 20 ]; then
  ok "Found $A11Y_COUNT a11y prop usages (threshold: 20+)"
else
  fail "Only $A11Y_COUNT a11y prop usages found (expected 20+)"
fi
if grep -q '"a11y"' i18n/locales/en.json; then
  ok "a11y namespace in en.json"
else
  fail "a11y namespace missing in en.json"
fi
if grep -q '"a11y"' i18n/locales/ru.json; then
  ok "a11y namespace in ru.json"
else
  fail "a11y namespace missing in ru.json"
fi
echo ""

# ─── P0-1: User-scoped queryKeys ───
echo "P0-1: User-scoped query keys"
if [ -f hooks/useUserScopedQueryKey.ts ]; then
  ok "useUserScopedQueryKey.ts exists"
else
  fail "useUserScopedQueryKey.ts missing"
fi
if grep -q 'useUserScopedQueryKey\|getUserScopedQueryKey' app/\(tabs\)/index.tsx; then
  ok "User-scoped key used in dashboard"
else
  fail "Dashboard not using user-scoped queryKey"
fi
if grep -q 'useUserScopedQueryKey\|getUserScopedQueryKey' app/plant/\[id\].tsx; then
  ok "User-scoped key used in plant details"
else
  fail "Plant details not using user-scoped queryKey"
fi
echo ""

# ─── P0-2: Persisted cache + banner ───
echo "P0-2: Persisted offline cache"
if grep -q 'createAsyncStoragePersister' lib/queryClient.ts; then
  ok "AsyncStorage persister configured"
else
  fail "AsyncStorage persister missing in queryClient.ts"
fi
if grep -q 'PersistQueryClientProvider' app/_layout.tsx; then
  ok "PersistQueryClientProvider in _layout.tsx"
else
  fail "PersistQueryClientProvider missing in _layout.tsx"
fi
if grep -q 'lastSynced\|dataUpdatedAt' app/\(tabs\)/index.tsx; then
  ok "Last synced banner in dashboard"
else
  fail "Last synced banner missing in dashboard"
fi
if grep -q 'query-async-storage-persister' package.json; then
  ok "@tanstack/query-async-storage-persister in package.json"
else
  fail "@tanstack/query-async-storage-persister missing from package.json"
fi
echo ""

# ─── P0-3: Unified network layer ───
echo "P0-3: Unified network layer"
if grep -q 'class ApiError' lib/api.ts; then
  ok "ApiError class in api.ts"
else
  fail "ApiError class missing in api.ts"
fi
if grep -q 'baseFetch' lib/api.ts; then
  ok "baseFetch function in api.ts"
else
  fail "baseFetch missing in api.ts"
fi
if grep -q 'AbortController' lib/api.ts; then
  ok "AbortController timeout in baseFetch"
else
  fail "AbortController missing in api.ts"
fi

# Check no direct fetch() outside api.ts (excluding node_modules)
DIRECT_FETCH=$(grep -rn 'await fetch(' app/ contexts/ components/ lib/ --include='*.ts' --include='*.tsx' | grep -v 'node_modules' | grep -v 'lib/api.ts' || true)
if [ -z "$DIRECT_FETCH" ]; then
  ok "No direct fetch() calls outside lib/api.ts"
else
  fail "Direct fetch() found outside api.ts:"
  echo "$DIRECT_FETCH" | head -5
fi

if grep -q 'API_BASE_URL' contexts/AuthContext.tsx; then
  fail "AuthContext still imports API_BASE_URL (should use baseFetch)"
else
  ok "AuthContext uses baseFetch, no direct API_BASE_URL"
fi
if grep -q 'API_BASE_URL' app/add-plant.tsx; then
  fail "add-plant.tsx still imports API_BASE_URL (should use apiRequest)"
else
  ok "add-plant.tsx uses apiRequest, no direct API_BASE_URL"
fi
echo ""

# ─── TypeScript check ───
echo "TypeScript compilation"
if npx tsc --noEmit 2>/dev/null; then
  ok "tsc --noEmit passed"
else
  fail "tsc --noEmit has errors"
fi
echo ""

# ─── Summary ───
echo "=== Results ==="
echo "  ✅ Passed: $PASS"
echo "  ❌ Failed: $FAIL"
echo "  ⚠️  Warnings: $WARN"
echo ""

if [ "$FAIL" -gt 0 ]; then
  echo "❌ VERIFICATION FAILED"
  exit 1
else
  echo "✅ ALL CHECKS PASSED"
  exit 0
fi
