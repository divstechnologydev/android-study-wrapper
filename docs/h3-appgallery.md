# h3 — AppGallery Connect submission package

Everything needed to put the `huawei` flavor on AppGallery (docs/plan.md
§h3). Code-side work is done (§h1–h2); this file is the operational
checklist. Items marked **[user]** need account access or a business
decision; the rest is ready.

## 1. Account registration **[user — start first, longest pole]**

1. Register a HUAWEI ID and an **enterprise** developer account at
   developer.huawei.com (individual accounts cannot list to all regions and
   the app ships under the Moveo One name).
2. Identity verification needs either the company's **DUNS number** or the
   **business registry code + a copy of the registry license**. Verification
   itself takes 1–2 working days; obtaining a *new* DUNS number can take
   weeks — if Moveo One doesn't have one, the registry-code path is faster.
3. No fee for the developer account (unlike Play's $25).

## 2. App creation in AppGallery Connect **[user]**

- **Package name:** `one.moveo.studywrapper` (must match exactly; cannot be
  changed later).
- **App name / category / regions:** mirror the Play listing decided in
  a4.2 (research-participant app framing). Suggest category *Education* or
  *Tools*; pick once with the client and keep both stores identical.
- **Signature:** AppGallery pins the upload certificate on first submit —
  it must be the h2 keystore (`../keys/studywrapper-release.jks`). SHA-256
  `26:70:...:C4:BE` (full value in plan.md §h2).

## 3. Build artifact

```sh
./gradlew :app:assembleHuaweiRelease
# → app/build/outputs/apk/huawei/release/app-huawei-release.apk (signed)
```

AppGallery accepts both APK and AAB; the signed APK is the simple path and
right for this app (1.7 MB, no dynamic features). Pre-upload sanity (same
as the §h1/§a4.2 audits):

```sh
# QA surface absent + no rival-store links
unzip -p app-huawei-release.apk "classes*.dex" | grep -c "play.google.com"   # must be 0
# signature is the pinned cert
apksigner verify --print-certs app-huawei-release.apk
```

## 4. Privacy declaration (mirror of the Play Data safety form — one story)

What the app actually does (sources: §a0.4 participant identity, §a2 consent
flow, the vendored tag):

| Question | Answer |
|---|---|
| Collects personal data? | Yes — pseudonymous participant ID (`p_<uuid>`, generated on-device, no account/sign-in) and app-interaction/behavioral events (page views, taps, viewport) **only inside the study browser on study websites, only after explicit consent** |
| Purpose | Academic/UX research the participant enrolled in |
| Shared with third parties? | No — data goes only to Moveo One's ingest endpoint (`api.moveo.one`) over TLS |
| Ads / advertising ID / tracking SDKs? | None (only dependency talking to the network is OkHttp) |
| Location, contacts, photos, device IDs? | Not collected; the only permission is `INTERNET` |
| Consent & withdrawal | Consent screen before any storage or network call; decline = nothing stored/sent; "Leave study" wipes local state + WebView data; study kill switch ends collection server-side |
| Privacy policy URL | **[user]** — same URL as the Play/App Store listings |

Because the only permission is `INTERNET`, no sensitive-permission usage
descriptions are required.

## 5. Store listing assets **[user + one capture session]**

- Icon: exists (adaptive `|M>` mark, iOS AppIcon parity).
- Screenshots: minimum 3 portrait phone shots. Capture from a
  **huaweiRelease** install (no gear icon): activation screen, consent
  screen, study home, study browser on a demo study. Reuse whatever the
  Play a4.2 listing settles on so the two stores tell one story.
- Description: state plainly that this is a research-participant app —
  usable only with a study invitation code; no general browsing utility.

## 6. Review notes (paste into "App information for review")

> This app is for enrolled participants of Moveo One research studies. It
> requires a study setup code from an invitation. Test code: **[user:
> working prod test code, same one prepared for Play review §a4.2]**.
> Enter the code on the first screen → review the consent text → Accept →
> "Open study browser". The app's browser only opens the study's websites.
> The app works fully without Google Mobile Services and contains no HMS
> SDK (no push, no ads, no third-party analytics).

Review typically takes 1–3 business days.

## 7. Pre-submission device test (Cloud Debugging) **[user + assisted]**

The Pixel AVD cannot simulate a GMS-less device. In AppGallery Connect →
Quality → **Cloud Debugging**, rent a real Huawei device (pick one without
GMS, e.g. a P40/nova series on EMUI 11+) and verify:

1. Install `app-huawei-release.apk`, cold start → activation screen, no
   gear icon.
2. Enroll with a dev/test code end-to-end (consent → home → browser;
   config GET + enroll POST succeed).
3. Off-origin link tap → opens in Huawei Browser (Custom Tabs provider or
   the plain-intent fallback in `LeadSurveyLauncher`).
4. If the device's WebView is current, the update gate won't show — its
   huawei wording is covered by the §h1 dex audit instead.

## 8. After approval

- h4 (with a1): add this cert's SHA-256 to `assetlinks.json`; AppGallery
  installs on Android <12 without GMS rely on the `moveoone://` scheme.
- a4.4 tag-update discipline applies to both stores: every tag update
  ships to Play **and** AppGallery together (same versionCode).
