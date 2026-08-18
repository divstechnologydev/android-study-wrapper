# Context — why this app, and why this shape

This doc captures the investigation and decisions behind the Android app so
nobody has to re-derive them. It adapts the iOS repo's context
(`../../ios-study-wrapper/ios-study-wrapper/docs/context.md`, analysis of
August 2026) to Android; the product-model and alternatives sections are the
shared story, the platform sections are Android-specific. Date: August 2026.

## The product model (who is who)

- **Moveo One** provides cognitive-load / UX analytics from DOM interaction
  events (clicks, hovers, appear/disappear impressions), collected by a JS
  tag and sent to Moveo's ingestion API.
- **Client (study owner)** creates a *study* in the Moveo One platform: which
  websites to track (`tracking.origins`), optional semantic-group rules,
  force-tracked elements, lead-in/lead-out surveys, and a target action that
  marks study completion. They get a shareable setup link containing a code.
- **Participant (test user)** receives the link, consents, and then simply
  uses the study's websites naturally while the tag records interactions.
- **The target websites are third-party** (e.g. Sainsbury's, Maxi,
  Sportvision). Neither Moveo, nor the client, nor the participant controls
  them. **This rules out every "put the tag on the site" solution** (direct
  embed, Google Tag Manager) — injection must happen on the participant's
  device.

On desktop this is solved by the Chrome extension
(`Moveo One Research Panel`): it fetches the study config by code and injects
the tag into study origins via `chrome.scripting.registerContentScripts`.

## The mobile gap

- **Chrome on Android does not support extensions.** Google's
  extensions-on-Android work targets desktop-class Android builds
  (Chromebooks/PCs); phone support is explicitly out of scope of that project.
- **Chrome on iOS does not support extensions** either — that gap is covered
  by the sibling iOS app (`../../ios-study-wrapper/ios-study-wrapper/`),
  already implemented.
- Therefore participants who use the study sites through mobile Chrome are
  invisible to the current product. Mobile traffic is a large share of
  ecommerce journeys, so studies are missing a major behavioral segment —
  and on most study markets Android is the larger half of that segment.

## How the industry solves it (the Loop11 precedent)

