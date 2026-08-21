# Moveo One Android Study Wrapper

Android participant app for Moveo One research studies — activation, consent,
and a locked-down in-app browser (WebView) that injects the Moveo analytics
tag into the study's websites. The Android sibling of the iOS study wrapper.

**Status: in progress.** Phase a0 done (project skeleton: `:app` +
`:studycore`, vendored tag + fixtures, `scripts/`); implementation follows
[docs/plan.md](docs/plan.md) phase by phase. Build: `./gradlew build`; unit
tests: `./gradlew :studycore:test`. [docs/](docs/) is the plan and context
package:

| Doc | What |
|---|---|
| [docs/README.md](docs/README.md) | What we're building, architecture, phases at a glance, key constraints |
| [docs/context.md](docs/context.md) | Why this app exists and why this shape (investigation + rejected alternatives) |
| [docs/plan.md](docs/plan.md) | Phase-by-phase implementation plan (a0–a4), StudyKit→studycore port map, milestones, platform-difference ledger |

## Build & run

One codebase, two build types — the Android analogue of the iOS Debug/Release
split. **Debug** compiles in the QA surface from the `app/src/debug` source
set (gear icon + debug settings screen, event spy, API-base/ingest overrides,
`MOVEO_*` intent extras, cleartext-to-localhost network config, relaxed
tag-endpoint guard for dev configs). **Release** contains none of that code —
the `app/src/release` source set replaces every hook with a no-op, so the
binary is pinned to the prod backend and the strict config guard. Nothing to
strip manually; a release build *cannot* expose debug features.

Orthogonally, two **store flavors** (docs/plan.md §h1): `play` (Google Play)
and `huawei` (AppGallery). They differ only in the per-flavor `StoreSupport`
object (store links + wording — the huawei binary contains no Google Play
URLs); everything else is identical, including versionCode/versionName.
Day-to-day development uses `playDebug`.

### Debug build (development / dev backend)

```sh
# Build + install on the connected device/emulator
./gradlew :app:installPlayDebug

# Launch pointed at the DEV environment (the override persists in prefs;
# also settable later via the in-app gear → "Config API base override")
adb shell am start -n one.moveo.studywrapper/.MainActivity \
  -e MOVEO_API_BASE https://dev-pigeon.moveo.one/api/v1/extension-config \
  -e MOVEO_EVENT_SPY 1
```

Dev studies declare the dev ingestion URL in their config; the debug build
accepts that and auto-redirects the tag's event POSTs to it. Omit
`MOVEO_API_BASE` for the prod config base, or run the local mock instead:

```sh
scripts/mock-backend.sh                 # serves the extension repo's mock on :8787
adb reverse tcp:8787 tcp:8787           # emulator → host
# then MOVEO_API_BASE http://localhost:8787/api/v1/extension-config
```

Other debug-only intent extras (scripted QA): `MOVEO_AUTO_CODE` (activate a
code on launch), `MOVEO_AUTO_FLOW consent|enroll|browser` (auto-step to that
screen), `MOVEO_AUTO_NAV <url>` (navigate the live study browser; also
accepts `javascript:` URLs), `MOVEO_INGEST_OVERRIDE <url>` (explicit event
reroute). Watch backend calls, bridge messages, lead launches, and spied
events:

```sh
adb logcat -s moveo-backend moveo-events
```

### Release build (prod)

```sh
./gradlew :app:bundlePlayRelease      # → .aab for Play Console upload
./gradlew :app:assembleHuaweiRelease  # → signed .apk for AppGallery Connect
```

Release is hard-pinned to production (`pigeon.moveo.one` config API, the
baked tag ingest endpoint): intent extras are ignored, there is no gear or
settings screen, no event spy, no ingest redirect, and no cleartext config.

Signing (docs/plan.md §h2): one keystore serves as the Play **upload** key
(Play App Signing re-signs for distribution) and the AppGallery
**distribution** key. Secrets come from `moveo.keystore.*` entries in
`local.properties` (git-ignored) or `MOVEO_KEYSTORE_*` env vars on CI; with
neither present the release build is unsigned but still build-verifiable
(`unzip -l`/`aapt` audits). The keystore file and password must be backed
up — the AppGallery key is unrecoverable if lost.

## Releasing (Google Play + Huawei AppGallery)

Every release ships to **both stores from the same commit, with the same
`versionCode`/`versionName`** — never let the stores drift.

**1. Bump the version** in `app/build.gradle.kts` (`versionCode` +1, set
`versionName`), commit.

**2. Pre-release checks** (all must pass before any upload):

```sh
./gradlew build                                # all modules, all variants, tests
./gradlew :app:bundlePlayRelease :app:assembleHuaweiRelease

# huawei binary carries no rival-store links (AppGallery rejects them) — must print 0
unzip -p app/build/outputs/apk/huawei/release/app-huawei-release.apk "classes*.dex" \
  | grep -c "play.google.com"

# both artifacts signed with the pinned cert (SHA-256 in plan.md §h2)
apksigner verify --print-certs app/build/outputs/apk/huawei/release/app-huawei-release.apk
keytool -printcert -jarfile app/build/outputs/bundle/playRelease/app-play-release.aab
```

Then the release smoke test on a device/emulator: install the huawei APK
(uninstall any debug build first — different signature), no gear icon, a
bogus code round-trips to prod ("Code not recognized").

**3. Google Play** — upload `app-play-release.aab` in Play Console:
closed-testing track first, then promote to production ([plan §a4.2–a4.3](docs/plan.md):
Data safety form and listing must mirror the iOS privacy labels; include a
working prod test code in the review notes).

**4. Huawei AppGallery** — upload `app-huawei-release.apk` in AppGallery
Connect and submit for review (1–3 business days). Full walkthrough —
account setup, privacy declaration answers, reviewer notes, Cloud Debugging
test on a real GMS-less device: [docs/h3-appgallery.md](docs/h3-appgallery.md).

**5. After both are live**, tag the commit (`v<versionName>`). First-release
extras — store listing assets, privacy policy URL, App Links fingerprints —
are tracked in [plan §a4 and §h](docs/plan.md).

## Sibling repos (read-only dependencies)

- `../../chrome-extension/moveo_one_chrome_extension/` — the normative config
  schema, backend contract, the tag file (vendored byte-identical), mock
  backend, schema fixtures.
- `../../ios-study-wrapper/ios-study-wrapper/` — the reference
  implementation this app mirrors screen-for-screen and rule-for-rule.

## Tag update discipline ([plan §a4.4](docs/plan.md))

Tag updates ship **deliberately**: re-run `scripts/vendor-tag.sh` against
the updated extension repo, commit the new `VENDOR.json`
(commit hash + sha256 is the provenance), re-run the fixture suite and the
a3.1 parity diff, then release. Never edit `assets/moveo-one.js`.

**Exit criteria (a4 + h):** production release on Google Play and Huawei
AppGallery; pilot study data verified comparable with desktop/iOS cohorts.
