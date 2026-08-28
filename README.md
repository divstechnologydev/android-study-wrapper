# Moveo One Android Study Wrapper

Android participant app for Moveo One research studies — activation, consent,
and a locked-down in-app browser (WebView) that injects the Moveo analytics
tag into the study's websites. The Android sibling of the iOS study wrapper.

**Status: in progress.** Phases a0–a3.2 (skeleton, `:studycore` port,
activation/consent/browser, parity capture, feature matrix), h1–h2 (store
flavors, signing), a1.4 (link-first activation with transaction id +
client enrollment id) and a2.8 (finishing a study) are done; open items
are platform-side (assetlinks, landing page) and release paperwork. Implementation follows
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
code — or a full setup link — on launch), `MOVEO_AUTO_FLOW
enroll|browser` (auto-accept consent, optionally open the browser), `MOVEO_AUTO_NAV <url>`
(navigate the live study browser; also accepts `javascript:` URLs),
`MOVEO_INGEST_OVERRIDE <url>` (explicit event reroute). Watch backend calls,
link arrivals, bridge messages, lead launches, and spied events:

```sh
adb logcat -s moveo-backend moveo-events
```

## Setup links (deep links)

The setup link is the **only** way into a study — manual code entry is
intentionally not in the UI (a typed code cannot carry the panel provider's
transaction id; the model still accepts one via `codeInput` for the DEBUG
`MOVEO_AUTO_CODE` hook, so re-adding the field later is UI-only). Both link
shapes land in `MainActivity.handleIntent` → `AppViewModel.handleOpenUrl`
and go straight to fetch → consent (no summary sheet in between — the
consent page names the study, lists the tracked sites and carries the
replace warning, same as the extension's link → consent tab;
[docs/a1-transactions.md](docs/a1-transactions.md)):

| Form | Example | Opens the app when |
|---|---|---|
| App Link | `https://app.moveo.one/extension/config/<code>?transaction_id=…` | Tapped from another app (Mail, Messages, a panel app) or the browser — **requires the `assetlinks.json` below**; otherwise the browser shows the landing page |
| Custom scheme | `moveoone://config/<code>?transaction_id=…` | Tapped anywhere (the landing page's "Open in app" button, QA); only when the app is installed. **The reliable path on GMS-less Huawei devices below Android 12**, which never auto-verify App Links |

`?transaction_id=` is the optional panel-provider id (`[A-Za-z0-9_-]{1,256}`,
anything else is dropped, never blocks activation). It is sent as
`transactionId` in the enroll body and echoed on the **lead-out** URL as
`transaction_id` — byte-identical to the extension and iOS. The app also
mints `enrollmentId` (`e_<uuid>`) once per activation, before the first
enroll call, and reuses it on consent-screen retries.

App Links need two things:

1. The manifest intent filter (`android:autoVerify="true"` for
   `app.moveo.one/extension/config/`; debug builds add `dev-app.moveo.one`
   without autoVerify). Already in place.
2. `https://app.moveo.one/.well-known/assetlinks.json` (platform team)
   listing **both** signing certificates — the Play App Signing key and the
   AppGallery distribution key (plan §h4); add the debug cert for internal
   builds. The Android counterpart of the iOS AASA; they coexist in
   `.well-known/`. **Not live yet** — as of 2026-08-28 the prod host returns
   `[]` and the dev host returns the SPA's HTML, so https links currently
   open the browser, not the app. Ready-to-deploy file, real fingerprints
   and verification steps: [docs/assetlinks-handoff.md](docs/assetlinks-handoff.md).

   ```json
   [{
     "relation": ["delegate_permission/common.handle_all_urls"],
     "target": {
       "namespace": "android_app",
       "package_name": "one.moveo.studywrapper",
       "sha256_cert_fingerprints": [
         "<PLAY APP SIGNING SHA-256>",
         "26:70:28:65:8D:82:FD:8C:85:F7:6E:F7:75:4D:AC:78:0B:4C:A6:8A:5D:0C:1E:81:DB:4B:1B:9A:12:6B:C4:BE"
       ]
     }
   }]
   ```

QA on the emulator (the scheme works before any assetlinks exists; `-n`
delivers an https link without consulting verification):

```sh
adb shell am start -a android.intent.action.VIEW \
  -d "moveoone://config/TESTCODE1234?transaction_id=tx_qa_001"
adb shell am start -a android.intent.action.VIEW -n one.moveo.studywrapper/.MainActivity \
  -d "https://app.moveo.one/extension/config/TESTCODE1234?transaction_id=tx_qa_001"
adb shell pm get-app-links one.moveo.studywrapper      # per-domain verification state (Android 12+)
adb shell pm verify-app-links --re-verify one.moveo.studywrapper
```

Oracles: `adb logcat -s moveo-backend` prints `openURL code … transactionId …`
on arrival and `lead: LEAD_OUT <url>` with the echoed id; the mock backend
logs `enrollmentId …, transactionId …` on enroll; the gear screen shows the
active study's ids.

## Study instructions on the consent screen

`study.instructions` (config-schema §2.1, optional, ≤ 2000 chars) is shown
on the consent page under "Instructions", between the replace notice and the
tracked websites — same placement as the extension's consent tab and iOS.
It is **plain text only**: `studycore/Instructions.kt` is a port of the
extension's `src/instructions.js` (blank line = paragraph break, `- `/`* `
lines = bullets, single newline = line break, control/zero-width/bidi
characters stripped, no Markdown/HTML/links ever interpreted) and
`ConsentScreen` renders every block with a plain `Text(String)` — no
annotated strings, no autolink. Absent or blank ⇒ the section is hidden.
Not consent wording, so `ConsentConstants.TEXT_VERSION` is unchanged.
`InstructionsTests` pins the extension's case table; the validator rejects
> 2000 chars (vendored fixture `invalid-instructions-too-long.json`).

## Finishing a study

Parity with the extension's and iOS's `done-finish` branches
([docs/a2-finish.md](docs/a2-finish.md)): reaching the target **completes**
the study, and the participant can finish it themselves.

- **Target reached** → lead-out opens after the usual 2 s delay (Custom
  Tab) → the study is completed (ended record written, active study
  cleared) the moment the lead-out launches; the "Study complete" screen
  shows when the participant comes back to the app (`onResume`). No
  lead-out configured ⇒ completes right at the target (browser gives way
  after the same 2 s).
- **Done** (browser) / **Finish study** (home) → confirmation ("Tracking
  stops and you'll be taken to the closing page.") → lead-out opens with the
  transaction id → complete. A lead-out already shown is not shown again.
  System back at the end of the page history still just returns to the
  home screen (tracking continues) — Android's way out without finishing.
- **Events are flushed first**: before the browser is torn down, the app
  runs `window.__moveoFlush()` (bootstrap-installed; drains the tag's
  buffer and resolves when the POSTs land, bounded by
  `FlowConstants.COMPLETION_FLUSH_TIMEOUT_SECONDS`) and waits for the
  page's `flushed` bridge reply. Debug: `completion flush → sent N timedOut
  B` on `moveo-backend`.
- Completion also clears the study website data, like leaving.

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
extras — store listing assets, privacy policy URL, Data safety answers, App
Links fingerprints — are tracked item by item in
[docs/a4-play-release.md](docs/a4-play-release.md) (and [plan §a4 and §h](docs/plan.md)).

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
