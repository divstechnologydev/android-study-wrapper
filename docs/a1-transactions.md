# a1.4 — Link-first activation, transaction id, client enrollment id

Port of the iOS `transactions-track` branch (commit `f88ae7d transaction deep
links for activating`) and the extension `enrollment-id` branch (`9861758
enrolment id generated and passed`, `b75412c transaction id fetched and
passed`) to Android — Play and Huawei flavors alike. Written 2026-08-27 as the
review plan; implementation follows step by step (each step ends testable).

**Status:** Steps 1–3 done 2026-08-27 (`./gradlew build` green: 84 studycore
+ 12 app JVM tests; emulator journey vs mock verified: scheme link →
sheet → consent → enroll body with both ids → lead-in without / lead-out
with `transaction_id`; same link again idempotent). Step 4 device pass done
2026-08-27 on the Galaxy A52s (Android 14) against dev — scheme link
activation reported working by the user. Remaining: the platform-team
items in §3.4 (assetlinks.json, landing page, backend admission of the new
fields for `client: "android"`) and the §3.5 paperwork note.

## 1. What changes (behavior contract — byte-identical to iOS/extension)

| # | Rule | Source of truth |
|---|---|---|
| 1 | **The setup link is the only UI entry.** The manual code field + Activate button leave the activation card (product decision 2026-08-27: a typed code cannot carry the panel provider's transaction id). The model keeps `codeInput`, so deep links and the DEBUG `MOVEO_AUTO_CODE` hook still work and re-adding the field later is UI-only. | iOS `ActivationView.swift` |
| 2 | Two link shapes, both may carry `?transaction_id=…`: `https://app.moveo.one/extension/config/<code>` (App Link) and `moveoone://config/<code>` (custom scheme). Other query params are ignored. | iOS `SetupLink.swift`, ext `content/setup-link.js` |
| 3 | `transaction_id` is untrusted URL input: valid iff `^[A-Za-z0-9_-]{1,256}$` after percent-decoding; **first occurrence wins** (`URLSearchParams.get`); empty / missing / wrong name / invalid ⇒ *no* transaction id — it never blocks activation. | ext `setup-link.js` |
| 4 | **`enrollmentId` = `e_<lowercase uuid>`**, minted once per pending activation *before* the first enroll attempt; consent-screen retries (Accept after a network error) resend the **same** id. Decline/cancel discards it with the pending activation. | ext `enrollment.js`, `config-service.js` |
| 5 | Enroll body gains `enrollmentId` (always) and `transactionId` (**only when present — key absent, not null**, so plain links / typed codes leave the request unchanged). Existing markers (`client: "android"`, `extensionVersion: "android/<v>"`, consent fields) untouched. | ext `enrollment.js`, iOS `ConfigService.swift` |
| 6 | `ActiveStudy` persists `enrollmentId` and `transactionId` (both nullable — records written by earlier builds keep decoding; the participant keeps their study across the update). | iOS `StudyState.swift`, ext `storage.js` |
| 7 | **Lead-out URL — and only the lead-out —** echoes `transaction_id=<id>` (same param name as inbound) so the panel provider can credit the completion. Lead-in never gets it. `participantId` opt-in behavior unchanged. Existing params of the same name are replaced (`searchParams.set` semantics). Nothing to append ⇒ URL returned **unchanged, not re-encoded**. | ext `target-service.buildLeadUrl`, iOS `LeadURL.swift` |
| 8 | Link arrival state machine (`handleOpenUrl`): (a) same code already active ⇒ idempotent — no re-fetch, no second enroll, clear any pending, close the browser, land on home (the extension's `alreadyActive`); (b) same code already mid-activation (summary sheet or consent) ⇒ update the pending record in place, a transaction id on the fresh link wins over a stale/absent one; (c) otherwise stash the transaction id **keyed by code** (a lingering id can never attach to a different code typed afterwards), set `codeInput`, close the browser (the confirmation sheet is owned by the activation screen), activate. Cancel / decline / leave clear the stash. | iOS `AppModel.swift`, ext `config-service.activate` |
| 9 | `codeInput` accepts a bare code **or** a pasted full setup link (the only manual path that keeps the transaction id — used by the DEBUG hook; UI has no field). | iOS `AppModel.resolveInput` |
| 10 | DEBUG only: an `openURL code … transactionId … active … pending … phase …` log line (codes/ids only — never the token or config, security §6); gear screen shows the active study's Enrollment ID / Transaction ID. Release logs nothing. | iOS `AppModel.swift`, `DebugSettingsView.swift` |

## 2. Port map (iOS `transactions-track` → Android)

| iOS / extension | Android | Notes |
|---|---|---|
| `StudyKit/SetupLink.swift` (`parse`, `Parsed`, `transactionIdParam`) + `TransactionId` | `studycore/SetupLink.kt` (+ `TransactionId` object) | `java.net.URI` path parsing kept; the query is parsed **manually from the raw string** (split at `?`/`#`, first `transaction_id`, `URLDecoder`) so a garbage query can never make `URI(...)` throw and swallow the code — strictly more robust than `URL(string:)`, same results on every iOS test vector. |
| `StudyKit/Enrollment.swift` | `studycore/Enrollment.kt` | `"e_" + UUID.randomUUID().toString().lowercase()` (java.util.UUID v4 = SecureRandom, same as `crypto.randomUUID()`). |
| `StudyKit/LeadURL.swift` | `studycore/LeadUrl.kt` | Moves the private `AppViewModel.leadUrl` into :studycore (testable), OkHttp `HttpUrl` (already an allowed studycore dep). `participantId: () -> String` lambda = the iOS `@autoclosure` (no prefs read unless appended). |
| `StudyKit/StudyState.swift` (`ActiveStudy.enrollmentId/transactionId`) | `studycore/StudyState.kt` | Two nullable `@Serializable` fields with defaults ⇒ legacy JSON decodes. |
| `StudyKit/ConfigService.swift` (`enroll(enrollmentId:transactionId:)`) | `studycore/ConfigService.kt` | `enrollmentId: String` required param, `transactionId: String? = null` — key emitted only when non-null. |
| `App/AppModel.swift` (`PendingActivation.enrollmentId/transactionId`, `LinkTransactionId`, `handleOpenURL`, `resolveInput`, `acceptConsent`, cancel/decline/leave) | `app/AppViewModel.kt` | Straight port; `PendingActivation` is a data class (`copy` for the in-place update); `browserPresented=false` on link arrival. |
| `App/AppModel+Browser.swift` (`presentDueLeadOut` passes `study.transactionId`) | `app/AppViewModel.kt` | Lead-in call site unchanged (no tx). |
| `App/Screens/ActivationView.swift` (link-only card) | `app/ui/ActivationScreen.kt` | Same copy: bold "setup link" in the explainer, "Loading your study…" progress row while fetching, "Don't have a link? Ask the study team for your invitation." otherwise. Text field, grouped-code hint and Activate button removed. |
| `App/Screens/DebugSettingsView.swift` (ids section; debug default = dev) | `app/src/debug/DebugSettingsScreen.kt` (+ `AppViewModel.apiBase` if Q2 = yes) | Debug-source-set only. |
| `App/Entitlements/{Debug,Release}.entitlements` (`applinks:`) | `app/src/main/AndroidManifest.xml` intent filters + `app/src/debug/AndroidManifest.xml` overlay | See §3 — the Android-specific part. |
| `MOVEO_AUTO_URL` env hook | none needed | `adb shell am start -a android.intent.action.VIEW -n one.moveo.studywrapper/.MainActivity -d "<url>"` delivers any URL to the activity **without** App Link verification — the intent *is* the hook. |
| iOS tests: `StudyStoreAndLinkTests` (+9), `LeadURLAndEnrollmentTests` (new), `ConfigServiceTests` (+2) | same classes under `studycore/src/test` + new `app/src/test/…/AppViewModelLinkTests.kt` (Q3) | Every iOS vector ported 1:1 (§5). |

## 3. Android-specific design ("suited for Android and Huawei")

### 3.1 Intent filters (`MainActivity`, already `singleTask` + `exported`)

```xml
<!-- App Link (a1.1): opens the app directly once assetlinks.json verifies. -->
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="app.moveo.one"
          android:pathPrefix="/extension/config/" />
</intent-filter>
<!-- Custom scheme (a1.2): landing page "Open in app", QA, mail clients. -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="moveoone" android:host="config" />
</intent-filter>
```

- Two **separate** filters: `autoVerify` must never sit on the scheme filter.
- Debug overlay (`app/src/debug/AndroidManifest.xml`) adds a third filter for
  `https://dev-app.moveo.one/extension/config/` **without** `autoVerify` (the
  iOS Debug entitlement's `dev-app.moveo.one?mode=developer` analogue). On
  Android 6–11 verification is all-or-nothing across every `autoVerify`
  host, so an unverifiable dev host must not share the prod host's
  verified filter. Release manifests carry the prod host only — same posture
  as iOS Release.
- Both flavors get identical filters; nothing store-specific (the §h1
  invariant "flavors differ only in `StoreSupport`" holds).

### 3.2 Intent delivery in `MainActivity`

- `onCreate`: handle the intent **only when `savedInstanceState == null`** —
  a recreated activity must not re-activate the link.
- `onNewIntent`: `setIntent(intent)` then handle (singleTask delivers a tap
  from Mail/Chrome here while the app is alive, including with the study
  browser on screen — the model closes it, §1 rule 8).
- Skip intents carrying `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` (relaunch from
  Recents re-delivers the original link intent; without this a declined
  activation would pop the consent screen again). Applies to the debug
  `MOVEO_*` extras too, which is the correct behavior for those as well.
- Everything else in the activity is unchanged; `RootScreen` needs no
  change — a link-triggered `Phase` already routes to
  `ActivationScreen` over Home (the "deep-link replacement" case its
  comment describes).

### 3.3 Verification & fallbacks (what works when)

| Path | Play devices | Huawei / GMS-less |
|---|---|---|
| `https://app.moveo.one/extension/config/…` tap | Opens the app once `https://app.moveo.one/.well-known/assetlinks.json` lists the app (platform team; **§h4: both fingerprints** — Play App Signing cert + the h2 distribution cert, plus the debug cert for internal builds). Until then: opens the browser → landing page. Android 12+: the user can also flip "Open supported links" in app settings. | Android 12+ (HarmonyOS 4-era) verifies via AOSP's `DomainVerificationService` like any device. Below 12, GMS-less devices never auto-verify → browser → landing page. |
| Landing page "Open in the Moveo One app" (`moveoone://config/<code>?transaction_id=…`) | Always works when installed. | **The reliable Huawei path.** |
| QA | `adb shell am start -a android.intent.action.VIEW -d "<either link>" [-n one.moveo.studywrapper/.MainActivity]` — with `-n` no verification is consulted. `adb shell pm get-app-links one.moveo.studywrapper` shows per-domain state on 12+. | Same commands. |

No HMS "App Linking" SDK: the app's zero-GMS/zero-HMS dependency posture
(§h1) stays; the scheme link + landing page already cover GMS-less devices.

### 3.4 Platform-team dependencies (this app only consumes them)

1. `assetlinks.json` on `app.moveo.one` (and `dev-app.moveo.one` for debug
   builds) — the Android counterpart of the AASA that iOS is waiting on.
2. Landing page: Android/Huawei user agents get the Play / AppGallery badge
   and the `moveoone://` "Open in app" button that **forwards
   `transaction_id` verbatim** (the extension repo's mock `mock-backend.mjs`
   working tree already demonstrates this — our `scripts/mock-backend.sh`
   runs that file unchanged, so local QA gets it for free).
3. Backend: accepts `enrollmentId` / `transactionId` from `client: "android"`
   (same shape iOS and the extension send; the mock already validates and
   logs both).

### 3.5 Privacy / store paperwork (doc item, no code)

`transactionId` is a panel-provider identifier passed through to the Moveo
backend. Add it to the a4 Play Data safety answers and the h3 AppGallery
privacy declaration alongside `participantId` ("User IDs", collected, not
shared, required for study functionality). Not part of this change's code.

## 4. Steps (each testable; confirm before the next — [[workflow-step-by-step]])

**Step 1 — `:studycore` (pure JVM, no UI).** `TransactionId`,
`SetupLink.parse`/`Parsed`, `Enrollment.generateId`, `LeadUrl.build`,
`ActiveStudy` fields, `ConfigService.enroll(enrollmentId, transactionId)`.
Port all iOS tests (§5). **Test:** `./gradlew :studycore:test` green (63 → ~80
tests).

**Step 2 — `:app` model + entry.** `AppViewModel` port (rules 4, 8, 9, 10),
`presentDueLeadOut` passes the transaction id, `MainActivity` intent rules
(§3.2), manifest filters + debug overlay (§3.1). New `app/src/test` JVM
suite for the link state machine (Q3). **Test:** `./gradlew build` green;
emulator: `adb shell am start -a android.intent.action.VIEW -d
"moveoone://config/TESTCODE1234?transaction_id=tx_qa_001"` against the mock
→ consent (the summary sheet in between was removed 2026-08-28, with iOS —
the consent page carries the study name, origins and replace warning) →
mock log shows `enrollmentId e_…, transactionId
tx_qa_001`; prefs oracle (`run-as … cat shared_prefs/studywrapper.xml`) shows
both on `activeStudy`; target page → `lead: LEAD_OUT …&transaction_id=tx_qa_001`
in logcat; re-tapping the same link → straight to home, no second POST.

**Step 3 — UI + debug + docs.** Link-only `ActivationScreen` card, gear
screen ids section (+ debug default → dev if Q2), README "Setup links"
section (Android version of the iOS one: table, adb QA lines, assetlinks
snippet), `docs/plan.md` §a1/§h4 status. **Test:** screenshots of the new
card (idle + loading), release APK still free of debug strings, `./gradlew
build`.

**Step 4 — Device pass.** Same journey on the Samsung A52s (dev backend):
link from a real mail/Chrome tap (scheme link, since assetlinks is not live),
enroll, target, lead-out URL carries the id. Then hand-off list for the
platform team (§3.4).

## 5. Test matrix (ported 1:1 from iOS, plus Android-only rows)

| Class | Cases |
|---|---|
| `SetupLinkTests` | tx on both link shapes; other params ignored; no tx ⇒ null (absent, empty value, no value, wrong name `transactionId`); invalid degrades to null but code parses (`bad%20id!`, `a.b`, `caf%C3%A9`, 257 chars; 256 ok); percent-encoded id decoded before validation; first occurrence wins; invalid code ⇒ null even with tx; **Android-only:** query that `java.net.URI` rejects still yields the code. |
| `TransactionIdTests` | `normalize` vectors (valid charset, nil/empty/space/`=`/257/256). |
| `LeadUrlTests` | unchanged when nothing to append (not re-encoded, participant id not read); participant id only on opt-in; tx appended under the inbound name; both; existing params replaced not duplicated; malformed raw ⇒ null. |
| `EnrollmentIdTests` | `e_` prefix, lowercase UUID, unique per call. |
| `StudyStoreTests` | round-trips both ids; record written before the ids existed decodes with nulls (`targetFired` preserved). |
| `ConfigServiceTests` | enroll carries `enrollmentId`; `transactionId` key **absent** when null; present when given; existing marker/409/error-mapping tests updated for the new required param. |
| `AppViewModelLinkTests` (app JVM, MockWebServer, Q3) | link ⇒ pending carries tx + fresh enrollment id; same code active ⇒ no request, browser closed, phase idle; same code pending ⇒ tx updated in place, fresh wins; typed different code after a link ⇒ no tx leak; cancel/decline clears stash; enroll network failure then retry ⇒ same `enrollmentId` on both POST bodies; enrolled study persists ids; lead-out URL has `transaction_id`, lead-in does not. |

## 6. Decisions to confirm (defaults in bold)

- **Q1 — Debug default API base.** iOS on this branch flipped Debug to
  default to **dev**; Android debug defaults to prod with a "Use dev"
  button. **Decided 2026-08-27: keep prod** — not ported (the override /
  `MOVEO_API_BASE` extra already cover dev).
- **Q2 — App-level JVM tests.** `AppViewModel` is constructor-testable but
  `:app` has no unit-test source set yet. **Default: add it** (junit +
  coroutines-test + mockwebserver, ~8 tests in §5) — the link state machine
  is the riskiest part of this change and iOS has no equivalent coverage.
- **Q3 — Android 12+ "Open supported links" shortcut** on the activation
  card (`Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS`). **Default: no** —
  keeps the iOS copy 1:1; the scheme link covers unverified devices.
- **Q4 — Branch.** **Default: work on a new `transactions-track` branch**
  (same name as iOS); no commits unless you ask.

## 7. Out of scope

Landing page and `assetlinks.json` (platform repo), backend admission of the
new fields, HMS App Linking, any change to the tag/bootstrap, versionCode
bump (ships with the next release).
