# Crash baseline before rollout (Stage 12 item 7, VAL-REL-006)

**Execution is user-gated wherever console/prod access is needed.** This
document defines the metrics, the halt thresholds and the baseline that every
step of `release-gate-rollout.md` is compared against.

## 1. Telemetry: what is wired in

**Crashlytics in the build** (`kmp/androidApp`, commit M12 release-rollout-prep):

| Element | Value |
|---|---|
| SDK | `com.google.firebase:firebase-crashlytics`, version set by the BoM `firebaseBom = 34.19.0` (the main module, NOT -ktx: KTX modules were dropped from the BoM as of 34.0.0, and on old ktx the `mapping_file_id` is lost — firebase-android-sdk #8564) |
| Gradle plugin | `com.google.firebase.crashlytics` **3.0.8** (pinned in `kmp/gradle/libs.versions.toml`), applied only in `:androidApp` |
| Mapping files (R8 deobfuscation) | `mappingFileUploadEnabled = true` for release — SET EXPLICITLY: AGP 9.3.1 does not register `uploadCrashlyticsMappingFile<Variant>` itself (#8545, fixed in AGP from 9.3.3; the mission does not bump the AGP 9.3.1 pin) |
| Native symbols (Stage 12 item 7 "upload of native library symbols") | `nativeSymbolUploadEnabled = true` for release: libsqliteJni.so (sqlite-bundled), libdatastore_shared_counter.so, libandroidx.graphics.path.so |
| Debug builds | `isCrashlyticsCollectionEnabled = false` in `GreenThumbApplication` (FLAG_DEBUGGABLE): validator probes on the emulator never reach the release baseline |
| Initialization | automatic (`FirebaseInitProvider` from google-services, the same `google-services.json`, project `greenthumb-5e3df`, package `com.greenthumbplantcare`) |

**User-gated Firebase Console steps** (workers do not execute them):

1. ☐ Firebase Console → project `greenthumb-5e3df` → **Crashlytics** →
   "Enable Crashlytics" for the app (on first open the console offers to enable
   the service up front; without this step the build's upload tasks and crash
   delivery do not work).
2. ☐ After enabling — verify symbol upload by the first release build: the
   build must run `uploadCrashlyticsMappingFileRelease` /
   `uploadCrashlyticsSymbolFileRelease` without errors; in the console,
   Crashlytics → the "Mapping files" / "dSYM & .sym file" tab shows entries for
   `com.greenthumbplantcare`.
   Dry-run measurement (2026-10-01, before the console step): the gradle tasks
   `generateCrashlyticsSymbolFileRelease` + `uploadCrashlyticsSymbolFileRelease`
   run successfully (exit 0), `uploadCrashlyticsMappingFileRelease` —
   NO-SOURCE (no mapping file without R8/minify — normal; once R8 is on, upload
   is already configured with `mappingFileUploadEnabled = true`). The remainder
   is confirming the VISIBILITY of symbols/mapping in the console after it is
   enabled.
3. ☐ Test crash: on an internal build (not on a personal phone — stop M8,
   AGENTS.md) trigger a crash and verify it appears in the Crashlytics console
   and is deobfuscated. The crash recipe is up to the user (for example, a
   temporary debug call in a dev branch, removed afterwards).

## 2. Baseline (what is compared against what)

Plan metrics (Stage 12 item 7): **share of sessions with a crash** and **share
of sessions with an ANR**.

| Era | Source of numbers |
|---|---|
| Expo (base, versionCode ≤ 5) | **Android Vitals** in Play Console (the Expo build has no Crashlytics): Production/Testing → Statistics/Vitals → crash rate, ANR rate by version |
| KMP (rollout steps) | **Crashlytics**: crash-free sessions / sessions; ANR: Vitals (the Crashlytics-ANR signal comes from the same Play contour) + Crashlytics sessions |

**Baseline table (filled in by the user before leaving the internal track;**
values are taken over the 14 days before the gate date):

| Metric | Baseline value | Measurement date | Where taken |
|---|---|---|---|
| Share of sessions with a crash (Expo, Vitals) | ______ | ______ | Play Console → Vitals → Crash rate |
| Share of sessions with an ANR (Expo, Vitals) | ______ | ______ | Play Console → Vitals → ANR rate |
| Active installs total | ______ | ______ | Play Console → Statistics |
| Share active on versionCode ≥ 5 | ______ | ______ | Active devices by app version (this is the entry to gate VAL-REL-002) |

Until the fields are filled in, the rollout does not leave the internal track
even with the adoption gate formally closed: there is nothing to compare the
steps against.

**Honest limitation:** before the first KMP release build Crashlytics has no
data; the "KMP baseline" at the first step = the thresholds below themselves,
not a relative comparison. Comparison with the Expo base becomes possible from
the second step on.

## 3. Halt thresholds (by the numbers, not by eye)

Any threshold exceeded on a step → **halt** (`release-gate-rollout.md`) and
`incident-runbook.md`. Advancing is allowed only if the step has collected its
minimum observation (same place, the ladder table).

| # | Metric | Halt threshold | Measurement |
|---|---|---|---|
| 1 | Share of sessions with a crash (Crashlytics) | > 0.5% **or** above the Expo baseline by more than 0.3 p.p. | Crashlytics → Sessions; automatic |
| 2 | Share of sessions with an ANR | > 0.47% (the Vitals "bad behavior" threshold) **or** above baseline by 0.3 p.p. | Vitals; automatic |
| 3 | Share of launches with the "key not found" screen on update installs | > 1% of launches | manual probe: the list is visible only locally (no screen telemetry — analytics are deliberately not wired in); tracked via complaints and a control probe on an updated device |
| 4 | Share of failed logins | > 2% of attempts | user-gated: `login-recovery` logs on the VPS (ssh — the user); distinguish 401 (wrong key) from 4xx/5xx/Neon quota errors |
| 5 | Mutations stuck in the journal for > 24 h | > 1% of mutations | no direct server-side visibility; controlled by offline-queue probes on the internal-track build + "my change doesn't save" complaints |
| 6 | Devices without an active push subscription after ≥ 7 days on KMP (among those that had Expo push) | > 10% | user-gated: SELECT over `fcm_push_subscriptions`/`expo_push_subscriptions` on prod (read-only; the user) |

Thresholds 1–2 are automatic (Crashlytics/Vitals dashboard). Thresholds 3–6 are
first-release ones: there is no automatic event telemetry for them in the app
(analytics are P1 roadmap, outside the mission); they are measured by manual
probes and server logs; this is agreed with the plan as "tracked separately".
Thresholds may be overridden only BEFORE the rollout.

## 4. How this relates to the kill-switch and the rehearsal

- The kill-switch (`min_supported_build`) does not catch a crash before the
  config is read — those are covered by: running the release build on a clean
  device (the gate checklist) + threshold 1 of this table + the incident
  rehearsal (`incident-runbook.md`).
- Going back to the Expo build is closed (the key has been overwritten by the
  KMP version) — the only recovery path is forward, with a fixed KMP build.
