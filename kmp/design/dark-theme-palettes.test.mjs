// Regression checks for the offline dark-palette preview (kmp/design/dark-theme-palettes.html).
// Run: node --test kmp/design/dark-theme-palettes.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const htmlPath = fileURLToPath(new URL('./dark-theme-palettes.html', import.meta.url));
const html = readFileSync(htmlPath, 'utf8');

const ROLES = ['background', 'surface', 'surfaceVariant', 'text', 'muted', 'primary', 'error'];
// Exact source values: former = dd84fff36a3b78debefe128414699264b78e71eb, current = 2a8da8fb0d4d092b3f5d58034c39d37c552f0319.
const FORMER = ['#15130E', '#1C1A14', '#2A2822', '#F3F2EF', '#A89F92', '#4A9A5A', '#A33535'];
const FOREST = ['#0E1610', '#152018', '#1C2A1F', '#EEF4EC', '#A8B9A6', '#277A40', '#EE8E72'];

function palettes() {
  const m = html.match(/<script type="application\/json" id="palettes">([\s\S]*?)<\/script>/);
  assert.ok(m, 'palette JSON block is present');
  return JSON.parse(m[1]);
}

function inlineScripts() {
  return [...html.matchAll(/<script(?![^>]*application\/json)([^>]*)>([\s\S]*?)<\/script>/g)];
}

function luminance(hex) {
  const n = parseInt(hex.slice(1), 16);
  const ch = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
}