Loop11 (remote usability testing; tests any live website "including your
competitors", i.e. same no-site-access constraint as ours):

- Desktop: browser extension — same as us.
- Mobile: a **dedicated participant app** ("Loop11 User Testing", on both the
  App Store and Google Play). The test link opens the app (deep link); the
  app contains an in-app browser (a WebView with a thin shell) where the
  tested site loads and is tracked/recorded.

There is no known trick that gets tracking code into a participant's own
mobile Chrome. Every serious platform routes mobile participants into an app.

## Alternatives considered and rejected

| Option | Why rejected |
|---|---|
| Tag embedded in site / GTM | Requires site ownership — nobody in the loop has it. |
| Rewriting proxy (serve site through our domain, inject tag) | Broken cookies/auth across the proxied origin, anti-bot walls (Akamai/Cloudflare on exactly the ecommerce sites we study), service workers, CSP, ToS/legal exposure. Dead end for logged-in third-party sites. |
| Firefox-for-Android port of the extension | Technically possible on Android (unlike iOS), but participants must install Firefox *and* the extension *and* switch browsers — at that point the behavioral-realism argument vs an app evaporates. It would also fork the client fleet: an extension variant to maintain for one platform while iOS already ships the wrapper-app pattern. Kept on record as a possible cheap pilot; not the product. |
| Kiwi-style Chromium fork / Edge Canary | Kiwi discontinued Jan 2025; Edge Canary is not something one asks study participants to install. |
| Trusted Web Activity / Custom Tabs injection | Custom Tabs render in the user's real Chrome — which is exactly the surface we cannot script. No injection hook exists. |

**Chosen: a WebView wrapper app** — the participant activates a study,
consents, and browses the study site inside the app, where we inject the tag.
Same shape as the iOS app; the two apps are siblings of one design.

## Why the existing architecture makes this cheap

Decisions made for the extension (recorded in the extension repo's
`docs/README.md`) were explicitly designed to enable mobile clients later,
and the iOS app has since proven the whole pattern end-to-end:

- **"Code is the primitive, link is the delivery"** — the setup link
  `https://app.moveo.one/extension/config/<code>` carries a code; any client
  can extract it and fetch config from the backend. The same link becomes an
  Android App Link.
- **"One backend endpoint serves all entry points"** — setup link, manual
  code entry, and the mobile apps were named in the original design. iOS
  shipped against those endpoints unchanged; Android needs almost nothing new
  (only its own `client` marker value in the enroll body).
- **The tag is portable.** `extension/moveo-one.js` (~4,000 lines) contains
  **zero `chrome.*` API references** — plain MAIN-world JS. The app vendors
  the tag byte-identical and replaces the shell with native code, exactly as
  iOS does.
- **Config-driven features ride along for free**: semantic-group rules,
  force-tracked elements, lead-in/lead-out, target actions all live in the
  config JSON and the tag/bootstrap — the app ships them by shipping the
  config and the tag, not by reimplementing them.
- **The iOS repo is a second normative source.** Its `StudyKit` package is a
  clean-room port of the extension contracts (validator, origins, codes,
  target matching, script assembly), pinned by the extension's fixture suite.
  The Android port translates StudyKit file-for-file instead of re-deriving
  anything (port map in [plan.md](plan.md)).

## What is genuinely different on Android (the real work)

1. **Origin scoping is natively supported — the hard iOS problem disappears.**
   `WebViewCompat.addDocumentStartJavaScript(script, allowedOriginRules)`
   (AndroidX WebKit) injects at document start only into frames whose origin
   matches the rules — the WebView-level equivalent of Chrome's `matches`.
   iOS had to rebuild scoping in two layers because `WKUserScript` has no URL
   filter; Android gets layer 0 from the platform. We keep the injected JS
   origin guard anyway (defense in depth, and it keeps the script assembly
   identical to iOS), plus the native navigation policy as the scope-keeper.
2. **The WebView is a separately-updated component** (Android System
   WebView) — the Android-specific risk is version spread, not API absence.
   `DOCUMENT_START_SCRIPT` and `WEB_MESSAGE_LISTENER` must be feature-checked
   at runtime (`WebViewFeature.isFeatureSupported`); devices below the bar get
   a friendly "update Android System WebView" screen, not a degraded racy
   injection path (decision §a0.3).
3. **The JS↔native bridge is origin-scoped too.**
   `WebViewCompat.addWebMessageListener(jsObjectName, allowedOriginRules,
   listener)` injects the bridge object only into study-origin frames —
   stricter than iOS's `WKScriptMessageHandler`, which is page-global. The
   bootstrap's only platform seam is its `post()` function.
4. **Re-validation runs on app-foreground via `ProcessLifecycleOwner`**,
   with an optional WorkManager periodic job as best-effort bonus — the same
   posture as iOS (foreground check is the mechanism, background is bonus),
   though WorkManager is considerably more reliable than `BGAppRefreshTask`.
5. **Login realism**: participants sign in to study sites *inside the app*
   (WebView cookies persist across launches, so once per study). Google and
   Facebook block WebView logins on Android exactly as on iOS
   (`disallowed_useragent` — the WebView UA carries a `wv` token) — a
   per-study screening criterion, not an engineering fix.
6. **Distribution is Google-gated**: Play Console review, the Data safety
   form (the Play analogue of Apple's privacy labels), closed-testing track
   instead of TestFlight. Loop11's participant app passes both stores —
   precedent exists.

## Methodological note for study design (surface to clients)

An in-app browser session is not the participant's everyday Chrome: fresh
logins, no autofill history, no existing basket. For UX/cognitive-load
analytics this is acceptable (the interaction stream is identical in kind),
but studies comparing desktop vs mobile cohorts should note the context
difference. Session metadata carries a platform marker (`client: "android"`,
§a0.2) so cohorts stay separable — desktop vs iOS vs Android.
