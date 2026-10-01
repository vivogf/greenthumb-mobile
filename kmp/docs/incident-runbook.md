# Post-rollout incident runbook + rehearsal (Stage 12 item 8, VAL-REL-005)

**Execution is user-gated** (Play Console, Remote Config, publishing).
The incident walkthrough was rehearsed BEFORE the rollout, not in the heat of
the moment. At the end of the document — a dry-run report (what was checked
locally, what remains user-gated).

Related documents: `release-gate-rollout.md` (gate, ladder, halt),
`crash-baseline.md` (thresholds), `library/kill-switch.md` in missionDir
(kill-switch mechanics and limitations).

## 0. Hard rules (read before any incident)

1. **SUGGESTING A REINSTALL IS FORBIDDEN** — not in support, not in release
   notes, not in dialogs, not in the FAQ: on Android a reinstall destroys the
   only recovery key → the account is lost forever. The right advice is
   "update the app in Google Play" (installing an update does not touch the
   data). The app copy never mentions reinstalling (verified by grep, see the
   rehearsal report, step 4).
2. **Going back to an Expo build is closed.** After the first launch of the KMP
   version the key has been overwritten by its storage: the old Expo build would
   read a stale value from its own SecureStore (Stage 12 item 8 of the plan).
   The only recovery path is FORWARD: a fixed KMP build with a higher
   `versionCode`. Tags/artifacts of the last Expo build (`backup/*` on GitHub,
   APKs/) are kept until the end of the support window — for building, not for
   rolling users back.
3. **Staged rollout does not roll the version back.** Halt stops NEW
   recipients; installs that already updated are helped by the kill-switch (the
   "update the app" screen) or by a new fixed version.
4. **The kill-switch does not catch a crash on startup before the config is
   read** (a native crash, a crash before the first frame) and does not work
   without a network at startup — `library/kill-switch.md`
   §"What the kill-switch does NOT catch".

## 1. Sign of an incident → stop the rollout

**Triggers:** any halt threshold from `crash-baseline.md` is exceeded
(crashes/ANR/"key not found"/logins/mutation journal/push subscriptions), or a
reproducible crash on a release build.

**Action (user, ~2 minutes):**

1. Play Console → Production → Releases → the active release with staged
   rollout → **Halt staged rollout** (same for Testing → Internal testing, if
   the incident is still there).
2. Record the halt time and the last percentage reached — the rollout resumes
   from there after the fix.
3. If the defect is not version-specific (backend) — fix the backend, leave the
   rollout alone.

## 2. Scope assessment: who was affected

1. Play Console → Production → Releases → release → rollout share → estimate
   the number of affected installs (percentage × active installs from
   Statistics).
2. Crashlytics → Issues: top crashes by version/OS; verify the data is
   deobfuscated (mapping upload works — `crash-baseline.md` §1).
3. If the crash happens BEFORE the config is read (the app dies before the
   kill-switch) — the kill-switch cannot help; only a fixed build can (step 3).

## 3. Fixed build

