#!/usr/bin/env node
// Boot an Android emulator (or use a connected device), install a preview APK,
// launch the app, grab a screenshot and the last 200 lines of logcat.
// Usage: node scripts/smoke.mjs <path-to.apk> [avd-name]

import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const PACKAGE = 'com.greenthumbplantcare';
const OUT_DIR = resolve('smoke-output');
const BOOT_TIMEOUT_MS = 180_000;

const SDK =
  process.env.ANDROID_HOME ||
  process.env.ANDROID_SDK_ROOT ||
  join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk');

const adb = join(SDK, 'platform-tools', 'adb.exe');
const emulator = join(SDK, 'emulator', 'emulator.exe');

if (!existsSync(adb)) die(`adb not found: ${adb}. Set ANDROID_HOME.`);
mkdirSync(OUT_DIR, { recursive: true });

const apk = process.argv[2];
const avd = process.argv[3];

if (apk && !existsSync(apk)) die(`APK not found: ${apk}`);

main().catch((e) => die(e.message));

async function main() {
  if (!deviceConnected()) {
    if (!avd) die('No device connected. Pass AVD name as second arg.');
    if (!existsSync(emulator)) die(`emulator not found: ${emulator}`);
    console.log(`> launching emulator ${avd}`);
    spawn(emulator, ['-avd', avd, '-no-snapshot-save'], {
      detached: true,
      stdio: 'ignore',
    }).unref();
    await waitForBoot();
  }

  if (apk) {
    console.log(`> installing ${apk}`);
    run(adb, ['install', '-r', apk]);
    console.log(`> launching ${PACKAGE}`);
    run(adb, ['shell', 'monkey', '-p', PACKAGE, '-c', 'android.intent.category.LAUNCHER', '1']);
    await sleep(4000);
  }

  const shot = join(OUT_DIR, `screen-${Date.now()}.png`);
  console.log(`> screenshot → ${shot}`);
  const cap = spawnSync(adb, ['exec-out', 'screencap', '-p'], {
    encoding: 'buffer',
    maxBuffer: 32 * 1024 * 1024,
  });
  if (cap.status !== 0) die(`screencap failed: ${cap.stderr?.toString()}`);
  writeFileSync(shot, cap.stdout);

  const logPath = join(OUT_DIR, `logcat-${Date.now()}.txt`);
  console.log(`> logcat tail → ${logPath}`);
  const log = spawnSync(adb, ['logcat', '-d', '-t', '200', '*:W', 'ReactNative:V', 'ReactNativeJS:V'], {
    encoding: 'utf8',
  });
  writeFileSync(logPath, log.stdout || '');

  console.log('done.');
}

function deviceConnected() {
  const r = spawnSync(adb, ['devices'], { encoding: 'utf8' });
  return /^\S+\s+device\b/m.test(r.stdout.split('\n').slice(1).join('\n'));
}

async function waitForBoot() {
  const start = Date.now();
  while (Date.now() - start < BOOT_TIMEOUT_MS) {
    const r = spawnSync(adb, ['shell', 'getprop', 'sys.boot_completed'], { encoding: 'utf8' });
    if (r.stdout.trim() === '1') {
      console.log('> boot_completed');
      return;
    }
    await sleep(2000);
  }
  die('Emulator boot timed out.');
}

function run(cmd, args) {
  const r = spawnSync(cmd, args, { stdio: 'inherit' });
  if (r.status !== 0) die(`${cmd} ${args.join(' ')} failed (${r.status})`);
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function die(msg) {
  console.error(`ERR: ${msg}`);
  process.exit(1);
}
