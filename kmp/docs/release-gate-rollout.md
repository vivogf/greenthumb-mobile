# Rollout gate and staged rollout of the KMP build (Stage 12 items 2–3, VAL-REL-002)

**All execution is user-gated.** Workers do not enter Play Console, Firebase
Console or EAS: every step below is performed by the user.
Canonical sources: `kmp-migration-plan.md` Stage 12 items 2–3 and items 6–7;
`missionDir/library/kill-switch.md` (kill-switch mechanics and limitations);
`missionDir/library/release-checklist.md` (the handoff-release versionCode 5
path).

## Version accounting

| Build | versionCode | versionName | Where it lives |
|---|---|---|---|
| Expo (historical binaries) | 2, 3 | 1.0.0 | the .apk base before the handoff |
| Expo handoff release (store, `allowBackup=false` manifest) | **5** | 1.0.0 | internal testing |
| KMP build (`kmp/androidApp`) | **6** | 0.1.0 | internal track (first publication), then staged rollout |

`versionCode = 6` is set in `kmp/androidApp/build.gradle.kts` and sits above
all active Expo tracks (Stage 1 item 8 of the plan). Any fixed build after the
rollout starts must have a `versionCode` greater than the previous one (see
`incident-runbook.md`).

## Why the gate (do not shorten or skip it)

The Expo base with versionCode ≤ 4 does not write `gt-handoff.json` (the file
appeared only in handoff release 5). Installing KMP on top of such a base leaves
the user without a key: KMP will find neither a handoff nor a key of its own —
the "key not found" screen, nothing to recover. The adoption gate is the only
protection against such users receiving the KMP build from Play. See Stage 12
item 2 of the plan and `library/release-checklist.md` ("migration gate").

## Gate: ≥95% of active installs on versionCode ≥ 5

**Rule:** the KMP build does not leave internal testing for staged rollout to
production until Play Console shows: **≥ 95% of the app's active installs sit
on versionCode ≥ 5.**

**Where to look (Play Console):**

1. Play Console → **GreenThumb — Plant Care** → **Statistics** → the
   **Active devices by app version** metric (user metrics). If the item is named
   differently in the current UI — look for "Active devices by app version".
2. Additionally: **Testing → Internal testing → Statistics** — the same
   version breakdown for the track.
3. Compute: `active on ≥5 / all active`. Threshold **0.95**.

**Expected tail:** devices that sync with Play over days/weeks ("sleeping" —
see library/release-checklist.md §2.4). They are covered by: the handoff OTA
track (the update applies on first launch), the "save your key" modal and the
KMP "key not found" screen (manual key entry). The gate waits, it is not
lowered.

## Before the rollout — preconditions (checklist in order)

1. ☐ **Firebase Remote Config: the `min_supported_build` = 0 parameter is
   created and PUBLISHED before the KMP build rollout.** Firebase Console →
   project `greenthumb-5e3df` → Remote Config → Create parameter → name
   `min_supported_build`, type Number (long), value `0` → Publish. Default 0 =
   the switch is inactive; raise the value only to recall a specific build in an
   incident (`incident-runbook.md`). Mechanics and limitations —
   `library/kill-switch.md`.
2. ☐ **Crashlytics is enabled for the app in Firebase Console** and the baseline
   is recorded (`crash-baseline.md`): enabling the service is user-gated; after
   enabling — a symbol upload run and a test crash (on the emulator/an
   internal-track build, not on a personal phone).
3. ☐ **docs updated and pushed:** `docs/privacy.html` (already rewritten in the
   repo for crash-report collection — verified in this same feature; the live
   page https://vivogf.github.io/greenthumb-mobile/privacy.html will update ONLY
   after the user's `git push`).
4. ☐ **Data Safety in Play Console updated:** App content → Data Safety → add
   the collection of **Crash logs** and **Diagnostics/performance data**
   (Crashlytics, a Google processor). Before this step the store declaration
   diverges from reality.
5. ☐ **Adoption gate ≥95% on versionCode ≥ 5** — closed (see above).
6. ☐ **Closed testing of 20+ testers × 14 days closed** (a Personal-account
   requirement before Production; see Phase 8 in CLAUDE.md — applies to the app
   as a whole, not just the Expo build).
7. ☐ The KMP release build is run on a clean device before the rollout
   (architecture.md §12: the kill-switch does not catch a crash before the
   config is read).

## Staged rollout: 5% → 100%

The plan's ladder (Stage 12 item 6): Android beta on the internal track →
handoff adoption gate → Android 5% → … → 100%. Each step is a new release in
Production with a rollout percentage:

| Step | Minimum observation | Transition criterion |
|---|---|---|
| 5% | ≥ 48 h **and** ≥ 50 sessions | all `crash-baseline.md` thresholds hold |
| 10% | ≥ 48 h **and** ≥ 50 new sessions | same |
| 20% | ≥ 48 h **and** ≥ 50 new sessions | same |
| 50% | ≥ 72 h **and** ≥ 100 new sessions | same |
| 100% | — | — |

The numbers are first-release thresholds (a base of ~tens of installs; the
"minimum sessions" guards against conclusions from empty data, the "minimum
hours" against conclusions from a single reminder peak window ~09:00 MSK). They
can be overridden before the rollout, but any override happens before, not
during.

**Mechanics (Play Console):**

1. Testing → Internal testing → Create new release → upload the AAB
   (`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   ./gradlew -p kmp :androidApp:bundleRelease`, see signing in
   `incident-runbook.md`) → release notes → roll out to testers.
2. After the gate: Production → Create new release → upload the same AAB →
   **Staged rollout 5%** → Roll out.
3. Next step: Production → Releases → the active release → **Update rollout
   percentage** → the next value. New AABs are not needed for steps until there
   are new commits.
4. Checking the share: Statistics → Active devices by app version (production).

**Stopping the rollout:** Production → Releases → active release → **Halt
staged rollout**. Installs that already updated stay on the version (staged
rollout does not roll the version back) — they are serviced by the kill-switch
(the "update the app" screen) and `incident-runbook.md`. Never suggest a
reinstall.

## Halt criteria (the canonical source is crash-baseline.md)

Any threshold exceeded on the current step → the rollout is stopped (halt),
then — `incident-runbook.md`. The full threshold sheet with measurement
procedures lives in `crash-baseline.md`; in brief: the share of sessions with a
crash and with an ANR no higher than baseline, "key not found" on update
installs ~0, failed logins, mutations stuck in the journal, devices without a
push subscription — per the thresholds.

## Rollout completion order after 100% (plan item 9 — not this feature)

"100% rollout" = 100% entitled to update, not 100% updated. The support window
for Expo endpoints and removal of the Expo sources is a separate user decision
after the window (not executed within the mission).