1. Fix in `kmp/` → local commit (the push is the user's).
2. **`versionCode` must be strictly greater than the previous one** (was 6 →
   becomes 7, 8, …): `kmp/androidApp/build.gradle.kts` → `versionCode = <N+1>`;
   bump `versionName` on the patch semver level. An upload to the track with a
   lower versionCode will be REJECTED by Play — this cannot be worked around.
3. Build the AAB (signing is the user's; the release keystore is not in the
   repo):

   ```bash
   cd kmp
   JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
     ./gradlew :androidApp:bundleRelease
   # artifact: androidApp/build/outputs/bundle/release/androidApp-release.aab
   ```

4. Verify the artifact before uploading:

   ```bash
   $ANDROID_SDK/build-tools/36.0.0/aapt2 dump badging \
     androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk \
     | grep -E "versionCode|native-code"
   # or bundletool dump manifest for the AAB: versionCode="7"
   ```

5. Before the release build — make sure Crashlytics is enabled in the console
   (`crash-baseline.md` §1), otherwise the symbol upload tasks will fail the
   build.

## 4. Delivering the fixed version to the affected

1. Play Console → Production → Create new release → upload the AAB with
   `versionCode N+1` → **Staged rollout = the last percentage reached before
   the halt** (do not jump to a higher one: the step threshold checks resume
   from the same spot, `release-gate-rollout.md`).
2. Delivery to the affected: the update arrives through the Play update channel
   (hours to days); it cannot be forced per device. Installs where the defect
   blocks STARTUP are helped by the kill-switch: Remote Config →
   `min_supported_build` = N (publish ONLY if the old version must be stopped
   entirely; the "update the app" screen does not fix data by itself) → once N+1
   appears in the rollout, set `min_supported_build` back to 0.
3. Delivery check: Statistics → Active devices by app version — the share of
   `versionCode N+1` grows; Crashlytics — the crash trend of version N falls.

## 5. Closing the incident

1. The rollout step on which the incident happened is repeated from scratch
   (the observation minimum starts over).
2. Post-mortem in the report: trigger, halt→fix→resume times, process gaps.
3. If the incident touched user local data (key/DB) — there are no recoverable
   paths: support only helps create a NEW account (and never a reinstall).

---

## Rehearsal report (dry run of the steps, 2026-10-01)

A run of the steps without publishing (publishing is user-gated). Executor:
feature worker `release-rollout-prep`, on a local machine, without
Play/Firebase consoles.

| # | Runbook step | Status | Observation |
|---|---|---|---|
| 1 | Halt staged rollout in Play Console | **user-gated** (console) | the path is written up (§1); not executed in the dry run — the Play UI is not available to workers under the mission boundaries |
| 2 | Fixed build with a higher versionCode | **checked locally** | see steps 2a–2e below |
| 3 | Delivery to the affected (Play channel + kill-switch) | **user-gated** | the §4 procedure is written up; kill-switch mechanics were separately verified on the emulator in the `kmp-remote-kill-switch` feature (VAL-REL-001, evidence `missionDir/evidence/m12-kill-switch/`) |
| 4 | Ban on suggesting reinstall | **checked locally** | grep for the word "reinstall" in both languages: in KMP user-facing UI strings (composeResources) — **0 matches**, in RN copy (app/components/i18n) — **0**; in KMP KDoc comments — 2 matches (SessionSurfaces.kt:83, KillSwitchGate.kt:28), both documenting the "WITHOUT reinstalling" path — these are not user advice. The rule is recorded in §0 |
| 5 | Return to Expo is closed | **documented** | the invariant of Stage 12 item 8 of the plan (§0.2); not executable by definition — the check = the absence of downgrade plans in the project |

Step 2 in detail (the fixed build was actually built in the dry run):

- 2a. `versionCode` 6 → 7 in a temporary tree (the change was NOT committed,
  reverted after the run).
- 2b. `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  ./gradlew :androidApp:assembleRelease` — BUILD SUCCESSFUL (84 tasks; the
  release APK artifact built; release signing was not applied — the keystore is
  with the user, packaging is enough for a dry run).
- 2c. Artifact check with `aapt2 dump badging`:
  `package: name='com.greenthumbplantcare' versionCode='7' versionName='0.1.0'`
  (native code in place: arm64-v8a, armeabi-v7a, x86, x86_64) — confirmed;
  after the run the versionCode was returned to 6.
- 2d. Release Crashlytics tasks work on this tree:
  `injectCrashlyticsMappingFileIdRelease` + `injectCrashlyticsBuildIdsRelease` +
  `injectCrashlyticsVersionControlInfoRelease` — in the `assembleRelease` graph;
  an explicit run of `uploadCrashlyticsSymbolFileRelease` +
  `generateCrashlyticsSymbolFileRelease` — executed, exit 0
  (`uploadCrashlyticsMappingFileRelease` — NO-SOURCE: no mapping file while
  R8/minify is off — this is normal; once R8 is on, upload is already
  configured). The console side (symbol/mapping visibility in Firebase Console)
  — user-gated.
- 2e. Conclusion: the "fixed version with a higher versionCode" mechanics work
  on this tree with no extra steps besides signing (user-gated).

Rehearsal outcome: the locally executable steps are verified; console steps are
written up and remain user-gated. A repeat rehearsal before the real rollout is
not required — if the Play UI changes, update §1/§4.
