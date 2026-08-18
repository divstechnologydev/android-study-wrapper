# Moveo One Android Study Wrapper — Implementation Plan

**Status:** planning — no implementation yet. This folder is the plan and the
context package for the Android participant app. The phase-by-phase plan
lives in [plan.md](plan.md); background and rationale in
[context.md](context.md).

## What we are building (one paragraph)

An Android app that lets research-study participants take part in Moveo One
studies **on their phones**, where the Chrome extension cannot run. The app
is *not a browser product*: it is three screens — activation, consent, and a
single WebView locked to the study's websites — with the Moveo One analytics
tag injected into every study page at document start. It reuses the
extension's backend (config-by-code, enrollment/billing, kill switch) and the
exact same tag file, so desktop-extension, iOS-app, and Android-app data are
directly comparable. It is the Android sibling of the already-implemented iOS
app and deliberately mirrors it screen-for-screen and rule-for-rule.

## Why it exists

The Chrome extension covers desktop only: Chrome on Android has no extension
support, and Google's extension work explicitly excludes phones. Since
neither the study owner nor the participant owns the target websites
(third-party ecommerce sites), embedding the tag in the site is impossible —
the only place injection is possible on mobile is an in-app browser view we
control. This is the industry-standard pattern (Loop11's mobile solution is a
participant app), and the pattern is already proven in production by the iOS
sibling. Full rationale and rejected alternatives: [context.md](context.md).

## Sibling repos (read-only dependencies)

| Repo | Path (relative to this repo) | What we take from it |
|---|---|---|
| Chrome extension | `../../chrome-extension/moveo_one_chrome_extension/` | The **normative config schema** (`docs/config-schema.md`), backend contract (`docs/phase-1-backend.md`), the tag file (`extension/moveo-one.js` — vendored byte-identical), validator rules (`extension/src/validate-config.js`), origin matching (`extension/src/origins.js`), consent copy (`extension/consent/`), mock backend (`scripts/mock-backend.mjs`), schema fixtures (`docs/schema/fixtures/`) |
| iOS app | `../../ios-study-wrapper/ios-study-wrapper/` | The **reference implementation**: `StudyKit/` is ported file-for-file to `:studycore` (port map in [plan.md](plan.md)); the app layer (state machine, browser policy, bootstrap JS, lead flow, kill switch) is translated screen-for-screen; screen copy and brand tokens are reused verbatim; `Scripts/diff-events.mjs` is the parity-diff tool |

The extension repo's `docs/config-schema.md` stays the **single source of
truth** for the config contract. Where iOS code and extension docs disagree,
the extension docs win — and the discrepancy is a bug to report, not a
behavior to copy.

## Architecture at a glance

```
┌────────────────┐   creates study    ┌─────────────────┐
│ Client (study  │ ─────────────────► │ Moveo One       │
│ owner)         │   gets link        │ Platform (BE+FE)│
└────────────────┘                    └────────┬────────┘
        │ shares link                          │ stores config behind code
        ▼                                      │
┌────────────────┐  taps link (phone) ┌────────▼────────┐
│ Participant    │ ─────────────────► │ App Link        │
│ (test user)    │                    │ app.moveo.one/  │
└──────┬─────────┘                    │ extension/      │
       │ app installed? opens app     │  config/<code>  │
       ▼          else: landing page  └────────┬────────┘
┌────────────────┐  with Play badge + code     │
│ Android app    │  GET config by code         │
│  - activation  │ ◄───────────────────────────┘
│  - consent     │  POST enroll (billing)
│  - WebView     │ ─────────────────► Backend (same endpoints as extension)
│    + injected  │
│    moveo tag   │  events → tracking.apiUrl (same as desktop tag)
└────────────────┘
```

## Phases

Detailed sections and milestones: [plan.md](plan.md). Numbering is a0–a4
(Android), mirroring the iOS phases i0–i4 one-to-one:

| Phase | Summary | Depends on |
|-------|---------|-----------|
| a0 | Contract reuse + Android decisions: enroll client marker, WebView feature gate, injection/bridge strategy, storage & backup, consent text version | — |
| a1 | App Links (`assetlinks.json`), custom scheme fallback, Play badge on the mobile landing page | a0 |
| a2 | App skeleton (activation/consent/browser), `:studycore` port, origin-scoped injection, JS↔native bridge, lifecycle & kill switch | a0 (can mock a1) |
| a3 | Event parity vs desktop via mock backend, config-feature verification, platform metadata | a2 |
| a4 | Site-screening checklist, Data safety form, closed-testing pilot, Play release | a3 |

## Critical path

Client marker sign-off (a0.2) → `:studycore` port green on the fixture suite
(a2.1) → browser + injection + bridge (a2.3–a2.4) → lifecycle/kill switch
(a2.6) → mock-backend parity run (a3.1) → closed-testing pilot (a4.3). App
Links (a1) and shell polish can trail without blocking the demo-able core —
the custom scheme and typed codes cover activation until then.

## Key constraints (know these before designing anything)

- **`addDocumentStartJavaScript` is origin-scoped natively** — the defining
  iOS design problem (WKUserScript's missing URL filter) does not exist here.
  The flip side: it's an AndroidX WebKit feature backed by the WebView
  *component*, not the OS version, so it must be runtime-checked
  (`WebViewFeature.DOCUMENT_START_SCRIPT`); unsupported devices get an
  "update Android System WebView" gate, never a racy fallback injection
  (decision §a0.3).
- **Google/Facebook OAuth block WebView logins** (`disallowed_useragent`) on
  Android exactly as on iOS. Email/password logins are unaffected. Study
  sites must be screened for social-login-only walls before launch (§a4.1).
- **Foreground is the re-validation trigger.** `ProcessLifecycleOwner` START
  is reliable; WorkManager periodic refresh is best-effort bonus only.
  Kill-switch latency is "next time the participant opens the app" — same
  accepted posture as iOS and the extension (whose worst case is one 4-hour
  alarm interval).
- **Play review will scrutinize a "records browsing" app.** Mitigations are
  the same ones that pass on iOS: consent-first flow, per-study origin
  scoping, study-code gating, honest Data safety declarations. Loop11's
  participant app passes both stores — precedent exists.
- **Parity is the product promise.** The tag is vendored byte-identical, the
  validator must give the same verdict on every extension fixture, and the
  event stream must diff clean against a desktop capture (a3). Anything that
  would make Android data drift from desktop/iOS data is a bug by definition.
- **Prerequisites:** Google Play Console account ($25 one-time), Android
  Studio. Target: Kotlin + Jetpack Compose, minSdk 26, single activity,
  AndroidX WebKit for the WebView APIs.

## Non-goals (v1)

- Not a general-purpose browser: no tabs, omnibox, bookmarks, history, sync.
- No screen/camera/audio recording (Loop11 does this; Moveo's value is DOM
  interaction analytics — the injected tag captures identical data to desktop
  without any recording permissions).
- One active study per install (mirrors extension + iOS v1). Loading a new
  code replaces the old study after user confirmation.
- No tablets/foldables-specific layouts, no Wear/TV/Auto form factors —
  phone-first, portrait-locked v1 (same scope as the iPhone-only iOS v1).
- No feature drift from iOS: if a behavior differs from the iOS app, it is
  either an Android platform necessity recorded in [plan.md](plan.md) or a
  bug.
