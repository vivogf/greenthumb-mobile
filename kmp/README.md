# GreenThumb KMP — Android + JVM desktop

Kotlin Multiplatform + Compose Multiplatform port of the GreenThumb app.
Active development happens here; the Expo app in the repository root is legacy
(see the [root README](../README.md)).

All commands below are run from the `kmp/` directory unless stated otherwise.

## Contents

- [Requirements](#requirements)
- [Modules](#modules)
- [Build and run](#build-and-run)
- [Tests and checks](#tests-and-checks)
- [Key mechanics](#key-mechanics)
- [Release](#release)
- [Rule: never suggest reinstalling](#rule-never-suggest-reinstalling)

## Requirements

| Tool | Requirement |
|---|---|
| **JDK 21** | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` is required for every `./gradlew` call (`scripts/check.sh` sets this path itself if `JAVA_HOME` is not set) |
| **Android SDK** | the `ANDROID_HOME` variable points to the Android SDK (on macOS — `~/Library/Android/sdk` by default); `adb` and `emulator` are not on PATH — use full paths under `$ANDROID_HOME` |
| **Emulator** | AVD `elt_test` (system image API 34, `google_apis`). Needed only for installing the APK and the Maestro smoke; at most one emulator on the machine |
| **Maestro 2.10.0** (optional) | `/opt/homebrew/bin/maestro`; without it the gate simply skips the flow syntax check step |
| **Python 3** | for `scripts/i18n_check.py` in the gate |

## Modules

| Module | What's inside |
|---|---|
| **`shared/`** | all shared code (`commonMain` + `androidMain`/`jvmMain`, tests in `jvmTest`):<br>• `core.network` — `ApiClient` (Ktor, 10 s timeout, cookies in memory) and `ApiError`; the only path to the network (grep rule K1)<br>• `core.storage` — SecureStore (AES-GCM + AndroidKeyStore; a file on JVM), `AppSettings` (DataStore: language, theme, grid, `cached_user`), Room `GreenThumbDb` (per-user database `plants_${userId}.db`)<br>• `core.platform` — expect/actual: Connectivity, RemoteKillSwitch, Haptics, pickImage/resizeJpeg and more (stubs on desktop where the platform has no equivalent)<br>• `data/` — `PlantRepository`, offline mutation journal, `WateringStatus`, `RefreshCoordinator`<br>• `ui.theme` / `ui.components` / `ui.screens.*` (welcome, login, enablenotifications, dashboard, addplant, plantdetail, profile, update, gallery) / `ui.nav`; en/ru strings live in `composeResources` |
| **`androidApp/`** | a thin shell: `MainActivity`, `GreenThumbApplication`, `GtFirebaseMessagingService`, `google-services.json`, backup rules (`full_backup_content.xml`, `data_extraction_rules.xml` — recovery-key files are excluded from them). `applicationId com.greenthumbplantcare`, `versionCode 6` / `versionName 0.1.0` |
| **`desktopApp/`** | JVM harness: `main.kt` (GUI launch, hot reload `hotRun`/`hotMcpServer`), `RealApiProbe.kt` (manual probe of the live API) |
| **`scripts/`** | `check.sh` (the gate), `grep-rules-selftest.sh`, `i18n_check.py`, `mcp-driver.mjs` |
| **`maestro/`** | `smoke.yaml` + `render-smoke.sh`, `delete-account.yaml` + `render-delete-account.sh` |

## Build and run

| Task | Command |
|---|---|
| Android debug APK | `./gradlew :androidApp:assembleDebug` |
| Install on the emulator | `$ANDROID_HOME/platform-tools/adb -s emulator-5554 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk` |
| Desktop GUI | `./gradlew :desktopApp:run` |
| Desktop hot reload (for UI development) | `./gradlew :desktopApp:hotRun --auto` |

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

# Android debug APK
./gradlew :androidApp:assembleDebug
# → androidApp/build/outputs/apk/debug/androidApp-debug.apk

# Install on the emulator (every adb call must use -s emulator-5554:
# a physical phone, if connected, is never addressed)
$ANDROID_HOME/platform-tools/adb -s emulator-5554 install -r \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

The smoke needs an install with a fresh timestamp (intro carousel):
`adb uninstall com.greenthumbplantcare` and then `install` of the same APK. An
`install -r` on top of an existing package is a second valid starting branch (the
"Key not found" screen); the smoke passes both.

Desktop app:

```bash
./gradlew :desktopApp:run            # regular GUI launch
./gradlew :desktopApp:hotRun --auto  # harness with hot reload (for UI development)
```

## Tests and checks

| Task | Command |
|---|---|
| Full gate for the kmp code | `bash scripts/check.sh` |
| Grep rules only (fast iteration) | `bash scripts/check.sh --grep-only` |
| Unit tests only | `./gradlew :shared:jvmTest` (often UP-TO-DATE; an honest rerun — `./gradlew :shared:jvmTest --rerun`) |
| Maestro smoke on the emulator | `bash maestro/render-smoke.sh run` |
| Account deletion flow | `bash kmp/maestro/render-delete-account.sh run <key-file>` — **deletes the account of that key**; run only with a separate test account |

```bash
bash scripts/check.sh             # full gate for the kmp code
bash scripts/check.sh --grep-only # grep rules only (fast iteration)
./gradlew :shared:jvmTest         # unit tests only (often UP-TO-DATE;
                                  # honest rerun — ./gradlew :shared:jvmTest --rerun)
```

`scripts/check.sh` (the replacement for `npm run check` in `kmp/`) covers four
steps:

1. compilation of `:shared:compileAndroidMain`, `:shared:compileKotlinJvm`,
   `:desktopApp:compileKotlin` + the `:shared:jvmTest` tests;
2. grep rules K1–K5: network only through `core.network`, no unsplash URLs,
   `YYYY-MM-DD` dates in request bodies, the database only through
   `PlantRepository`, UI literals (`.dp`, `Color(`, `.sp`) only outside
   `ui/screens/**`;
3. Maestro flow syntax for `maestro/smoke.yaml` (without maestro installed the
   step is skipped; `GT_SKIP_MAESTRO=1` skips it explicitly);
4. i18n parity: the en/ru `strings.xml` key sets cover the i18n set.

Maestro smoke on the emulator (needs the `elt_test` AVD running and the debug
APK **already installed**):

```bash
bash maestro/render-smoke.sh run
```

The flow passes five scenarios: recovery-key login, adding a plant without a
photo, watering (with a differentiating assert "7 days overdue" → "3 days
left"), pull-to-refresh, an offline restart with the list; both starting branches
(intro and "Key not found"). **The recovery key is injected only from a key
file** (by redirect, never through env/argv/logs); the script filters Maestro
output for echoes of the typed key and deletes temporary copies — the key is
never printed.

## Key mechanics

<details>
<summary><b>Storage and session</b></summary>

SecureStore (AES-GCM + AndroidKeyStore; a file on JVM) + `AppSettings` on
DataStore. Offline session: if the network is unavailable at startup and
`cached_user` exists — the app works from Room with a "no network" banner.

</details>

<details>
<summary><b>Import from the Expo handoff</b></summary>

The Expo handoff release (versionCode 5) writes `gt-handoff.json` (recovery key +
settings) into `filesDir`; KMP reads and deletes it on first launch. With
neither a handoff nor a key of its own — the "Key not found" screen. Files with
the key are excluded from Android backup and device transfer.

</details>

<details>
<summary><b>Offline mutation queue</b></summary>

Per-user Room database + a `pending_mutations` journal: on Network/Timeout the
change stays queued and is delivered when the network appears (the UI honestly
says "will be applied once you're back online"); on a definite server rejection
(4xx/5xx) — a rollback from the snapshot.

</details>

<details>
<summary><b>FCM push + deep link</b></summary>

Channel `default` ("Plant Care Reminders", HIGH); foreground notifications are
shown by the app itself; a deep link with `data.plant_id` opens `plant/{id}`.

</details>

<details>
<summary><b>Kill-switch</b></summary>

Firebase Remote Config `min_supported_build` is compared with the installed
versionCode: when blocked — an "Update the app" screen with a Google Play
button. Fail-open: any failure or missing parameter → 0 (does not block);
debug builds can override it with the value from the
`files/debug_min_supported_build` file.

</details>

<details>
<summary><b>In-app account deletion</b></summary>

Confirmation screen → `DELETE /api/auth/account` → full local cleanup
(a Google Play requirement).

</details>

<details>
<summary><b>Crashlytics</b></summary>

Wired into `:androidApp` (mapping and native symbols are uploaded for release);
debug builds do not send reports (`isCrashlyticsCollectionEnabled = false`) —
probes never reach the baseline.

</details>

## Release

The full rollout contract lives in `kmp/docs/`:

- [`kmp/docs/release-gate-rollout.md`](docs/release-gate-rollout.md) — the ≥95%
  of active installs on versionCode ≥ 5 gate and the staged rollout ladder
  5→10→20→50→100%;
- [`kmp/docs/crash-baseline.md`](docs/crash-baseline.md) — crash baseline and
  rollout halt thresholds;
- [`kmp/docs/incident-runbook.md`](docs/incident-runbook.md) — what to do in an
  incident after rollout.

Steps before the rollout that the **owner** performs (Play/Firebase Console,
git push — not automated by agents):

1. Firebase Console: enable Crashlytics; create and **publish** the Remote
   Config parameter `min_supported_build` = 0 (the switch is off) **before**
   the rollout; fill in the crash baseline (`crash-baseline.md`) — without it,
   do not leave the internal track.
2. `git push` — the public `docs/` pages (privacy etc.) on GitHub Pages update
   only after a push.
3. Play Console: Data Safety — add crash logs/diagnostics; upload the KMP AAB to
   the internal track; closed testing of 20 testers × 14 days (Personal
   account); staged rollout along the ladder from `release-gate-rollout.md`.

## Rule: never suggest reinstalling

> [!WARNING]
> **Never suggest reinstalling the app** — on Android a reinstall destroys the
> only recovery key, and the account is lost forever. The right advice is
> "update the app in Google Play" (an update does not touch the data).
