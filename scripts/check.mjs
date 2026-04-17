#!/usr/bin/env node
// Automated pre-commit / CI checks for GreenThumbMobile.
// Runs typecheck + project-specific regression grep.
// Mirrors the `test-app` skill but as executable code.
// Exit 0 = clean, exit 1 = typecheck failed or regression hit.

import { spawnSync } from 'node:child_process';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const ROOT = process.cwd();
const SKIP_DIRS = new Set([
  'node_modules',
  '.expo',
  '.git',
  '.claude',
  '.codex',
  'android',
  'ios',
  'dist',
  'build',
]);
const SKIP_FILES = new Set(['expo-env.d.ts', 'nativewind-env.d.ts']);

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (SKIP_DIRS.has(name)) continue;
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (/\.(ts|tsx)$/.test(name) && !SKIP_FILES.has(name)) out.push(p);
  }
  return out;
}

const rel = (p) => relative(ROOT, p).split(sep).join('/');

// ── 1. Typecheck ────────────────────────────────────────────
console.log('=== typecheck ===');
const tc = spawnSync('npm', ['run', '--silent', 'typecheck'], {
  stdio: 'inherit',
  shell: true,
});
if (tc.status !== 0) {
  console.error('\nFAIL: typecheck — fix types before proceeding.');
  process.exit(1);
}
console.log('OK: typecheck passed\n');

// ── 2. Regression grep ──────────────────────────────────────
console.log('=== regression grep ===');
const files = walk(ROOT);
const sources = files.map((f) => ({ path: f, rel: rel(f), src: readFileSync(f, 'utf8') }));

let failed = false;

/**
 * @param {string} id
 * @param {string} title
 * @param {(ctx: {rel: string, line: string, lineNo: number, src: string}) => boolean} predicate
 */
function check(id, title, predicate) {
  const hits = [];
  for (const { rel: relPath, src } of sources) {
    const lines = src.split('\n');
    for (let i = 0; i < lines.length; i++) {
      if (predicate({ rel: relPath, line: lines[i], lineNo: i + 1, src })) {
        hits.push(`${relPath}:${i + 1}  ${lines[i].trim().slice(0, 120)}`);
      }
    }
  }
  if (hits.length === 0) {
    console.log(`  ${id} ${title}: clean`);
  } else {
    failed = true;
    console.log(`  ${id} ${title}: ${hits.length} hit(s)`);
    for (const h of hits) console.log(`     ${h}`);
  }
}

// Return true if the regex match position is inside a // line-comment.
const inLineComment = (line, matchIndex) => {
  const c = line.indexOf('//');
  return c !== -1 && c < matchIndex;
};

// R1 — raw fetch() outside lib/api.ts (should go through baseFetch/apiRequest/apiFetch)
check('R1', 'raw fetch()', ({ rel, line }) => {
  if (rel === 'lib/api.ts') return false;
  const m = line.match(/\bfetch\s*\(/);
  if (!m) return false;
  if (inLineComment(line, m.index)) return false;
  if (/^\s*import\b/.test(line)) return false;
  return true;
});

// R2 — staleTime: Infinity should have been replaced by 60_000 (QW4)
check('R2', 'staleTime: Infinity', ({ rel, line }) => {
  if (rel !== 'lib/queryClient.ts') return false;
  return /staleTime\s*:\s*Infinity/.test(line);
});

// R3 — Unsplash fallback was removed (QW3)
check('R3', 'unsplash.com URL', ({ line }) => {
  const m = line.match(/unsplash\.com/i);
  if (!m) return false;
  if (inLineComment(line, m.index)) return false;
  return true;
});

// R4 — literal queryKey array with /api/... (must use useUserScopedQueryKey, P0-1)
check('R4', 'non-scoped queryKey (literal [/api/...])', ({ rel, line }) => {
  if (rel === 'hooks/useUserScopedQueryKey.ts') return false;
  return /queryKey\s*:\s*\[\s*['"`]\/api\//.test(line);
});

// R7 — toISOString() in app/ (backend expects YYYY-MM-DD for date-only fields)
check('R7', 'toISOString() in app/', ({ rel, line }) => {
  if (!rel.startsWith('app/')) return false;
  const m = line.match(/\.toISOString\s*\(\s*\)/);
  if (!m) return false;
  if (inLineComment(line, m.index)) return false;
  return true;
});

// R8 — "+ 'T12:00:00'" hack on non-normalized strings (DatePicker bug 2026-03-09).
// Safe when applied to the output of normalizeDate() — flag only when `normalized`
// is absent on the same line.
check('R8', "'T12:00:00' concat on non-normalized value", ({ line }) => {
  if (!/\+\s*['"`]T\d{2}:\d{2}:\d{2}/.test(line)) return false;
  if (/\bnormalized\b/.test(line)) return false;
  return true;
});

// R10 — expo-notifications must be lazy-imported (Expo Go crash)
check('R10', 'expo-notifications static import', ({ rel, line }) => {
  if (rel === 'lib/notifications.ts') return false;
  return /^\s*import\b[^;]*['"]expo-notifications['"]/.test(line);
});

// ── Summary ─────────────────────────────────────────────────
console.log();
if (failed) {
  console.log('FAIL: regression check found issues — see hits above.');
  console.log('If a hit is a false positive, narrow the regex in scripts/check.mjs.');
  process.exit(1);
}
console.log('OK: all automated checks clean.');
console.log('Manual smoke (preview APK) is still your responsibility for UI changes.');
process.exit(0);
