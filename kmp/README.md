# GreenThumb KMP — Android + JVM desktop

GreenThumb is a plant-care app built with Kotlin Multiplatform + Compose Multiplatform.
It includes watering reminders, offline plant data, a care journal and photo timeline,
species identification, photo-based diagnosis, progress and an Android home-screen widget.
Active development happens here; the Expo app in the repository root is legacy
(see the [root README](../README.md)).

**After M10:** the UI/UX redesign is implemented. Release **0.8.0 (`versionCode 7`)**
has a signed Android App Bundle (AAB) built locally; it has **not been uploaded to Google Play**.
Android is the release target; JVM desktop is the development and test harness.

All commands below are run from the `kmp/` directory unless stated otherwise.

## Contents

- [M10 UI/UX](#m10-uiux)
- [Requirements](#requirements)
- [Modules](#modules)
- [Build and run](#build-and-run)
- [Tests and checks](#tests-and-checks)
- [Key mechanics](#key-mechanics)
- [Release](#release)
- [Rule: never suggest reinstalling](#rule-never-suggest-reinstalling)

## M10 UI/UX

| Area | Current behavior |
|---|---|
| Theme and icons | Solarpunk **«Лес»** in Light, Dark and Auto («Как в системе»), with Retro-cosmos icons. Theme-aware startup and navigation avoid white flashes; reduce motion disables transitions and the camera glow animation. |
| Bottom sheets and switches | Shared `GtModal`, `GtSheetTitle` and `GtSheetRow` keep sheets consistent. One scrim dims the background, without a second Android window dim. Switch rows expose one accessible node; tapping the title toggles the switch. |
| Plant detail | One watering-due line with a progress bar and last-watered date replaces the old days-left pill. Tapping the name opens «Название и место»: name, place chips and a custom place; explicit save, cancel/Back/scrim discard changes. |
| Care settings | Cards use −/+ steppers within server bounds: watering **1–365 days**, fertilizing **1–3650 days**, repotting/pruning **1–120 months**. Optional care types have switches; watering stays enabled. |
| Diagnosis | «Что с растением?» on a plant (and «Проверить на болезни» on its cover) opens the smart camera in «Болезнь» with a chip of that plant; the check uses its care history, and ✕ on the chip makes it a plant-less check. The result shows up to three causes with likelihood words and dots, advice and a disclaimer, not percentages. «Сохранить в журнал растения» optionally saves the photo and short result to an owned plant's journal; failures keep a retry action. |
| Smart camera | The dashboard's icon-only button opens the app's own **CameraX** viewfinder at once. The **«Растение · Болезнь»** mode switch sits above the shutter and gallery row (tap or swipe; the last mode is remembered). A taken or picked photo stays on the phone until the one explicit send, «Узнать вид» or «Проверить растение»; «Переснять» discards it. After a connection or service failure, «Вернуться к фото» brings back the same photo, note and plant chip without sending; only the next explicit send asks again. Limits and «not recognised» keep their own messages and offer a new photo instead. Plant detail («Что с растением?») and its photo sheet («Проверить на болезни») open the same camera in «Болезнь» with a plant chip. If the camera is denied or cannot start, the gallery (system photo picker) and the system camera remain. Plant mode uses Pl@ntNet; a result can become an Add Plant draft or be viewed without saving. Disease mode uses OpenAI without requiring a plant; «Это моё растение?» selects an owned plant for an optional journal save **without another diagnosis call**. No usage counter is shown; limits have clear messages. |
| Add Plant | Choosing a cover photo only sets the cover. Optional **«Узнать вид по фото»** sends that photo to Pl@ntNet; the offline species catalog remains available. A species can fill care defaults but never overwrites a typed name. The watering stepper starts at **7 days**. |
| Dashboard | Grid cells show the day count above the name at the start edge, so long names and the water button never cover it. If the first load fails (or the app is offline with no plant cache), the dashboard shows «Не удалось загрузить растения…» with **«Повторить»**, not an empty garden; Retry shows a spinner, runs one refresh at a time and can be repeated. A later refresh failure keeps the loaded plants under the «showing saved data» strip. In «Полить», a note explains that «Полить все» and «Отложить все» act on every plant that needs water, including plants hidden by search, and that postpone records the last watering as yesterday; the requests are unchanged. |
| Privacy and first sign-in | Outgoing photos lose EXIF/GPS metadata. The short login notice links to the privacy page. The intro appears only before the first sign-in; signing out returns to the login choice, not the intro. Contact: **greenthumbapp@proton.me**. |

The updated [privacy policy](../docs/privacy.html), [account-deletion page](../docs/account-deletion.html)
and [Data Safety notes](docs/data-safety.md) cover smart camera and saved diagnosis results.

## Requirements

| Tool | Requirement |
|---|---|
| **JDK 21** | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` is required for every Gradle and Maestro call (`scripts/check.sh` and `render-smoke.sh` set it themselves) |
| **Android SDK** | `compileSdk 37`, `targetSdk 36`, `minSdk 24`. `ANDROID_HOME` points to the SDK (on macOS — `~/Library/Android/sdk` by default); use full paths for `adb` and `emulator` |
| **Emulator** | AVD `elt_test` (system image API 34, `google_apis`). Needed only for installing the APK and the Maestro smoke; at most one emulator on the machine |
| **Maestro 2.10.0** (optional) | `/opt/homebrew/bin/maestro`; without it the gate simply skips the flow syntax check step |
| **Python 3** | for `scripts/i18n_check.py` in the gate |

Pinned versions live in `gradle/libs.versions.toml`: Kotlin **2.4.20**, Compose Multiplatform
**1.12.0**, AGP **9.3.1**, Ktor **3.5.2**, Room **2.8.5**, Coil **3.6.3**, Glance **1.2.0**.
Do not run `./gradlew --stop`; other work may share the Gradle daemon.

## Modules

| Module | What's inside |
|---|---|
| **`shared/`** | shared code (`commonMain` + `androidMain`/`jvmMain`, tests in `jvmTest`):<br>• `core.network` — `ApiClient` (Ktor, cookies in memory, 10 s default deadline; 30 s for creating a plant with a cover), `ApiError` and the shared Coil image loader; the only path to the network (grep rule K1)<br>• `core.storage` — SecureStore (AES-GCM + AndroidKeyStore; a file on JVM), `AppSettings` (DataStore: language, theme, grid, `cached_user`), Room `GreenThumbDb` (per-user database `plants_${userId}.db`)<br>• `core.platform` — expect/actual: Connectivity, RemoteKillSwitch, Haptics, pickImage/resizeJpeg and more (stubs on desktop where the platform has no equivalent)<br>• `data/` — `PlantRepository`, offline mutation journal, `WateringStatus`, `RefreshCoordinator`<br>• `ui.theme` / `ui.components` / `ui.screens.*` — auth, dashboard, add/edit plant, diagnosis, smart camera, journal/photos, progress and Profile; `ui.nav` owns navigation; en/ru strings and the offline species catalog live in `composeResources` |
| **`androidApp/`** | Android shell: `MainActivity`, `GreenThumbApplication`, `GtFirebaseMessagingService`, Glance widget + WorkManager refresh, `google-services.json`, backup rules (`full_backup_content.xml`, `data_extraction_rules.xml` — recovery-key files are excluded). `applicationId com.greenthumbplantcare`, `versionCode 7` / `versionName 0.8.0`; debug is `com.greenthumbplantcare.debug` ("GreenThumb Debug", `0.8.0-debug`, signed with `~/.android/debug.keystore`) and installs beside release |
| **`desktopApp/`** | JVM harness: `main.kt` (GUI launch, hot reload `hotRun`/`hotMcpServer`), `RealApiProbe.kt` (manual probe of the live API) |
| **`scripts/`** | `check.sh` (the gate), `grep-rules-selftest.sh`, `i18n_check.py`, `mcp-driver.mjs` |
| **`maestro/`** | M10 staged flows (`m10_*.yaml`), older feature flows and key-safe `render-*.sh` wrappers. Debug flows target `com.greenthumbplantcare.debug`; `GT_KEY_FILE` and `GT_DEVICE` override the key-file path and device. `release-smoke.yaml` targets release; `vault-wording.yaml` checks neutral password-manager strings. With `render-smoke.sh`, `GT_FLOW` selects the flow and `GT_STEP` passes `STEP=<stage>` |

## Build and run

| Task | Command |
|---|---|
| Android debug APK | `./gradlew :androidApp:assembleDebug` |
| Install on the emulator | `"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk` |
| Desktop GUI | `./gradlew :desktopApp:run` |
| Desktop hot reload (for UI development) | `./gradlew :desktopApp:hotRun --auto` |

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

# Android debug APK
./gradlew :androidApp:assembleDebug
# → androidApp/build/outputs/apk/debug/androidApp-debug.apk

# Install on the emulator (every adb call must use -s emulator-5554:
# a physical phone, if connected, is never addressed)
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 install -r \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Use `install -r` to update without clearing local data. Never uninstall or overwrite
the release package on a phone. Test flows that clear debug app state require a
throwaway account on `elt_test`; save its recovery key outside the app first.

Desktop app:

```bash
./gradlew :desktopApp:run            # regular GUI launch
./gradlew :desktopApp:hotRun --auto  # harness with hot reload (for UI development)
```

## Tests and checks

| Task | Command |
|---|---|
| Full gate for the kmp code | `bash scripts/check.sh` (from the repository root: `bash kmp/scripts/check.sh`) |
| Grep rules only (fast iteration) | `bash scripts/check.sh --grep-only` |
| Shared JVM tests, including Compose UI tests | `./gradlew :shared:jvmTest --rerun` (without `--rerun`, Gradle may report UP-TO-DATE and run nothing) |
| Android widget unit tests | `./gradlew :androidApp:testDebugUnitTest` |
| Maestro M10 flows | `maestro/m10_*.yaml`, one stage at a time; setup and examples below |
| Legacy Maestro smoke | `bash maestro/render-smoke.sh run` — see the M10 compatibility note below |
| Account deletion flow | `bash maestro/render-delete-account.sh run <key-file>` — **deletes the account of that key**; use only a separate test account |

```bash
bash scripts/check.sh             # full gate for the kmp code
bash scripts/check.sh --grep-only # grep rules only (fast iteration)
./gradlew :shared:jvmTest --rerun # fresh shared JVM test run
```

`scripts/check.sh` (the replacement for `npm run check` in `kmp/`) covers four
steps:

1. compilation of `:shared:compileAndroidMain`, `:shared:compileKotlinJvm`,
   `:desktopApp:compileKotlin`, plus `:shared:jvmTest` and
   `:androidApp:testDebugUnitTest` (widget tests);
2. grep rules K1–K5: network only through `core.network`, no unsplash URLs,
   `YYYY-MM-DD` dates in request bodies, the database only through
   `PlantRepository`, UI literals (`.dp`, `Color(`, `.sp`) only outside
   `ui/screens/**`;
3. Maestro flow syntax for `maestro/smoke.yaml` (without maestro installed the
   step is skipped; `GT_SKIP_MAESTRO=1` skips it explicitly);
4. i18n parity: the en/ru `strings.xml` key sets cover the i18n set.

### Maestro on the emulator

Use only `elt_test` as `emulator-5554`, with the debug APK **already installed**.
Confirm the AVD before running a flow:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 emu avd name

# /tmp/gt-test.key must already contain a throwaway account's key (mode 600).
# Only the file path is passed; never put the key in env, argv or logs.
GT_DEVICE=emulator-5554 GT_FLOW=m10_smartcam.yaml GT_STEP=login \
  GT_KEY_FILE=/tmp/gt-test.key bash maestro/render-smoke.sh run

# Keyless stage: open both smart-camera modes, without an upstream call.
/opt/homebrew/bin/maestro --device emulator-5554 test \
  --test-output-dir /tmp/gt-maestro/m10-open \
  -e STEP=open maestro/m10_smartcam.yaml
```

Read each flow's header for required plants, gallery fixtures, language and `STEP` values.
Do not run the whole directory blindly: some stages call live Pl@ntNet/OpenAI or delete accounts.

| Coverage | Flows in `maestro/` |
|---|---|
| Startup, themes, icons and sheets | `m10_flash_login.yaml`, `m10_flash.yaml`, `m10_icons.yaml`, `m10_sheets.yaml`, `m10_login_back.yaml` |
| Plant detail and care | `m10_water_line.yaml`, `m10_name_place.yaml`, `m10_care_cards.yaml`, `m11_plant_camera.yaml` |
| Smart camera and Add Plant | `m10_smartcam.yaml`, `m10_new_plant.yaml` |
| Metadata and sign-out routing | `m10_exif.yaml`, `m10_signout_route.yaml` |

**Legacy smoke compatibility:** `smoke.yaml` predates M10. Its editable watering-field
and plant-detail due-text selectors are stale. The gate checks its **syntax only**;
that does not prove it passes on the redesigned UI. Use the M10 flows for current UI checks.

For flows that enter a recovery key, use `render-smoke.sh` (or the dedicated deletion
wrapper), never raw Maestro. The wrapper reads the key file, filters key echoes from
output and deletes temporary copies. Never capture screenshots or hierarchy dumps
while the key is visible. Restore emulator network, locale, theme, font and animation
settings after a run.

## Key mechanics

<details>
<summary><b>Storage and session</b></summary>

SecureStore (AES-GCM + AndroidKeyStore; a file on JVM) + `AppSettings` on
DataStore. Offline session: if the network is unavailable at startup and
`cached_user` exists — the app works from Room with a "no network" banner.
The intro is marked seen on a successful sign-in, including saved-key sign-in;
sign-out does not bring it back.

</details>

<details>
<summary><b>Import from the Expo handoff</b></summary>

The Expo handoff release (versionCode 5) writes `gt-handoff.json` (recovery key +
settings) into `filesDir`; KMP reads and deletes it on first launch. An update install
without a handoff or stored key shows "Key not found"; a fresh install starts with
the intro/login flow. Files with the key are excluded from Android backup and device transfer.

</details>

<details>
<summary><b>Offline mutation queue</b></summary>

Per-user Room database + a `pending_mutations` journal: on Network/Timeout the
change stays queued and is delivered when the network appears (the UI honestly
says "will be applied once you're back online"); on a definite server rejection
(4xx/5xx) — a rollback from the snapshot.

</details>

<details>
<summary><b>Species, journal and photo privacy</b></summary>

The bundled en/ru species catalog works offline. Pl@ntNet identification and OpenAI
diagnosis require an explicit action and a network connection. Selecting an Add Plant
cover never starts identification automatically.

Journal events and timeline photos are cached per user; adding notes/photos and saving
a diagnosis require a connection (no offline queue for these actions). A saved diagnosis
is a timeline photo plus a short journal caption. Photos use the shared JPEG preparation
path; `photoDataUri` also strips APP1 EXIF/XMP segments before upload. `ExifGuardTest`
and route tests cover identify, diagnose, cover and timeline uploads.

</details>

<details>
<summary><b>Seasonal watering, progress and widget</b></summary>

Seasonal watering uses the selected season mode and device timezone; dashboard, plant
detail and widget share the watering rule. Streaks and achievements have cached offline
display. The Android Glance widget reads Room without network access and shows up to five
due/overdue plants with plant deep links. Repository changes and a periodic WorkManager
job refresh it.

</details>

<details>
<summary><b>Recovery-key backup and restore</b></summary>

Android Credentials supports saving/restoring the recovery key through a password
manager; manual key entry stays available. Profile also offers a QR export, protected
by `FLAG_SECURE` while visible. Keep a recovery-key backup before signing out or changing
devices.

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

Wired into `:androidApp` (R8 mapping upload enabled for release; native-symbol upload
disabled because the app has no own native code);
debug builds do not send reports (`isCrashlyticsCollectionEnabled = false`) —
probes never reach the baseline.

</details>

## Release

**M10 state:** one signed **0.8.0 / 7** AAB has been built locally.
Google Play upload and rollout remain owner actions; no upload has been performed.

### Release build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :androidApp:bundleRelease
# → androidApp/build/outputs/bundle/release/androidApp-release.aab

# Optional minified APK for emulator testing
./gradlew :androidApp:assembleRelease
# → androidApp/build/outputs/apk/release/androidApp-release.apk (when signing is configured)
```

Release builds use R8 minification and resource shrinking
(`isMinifyEnabled`, `isShrinkResources`); custom keep rules live in `androidApp/proguard-rules.pro`,
each with its reason. Today there is one rule: it keeps the `androidx.work.InputMerger` constructor
(without it, R8 full mode breaks the widget worker and the widget stays on a loading spinner). The R8 mapping is at `androidApp/build/outputs/mapping/release/mapping.txt`, and
`uploadCrashlyticsMappingFileRelease` sends it to Crashlytics.

Signing configuration lives at `~/.config/greenthumb/release-signing.properties`,
outside the repository. Never print its contents or passwords. Keep it at mode 600; the upload keystore is
`~/.config/greenthumb/upload-keystore.jks`. `-PgtReleaseSigning=<path>` overrides the path. When the file is absent, the build
prints `GreenThumb: release signing file … not found` and the release APK/AAB is unsigned (no fallback
key). A file with a missing key fails the build and names the key.

For a minified release smoke, use the signed APK from `:androidApp:assembleRelease`
on `elt_test` only, with a throwaway account (never overwrite a phone's release app).
Run `maestro/release-smoke.yaml` stage by stage
(`STEP=create|plant|widget|progress|offline|delete|saved-key`, see its header).
This is an older flow: check its selectors against M10 before using it as release evidence.
`maestro/vault-wording.yaml` checks the neutral "save key to password manager" strings in ru and en.

Certificate fingerprints (public values):

| Certificate | SHA-256 |
|---|---|
| Upload key | `47:D0:AA:F4:6F:D5:4F:27:3B:6F:F9:8D:47:E4:0A:1B:BE:9A:9E:FE:C6:E6:C2:2D:D2:EC:BC:3C:24:ED:88:DB` |
| Play App Signing | `C1:48:76:7E:22:54:51:7A:05:B5:F9:F6:ED:D6:F8:0F:59:35:23:B4:AA:8B:A5:A9:53:8C:36:F1:EA:5C:6C:49` |

`https://greenthumb.xmpp.site/.well-known/assetlinks.json` lists both packages. The server reads
the fingerprints from the env vars `ASSETLINKS_FINGERPRINTS_RELEASE` and
`ASSETLINKS_FINGERPRINTS_DEBUG` on the VPS; after a change, run
`pm2 reload ecosystem.config.cjs --update-env`.

### Release checklist (0.8.0 / 7)

The **owner** performs these steps by hand, in this order:

1. `git push` — GitHub Pages publishes `docs/privacy.html` and `docs/account-deletion.html`
   only after a push.
2. Back up `~/.config/greenthumb/upload-keystore.jks` and `release-signing.properties` offline.
3. Firebase Console: enable Crashlytics; publish the Remote Config parameter
   `min_supported_build` = 0.
4. Play Console: upload the signed AAB to internal testing. If a rebuild is needed,
   use the release command above; the output is
   `androidApp/build/outputs/bundle/release/androidApp-release.aab`.
5. Play Console: fill in the Data Safety form from [`kmp/docs/data-safety.md`](docs/data-safety.md).
6. Play Console → App integrity: check that the app signing SHA-256 equals `C1:48:…:6C:49`
   (table above). If it differs, update `ASSETLINKS_FINGERPRINTS_RELEASE` on the server.
7. Closed testing (20 testers × 14 days), then the rollout ladder from
   [`kmp/docs/release-gate-rollout.md`](docs/release-gate-rollout.md).

### Rollout
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
