# Moveo One Android Study Wrapper

Android participant app for Moveo One research studies — activation, consent,
and a locked-down in-app browser (WebView) that injects the Moveo analytics
tag into the study's websites. The Android sibling of the iOS study wrapper.

**Status: in progress.** Phases a0–a3.2 (skeleton, `:studycore` port,
activation/consent/browser, parity capture, feature matrix), h1–h2 (store
flavors, signing), a1.4 (link-first activation with transaction id +
client enrollment id), a2.8 (finishing a study), a2.9 (session link) and
a2.10 (Content-Security-Policy relay) are done; open items
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
set (event spy, API-base/ingest overrides, `MOVEO_*` intent extras,
cleartext-to-localhost network config, relaxed tag-endpoint guard for dev
configs). It has **no on-screen entry point** — every hook is driven by
intent extras, so no QA affordance can show up in a screenshot or under a
reviewer's thumb (2026-10-01, with iOS; `DebugSettingsScreen` is still in the
debug source set, unreferenced, if you want the panel back). **Release** contains none of that code —
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

# Launch pointed at the DEV environment (the override persists in prefs,
# so later launches need the extra only to change it)
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

The setup link is the primary way into a study. The activation card also
has a manual code field as a fallback — it accepts a bare code or a pasted
setup link; only the pasted link keeps the panel provider's transaction id
(a typed code cannot carry it). The reserved code `MOVEODEMO` is intercepted
before any network call and runs the demo (see below). Both link
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
logs `enrollmentId …, transactionId …` on enroll.

## Demo study (no invitation needed)

Anyone — a prospective customer, a store reviewer, you — can run the whole
participant flow without a study code: **Try a demo study** on the activation
screen (or the reserved code `MOVEODEMO`, or the study menu's **How it
works** → *Try the demo study*). Pick any website; the demo runs consent →
instructions → study browser → completion against it.

The demo **records nothing**, and that is a property of the build rather than
of the copy — `AppViewModel` intercepts it at three points: activation never
fetches a config, accepting never enrolls, and no tag is ever injected (nor
is the demo re-validated, which would 404 and revoke it). `AppViewModelDemoTests`
guards each one. Details: [plan §a5](docs/plan.md).

```sh
# QA: run the demo on a given site with no UI interaction (debug builds)
adb shell am start -n one.moveo.studywrapper/.MainActivity \
  -e MOVEO_AUTO_CODE MOVEODEMO -e MOVEO_DEMO_SITE example.com/pricing
```

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

## Content-Security-Policy relay

Parity with the extension's `sec-policy-overcome` branch and the iOS
`csp-fixes` branch ([docs/a6-csp-relay.md](docs/a6-csp-relay.md)). The
injected tag runs in the study page's own JavaScript world, so its requests
are governed by the host site's Content-Security-Policy — sites with a
strict `connect-src` (Tesco, most banks) refuse `api.moveo.one` from the
page and no event ever leaves. The vendored tag therefore has a `transport`
init option, and the bootstrap routes every tag request through the native
side:

```
tag → options.transport (moveo-bootstrap.android.js)
    → window.moveoFetch.postMessage({ id, kind, body })   (origin-scoped WebMessageListener)
    → StudyWebViewController → AppViewModel.relayTagRequest → studycore/TagRelay.kt → OkHttp
    … and the reply { id, ok, status, body } comes back over the frame's
      JavaScriptReplyProxy; the bootstrap matches it to the pending promise by id.
```

Same trust model as the extension's `src/tag-relay.js` and iOS's
`TagRelay.swift`: only a request `kind` (`events` | `ip`) and an opaque body
cross the page boundary. The URL and the `Authorization` token are derived
natively from the active study; the posting frame's origin must match the
study origins (and the `moveoFetch` object only exists on study origins to
begin with — same `allowedOriginRules` as the bridge); event bodies must be
a `{ "events": [...] }` document ≤ 1 MiB; responses are capped at 64 KiB;
15 s native timeout, 20 s page-side timeout. Anything else replies
`{ ok: false, status: 0 }`, which the tag sees as a failed fetch. The relay
message is posted synchronously, so the tag's `pagehide`/`beforeunload`
drains survive page teardown, and the native request runs in the app scope
so it outlives the WebView. The demo never relays (no tag, and a forged
message against the demo is refused). `TagRelayTests` ports the extension's
builder cases, runs `perform` against a MockWebServer and pins the seams (no
page-world fetch of the ingestion URL left in the vendored tag; bootstrap
and native side agree on the object name, the kinds and the id handshake);
`AppViewModelRelayTests` covers the model's refusals and the DEBUG knock-on
effects. `scripts/relay-smoke.sh` runs the real tag + bootstrap in a real
WebView under `connect-src 'none'` (instrumented `RelaySmokeTest`) and
checks that events still arrive through the relay — re-run it after every
re-vendor:

```sh
scripts/relay-smoke.sh        # needs a booted AVD / device over adb
```

Knock-on effects in debug builds: the ingest redirect and the event spy
apply to relayed requests natively (`AppViewModel.effectiveIngestRedirect`,
`EventSpy.observeRelayedPost`; the page-world `fetch` wrappers only see the
fallback path now), and the IP lookup follows the redirected ingestion host
like the extension's dev build.

The vendored tag is the extension build (`libVersion` `1.0.22-ext`): on top
of the relay seam it registers its click listener in the capture phase and
falls back to `aria-label`/`title`/image `alt` for icon-only controls, and
there is no longer a cap of 20 on `semanticGroupRules` /
`additionalTrackedElements` (validator and tag both; per-entry limits stay).
`docs/examples/tesco-sg-rules.json` is the extension's CSP-strict example
config (21 force-tracked entries — the validator test suite uses it as the
cap-removal fixture).

**`flow.prepositionPath`** (wrapper-first; now also in the extension's
consent redirect, extension commit `a099d26` "preposition fix"): the
validator and `StartUrl.build` (`studycore/StartUrl.kt`) are ports of the
extension's `validate-config.js` / `src/start-url.js`. A path on the first
origin, never a URL — max 500 chars, no scheme, no `//host`, no `\`, no
whitespace/control characters (rejected as `invalid_config`; vendored
fixtures `invalid-preposition-path-url.json` /
`valid-preposition-path-empty.json`). Null/blank ⇒ the origin root. At use
time the start URL can only ever be https on the first origin: a missing
leading `/` is tolerated, `..` cannot escape, and anything off-origin or
unparsable falls back to the root. `StudyConfig.startUrl` (what the study
browser opens on) is that builder.

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
  buffer and resolves when the relayed POSTs land, bounded by
  `FlowConstants.COMPLETION_FLUSH_TIMEOUT_SECONDS`) and waits for the
  page's `flushed` bridge reply. Debug: `completion flush → sent N timedOut
  B` on `moveo-backend`.
- Completion also clears the study website data, like leaving.
- **Session link** (extension parity, `session id passed on study end`;
  [docs/a5-session-link.md](docs/a5-session-link.md)):
  the injected bootstrap reports the tag's tracking-session id over the
  bridge (`{ type: "session", sessionId }`, latest wins, validated +
  stored on the active study), and completion POSTs
  `{ enrollmentId, participantId, sessionId }` to `/{code}/sessions` —
  best effort (one retry for network/5xx/429, never blocks completion; the
  analytics stream stays the ground truth). No session observed ⇒ no call.

### Release build (prod)

```sh
./gradlew :app:bundlePlayRelease      # → .aab for Play Console upload
./gradlew :app:assembleHuaweiRelease  # → signed .apk for AppGallery Connect
```

Release is hard-pinned to production (`pigeon.moveo.one` config API, the
baked tag ingest endpoint): intent extras are ignored, there is no settings
screen, no event spy, no ingest redirect, and no cleartext config.

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
(uninstall any debug build first — different signature), `MOVEO_API_BASE`
is ignored, a bogus code round-trips to prod ("Code not recognized").

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
(commit hash + sha256 is the provenance), re-run the fixture suite, the
relay smoke test (`scripts/relay-smoke.sh`) and the a3.1 parity diff, then
release. Never edit `assets/moveo-one.js`.

**Exit criteria (a4 + h):** production release on Google Play and Huawei
AppGallery; pilot study data verified comparable with desktop/iOS cohorts.