function contrast(a, b) {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

test('exactly four labelled palettes in the agreed order', () => {
  const list = palettes();
  assert.deepEqual(list.map((p) => p.id), ['former', 'forest', 'graphite', 'sage']);
  assert.deepEqual(list.map((p) => p.label), ['Прежняя', 'Текущий Лес', 'Графит', 'Серо-шалфейная']);
  const keys = Object.keys(list[0].colors).sort();
  for (const p of list) {
    assert.deepEqual(Object.keys(p.colors).sort(), keys, `${p.id} defines the same color roles`);
    for (const role of ROLES) assert.ok(p.colors[role], `${p.id}.${role} is defined`);
  }
});

test('former and current Forest keep their seven source roles exactly', () => {
  const byId = Object.fromEntries(palettes().map((p) => [p.id, p]));
  assert.deepEqual(ROLES.map((r) => byId.former.colors[r].toUpperCase()), FORMER);
  assert.deepEqual(ROLES.map((r) => byId.forest.colors[r].toUpperCase()), FOREST);
  assert.equal(byId.former.source, 'dd84fff36a3b78debefe128414699264b78e71eb');
  assert.equal(byId.forest.source, '2a8da8fb0d4d092b3f5d58034c39d37c552f0319');
});

test('the two new palettes are distinct, subdued and readable', () => {
  const byId = Object.fromEntries(palettes().map((p) => [p.id, p]));
  const sat = (hex) => {
    const n = parseInt(hex.slice(1), 16);
    const c = [(n >> 16) & 255, (n >> 8) & 255, n & 255];
    return Math.max(...c) - Math.min(...c);
  };
  for (const id of ['graphite', 'sage']) {
    const c = byId[id].colors;
    assert.notDeepEqual(ROLES.map((r) => c[r].toUpperCase()), FORMER, `${id} differs from former`);
    assert.notDeepEqual(ROLES.map((r) => c[r].toUpperCase()), FOREST, `${id} differs from Forest`);
    for (const r of ['background', 'surface', 'surfaceVariant']) {
      assert.ok(sat(c[r]) <= 12, `${id}.${r} is a low-saturation large surface`);
    }
    assert.ok(contrast(c.text, c.background) >= 7, `${id} main text on background`);
    assert.ok(contrast(c.text, c.surface) >= 7, `${id} main text on cards`);
    assert.ok(contrast(c.muted, c.surface) >= 4.5, `${id} muted text on cards`);
    assert.ok(contrast(c.muted, c.surfaceVariant) >= 4.5, `${id} muted text on fields`);
    assert.ok(contrast(c.onPrimary, c.primary) >= 4.5, `${id} button label`);
    assert.ok(contrast(c.accentText, c.surface) >= 4.5, `${id} link text on cards`);
    assert.ok(contrast(c.error, c.surface) >= 4.5, `${id} overdue text on cards`);
  }
  const g = byId.graphite.colors.background;
  const s = byId.sage.colors.background;
  assert.notEqual(g.toUpperCase(), s.toUpperCase());
  const n = (hex) => parseInt(hex.slice(1), 16);
  // Graphite stays neutral; sage leans green (G channel above R and B).
  assert.ok(sat(g) <= 4, 'graphite background is near-neutral');
  const sg = [(n(s) >> 16) & 255, (n(s) >> 8) & 255, n(s) & 255];
  assert.ok(sg[1] > sg[0] && sg[1] > sg[2], 'sage background leans green');
});

test('one shared template per screen; templates use color variables only', () => {
  for (const id of ['tpl-dashboard', 'tpl-detail']) {
    const all = html.match(new RegExp(`<template id="${id}">`, 'g')) || [];
    assert.equal(all.length, 1, `${id} occurs once`);
  }
  const templates = [...html.matchAll(/<template id="tpl-[^"]+">([\s\S]*?)<\/template>/g)].map((m) => m[1]);
  assert.equal(templates.length, 2);
  for (const t of templates) {
    assert.doesNotMatch(t, /#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?(?![\w-])/, 'no literal hex color in a screen template');
    assert.doesNotMatch(t, /rgba?\(|hsla?\(/, 'no literal rgb/hsl color in a screen template');
    assert.doesNotMatch(t, /\bid="/, 'templates are cloned, so they carry no ids');
  }
  const css = [...html.matchAll(/<style>([\s\S]*?)<\/style>/g)].map((m) => m[1]).join('\n');
  assert.doesNotMatch(css, /data-palette/, 'CSS never styles a palette differently');
  assert.match(html, /\.style\.setProperty\(/, 'palettes are applied as CSS custom properties');
});

test('no animation, transition or smooth scrolling', () => {
  assert.doesNotMatch(html, /@keyframes|\btransition\s*:|\banimation\s*:|scroll-behavior\s*:\s*smooth|<animate|requestAnimationFrame|\.animate\(/);
});

test('runtime assets are inline: no remote or external loads', () => {
  assert.doesNotMatch(html, /\b(?:src|href)\s*=\s*["'](?:https?:)?\/\//i, 'no remote src/href');
  assert.doesNotMatch(html, /url\(\s*["']?(?:https?:)?\/\//i, 'no remote CSS url()');
  assert.doesNotMatch(html, /@import|<link\b|<iframe\b|<img\b[^>]*src="(?!data:)/i);
  assert.doesNotMatch(html, /\bfetch\(|XMLHttpRequest|WebSocket|sendBeacon|EventSource|navigator\.serviceWorker/);
  for (const [, attrs] of inlineScripts()) assert.doesNotMatch(attrs, /\bsrc=/, 'scripts are inline');
});

test('inline JavaScript parses', () => {
  const scripts = inlineScripts();
  assert.ok(scripts.length >= 1, 'has an inline script');
  for (const [, , code] of scripts) assert.doesNotThrow(() => new vm.Script(code));
});

test('current design markers are present once per screen template', () => {
  const dash = html.match(/<template id="tpl-dashboard">([\s\S]*?)<\/template>/)[1];
  const detail = html.match(/<template id="tpl-detail">([\s\S]*?)<\/template>/)[1];
  assert.match(dash, /Мои растения/);
  // M12a: the grid day count sits above the plant name.
  const cell = dash.match(/class="cell-days[^"]*"[\s\S]*?class="cell-name"/);
  assert.ok(cell, 'day count precedes the name in a grid cell');
  assert.match(detail, /О растении/);
  assert.match(detail, /Запись в журнал/);
});
