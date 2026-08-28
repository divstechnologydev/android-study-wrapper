# a4 — Google Play release checklist

Audit of 2026-08-28 against Google Play's publishing requirements. Work
the items top to bottom; tick a box only when the evidence line holds.
Items marked **client** need input from Moveo One / the platform team.
Companion docs: [plan §a4](plan.md), [assetlinks-handoff.md](assetlinks-handoff.md),
[h3-appgallery.md](h3-appgallery.md) (the AppGallery mirror of this list).

## 1. Blockers (upload or review fails)

- [x] **R1 — target API 36.** (done 2026-08-28) From 2026-08-31 new apps
  and updates must target Android 16 (API 36); `targetSdk 35` is refused
  at upload ([Play Console Help](https://support.google.com/googleplay/android-developer/answer/11926878)).
  `compileSdk`/`targetSdk` = 36 in `app/build.gradle.kts`; `aapt2 dump
  badging` on `assemblePlayRelease` shows `targetSdkVersion:'36'`,
  `./gradlew build` green. Enforced edge-to-edge broke the layout
  (status-bar clock over the wordmark, consent action bar under the
  gesture nav — Accept button unreadable): fixed with `enableEdgeToEdge`
  (light bars, dark icons) + `WindowInsets.safeDrawing` padding on the
  root in `MainActivity`; verified on the `Medium_Phone_API_36` AVD
  (consent, browser, home).
- [ ] **R2 — privacy policy (client).** `BackendConstants.PRIVACY_POLICY_URL`
  (`https://www.moveo.one/privacy`, returns 200) must explicitly cover this
  app: interaction events on study sites, pseudonymous participant id,
  transaction id from panel providers, sharing with research teams,
  retention, deletion contact. Same URL goes in the Play listing (Store
  presence → Privacy policy) and stays linked from the consent screen.
  Remove the `TODO(a4)` in `studycore/Constants.kt` once confirmed.
- [ ] **R3 — Data safety form.** Declare (matching the iOS privacy labels):
  | Category | Data | Collected | Shared | Required | Purpose |
  |---|---|---|---|---|---|
  | App activity | App interactions (taps, scroll, typing activity, dwell) | yes | yes (research team) | yes | Analytics |
  | App activity | Other actions (pages viewed on study sites) | yes | yes | yes | Analytics |
  | Device or other IDs | Participant id `p_<uuid>` (persists via backup), enrollment id, panel transaction id | yes | yes | yes | Analytics / app functionality |
  Encrypted in transit: yes. Deletion request: yes → mechanism from R6.
  No ads, no selling, no financial/health/location/contacts data. "No data
  collected" would be a false declaration.
- [ ] **R4 — store listing (client sign-off).** Assets are generated in
  [store/](store/README.md) (2026-08-28): `icon-512.png`,
  `feature-graphic-1024x500.png`, three 1080×2400 phone screenshots
  (from a debug build on the mock study — retake from release on a real
  study before production; fine for closed testing). Copy drafts are in
  the section below. Still to do in Console: app name "Moveo One User
  Research", category Tools, contact email + website, content rating
  (IARC — utility, no UGC shown to others), target audience **18+ / not
  for children**, ads: none, news: no, government: no. Tablet screenshots
  skipped (R13).
- [ ] **R5 — App access for reviewers.** The app has no manual code entry;
  a reviewer sees an empty activation screen. In *App content → App
  access* choose "all or some functionality restricted" and supply a
  working **prod** setup link in both forms plus instructions:
  `https://app.moveo.one/extension/config/<code>` and
  `moveoone://config/<code>`. Until assetlinks is live the https link
  opens the browser, so the note must point at the scheme link / the
  landing page's "Open in app" button. Keep the test study alive for the
  whole review window.

## 2. Should fix before submitting

- [ ] **R6 — data deletion path (client).** Leave/finish clears local
  data only; server-side events stay. Publish a deletion contact (email
  in the privacy policy, or a "Request data deletion" line on the study
  home) and reference it in Data safety.
- [ ] **R7 — assetlinks.json (platform).** Prod host returns `[]`. Not a
  Play gate, but needed for link-first activation. Requires the **Play
  App Signing** SHA-256, which only exists after the first Play Console
  upload (Setup → App integrity → App signing key certificate). Then
  update `assetlinks-handoff.md` and re-verify with `pm get-app-links`.
- [x] **R8 — release logging.** (verified 2026-08-28, no change) `App.kt`
  already passes `debugLog = null` unless `BuildConfig.DEBUG`; the
  `Log.i("moveo-backend")` sink exists only in debug builds and logs
  method/path/status, never bodies. Nothing else in `src/main` or
  `:studycore` calls `android.util.Log`.
- [ ] **R9 — Play App Signing.** Opt in on first upload (the local
  keystore is the *upload* key). Back up `../keys/studywrapper-release.jks`
  + password off-machine (it is also the unrecoverable AppGallery key).
- [x] **R10 — version for public release.** Not a gate — ships as
  `versionCode 1 / 0.1.0` (decision 2026-08-28); bump per the README's
  release steps on later uploads.
- [ ] **R11 — closed testing first.** Upload to a closed track, run the
  §a4.3 pilot cohort; read the pre-launch report (crashes on the
  WebView-gate path, accessibility contrast on muted text). Personal
  developer accounts additionally need 12 testers × 14 days before
  production is unlocked; organisation accounts do not.

## 3. Hygiene (not gates)

- [x] **R12 — dependency refresh.** (done 2026-08-28) AGP 8.8.2, Compose
  BOM 2025.06.01, activity-compose 1.10.1, core-ktx 1.16.0, lifecycle
  2.9.1, webkit 1.14.0 — the newest set that builds on AGP 8.x /
  compileSdk 36. The current heads (core 1.19, lifecycle 2.11, BOM
  2026.08) require **AGP 9.1 + compileSdk 37** — a Gradle 9 / Kotlin 2.2
  toolchain migration, deferred to after the first release. `./gradlew
  build` green, API 36 smoke (consent → browser → home) clean, no
  `AndroidRuntime` fatals. Remaining lint: `GradleDependency` /
  `AndroidGradlePluginVersion` only.
- [x] **R13 — orientation lock.** Skipped (not a Play gate, 2026-08-28).
  `screenOrientation="portrait"` is ignored on large screens at API 36
  (lint suppressed in `app/lint.xml`, product decision §a0.6); the
  consent card is `widthIn(max = 560.dp)` so it centres. Tablets are not
  a target form factor for v1 — accept the "not optimised for tablets"
  listing note.
- [x] **R14 — lint noise.** (done 2026-08-28) `@SuppressLint("RequiresFeature")`
  on the three gated `StudyWebView` methods; `app/lint.xml` ignores
  `ObsoleteSdkInt` on `mipmap-anydpi-v26` (false positive — AAPT rejects
  `<adaptive-icon>` without the `-v26` qualifier, verified), the
  constellation vector size/path warnings and the deliberate portrait
  lock. `lintPlayRelease` now reports only `GradleDependency` /
  `AndroidGradlePluginVersion` (→ R12).

## Drafts for Play Console (R4 / R5 — client to approve)

**Short description (≤80):**
`Take part in Moveo One research studies from your phone.`

**Full description (draft):**

> Moveo One User Research is the participant app for research studies run
> by Moveo One and its research partners. You only need it if you have been
> invited to a study.
>
> How it works: open the invitation link you received, read the consent
> page, and accept. The study then runs inside the app's built-in browser,
> only on the websites listed on the consent page. While you use those
> sites, the app records how you interact with them — taps, scrolling,
> typing activity (never the text you type), and how long you spend on
> each page — together with basic technical context (device type, screen
> size, language). Nothing is collected on any other website or outside
> the app, and no passwords, payment details or typed text are ever
> recorded.
>
> You take part under a random participant ID; the research team never
> receives your name or email. You can leave a study at any time from the
> study screen, which stops tracking and clears the study's browsing data
> from the app.
>
> Privacy policy: https://www.moveo.one/privacy

**App access → instructions for reviewers (draft):**

> This app is only usable through a study invitation link — there is no
> sign-in and no manual code entry. To review:
> 1. Install the app, then open this link on the device:
>    `moveoone://config/<PROD_TEST_CODE>` (paste it into Chrome's address
>    bar, or open `https://app.moveo.one/extension/config/<PROD_TEST_CODE>`
>    and tap "Open in app" on the page).
> 2. The consent page for the test study appears; tap "Accept & continue".
> 3. Tap "Open study browser" to browse the study website inside the app.
> 4. "Leave study" (menu on the study screen) ends participation and
>    clears data.
> The test study stays active for the whole review period. No credentials
> are needed.

Fill in `<PROD_TEST_CODE>` with a live prod code before submitting and
keep the study alive until approval.

## Already verified compliant (2026-08-28)

INTERNET is the only permission; no camera/mic/location (WebView
`onPermissionRequest` denies); `<queries>` declared for browser
resolution; no cleartext, no `debuggable`, debug source set absent from
release; R8 on; no native libraries (16 KB page-size rule n/a); no
accounts (account-deletion policy n/a); consent with versioned wording
shown before any tracking; adaptive + monochrome launcher icon;
`allowBackup` with explicit rules; `bundlePlayRelease` produces a signed
AAB with the pinned upload cert (`26:70:…:C4:BE`); lint exits 0.
