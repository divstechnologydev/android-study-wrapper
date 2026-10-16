# a6 — Content-Security-Policy relay (a2.10)

Port of the iOS `csp-fixes` branch (itself parity with the extension's
`sec-policy-overcome` branch, commit `acda744`), 2026-10-16. Where this doc
says "same as iOS" the behaviour is pinned by a test on both sides.

## 1. Problem

The vendored tag is injected into the study page's own JavaScript world, so
every `fetch` it makes is subject to the host site's Content-Security-Policy.
Sites with a strict `connect-src` (Tesco, most banks) refuse `api.moveo.one`
and every event is lost silently — the tag's error path is a swallowed
rejection. Native code is not bound by the page's policy, so the tag's
network calls are relayed through the app.

## 2. Mechanism

| Step | Extension | iOS | Android |
|---|---|---|---|
| Tag seam | `options.transport` (`sendRequest`), kinds `events` \| `ip` | same vendored tag | same vendored tag (`app/src/main/assets/moveo-one.js`, `1.0.22-ext`) |
| Page → native | DOM event → isolated bridge → runtime message `TAG_FETCH` | `webkit.messageHandlers.moveoFetch.postMessage` (reply handler) | `window.moveoFetch.postMessage(JSON { id, kind, body })` — a second origin-scoped `addWebMessageListener` |
| Native → page | runtime reply | reply-handler promise | `JavaScriptReplyProxy.postMessage(JSON { id, ok, status, body })` → `message` event on `window.moveoFetch`; the bootstrap matches by `id` |
| Builder | `src/tag-relay.js buildTagRequest` | `StudyKit/TagRelay.swift` | `studycore/TagRelay.kt buildRequest` |
| Transport | background `fetch` + `AbortSignal.timeout(15 s)` | `URLSession`, 15 s | OkHttp, `callTimeout` 15 s (`TagRelay.defaultClient`) |
| Page-side timeout | 20 s | 20 s | 20 s |

The bootstrap (`moveo-bootstrap.android.js`) is the iOS bootstrap with the
Android handshake: a WebMessageListener has no per-message reply, so each
request carries `id = <per-document random tag>-<sequence>`, kept in a
pending map until the echoing reply, the 20 s timeout, or a thrown
`postMessage`. The reply listener is attached once per document
(`addEventListener("message", …)`, `onmessage` fallback). The message is
posted synchronously, so `pagehide`/`beforeunload` drains leave before the
page is torn down; the native request runs in the model's app scope (not
the browser screen's), so it also outlives a WebView the participant is
closing — only the *answer* is dropped when there is no page left.

## 3. Trust model (same as the extension and iOS)

Everything that reaches the native side crossed the page boundary, so a page
script could have forged it. Hence:

- the message carries only `kind` and an opaque `body` — URL, headers and
  the `Authorization` token are derived natively from the **active study**
  (the same token the injected payload already hands the tag; a forged
  message gains nothing a page-world fetch could not do);
- the posting frame's origin (`sourceOrigin` from the listener, never a
  field in the message) must match the study origins
  (`Origins.hostnameMatches`); on Android the `moveoFetch` object only
  exists on those origins to begin with (same `allowedOriginRules` as the
  bridge) — the native check stays for parity and defense in depth;
- `events` bodies must be a `{ "events": [...] }` document ≤ 1 MiB (UTF-16
  units, like the extension's `body.length`); responses are capped at
  64 KiB (a split surrogate pair is repaired);
- no active study, or the **demo** (which never injects the tag) ⇒ refused;
- anything refused or failed replies `{ ok: false, status: 0, body: "" }`,
  which the bootstrap turns into a rejected promise — exactly what the tag
  sees from a failed fetch, so all its call sites already cope.

## 4. Android decisions (ledger: plan §6)

- **Reply correlation by id** instead of a per-message reply handler.
- **App-scoped request** (`AppViewModel.relayTagRequest` → `scope.launch`)
  instead of the iOS unstructured `Task`; the WebView controller drops the
  reply when its WebView is gone.
- **OkHttp** with a whole-call 15 s timeout; `Content-Type` is the bare
  `application/json` the tag sends (byte body, not the String overload that
  appends a charset).
- **DEBUG knock-ons live in the model** behind an injected seam: the ingest
  redirect target is `AppViewModel.effectiveIngestRedirect` (one definition
  for the page-world rewrite *and* the relay), and relayed batches are
  mirrored through `relayedEventsObserver` — the debug `DebugHooks` wires
  `EventSpy.observeRelayedPost`, the release one passes `null`.
- **Smoke test is instrumented** (`RelaySmokeTest`, `scripts/relay-smoke.sh`)
  on a real WebView, using the production `ScriptBuilder` assembly rather
  than a hand-rolled wrapper.

## 5. Flush bridge

`__moveoFlush` no longer swaps `window.fetch` around `flush()` /
`sendEventImmediate()`: every tag request now goes through
`relayTransport`, which tracks each promise in `inFlight`. The drain is
handed to the transport from a microtask (`sendRequest` does
`Promise.resolve().then(transport)`), so the in-flight list is read one
macrotask later. The native side is unchanged (`evaluateJavascript` +
`flushed` bridge post, docs/a2-finish.md §2.2).

## 6. Validator

No cap on the number of `semanticGroupRules` / `additionalTrackedElements`
(the tag dropped `SG_RULES_MAX_RULES` / `TRACK_SELECTORS_MAX_RULES`; per-entry
limits stay). `docs/examples/tesco-sg-rules.json` (21 tracked entries) is the
regression fixture.

## 7. Tests

| Suite | What it pins |
|---|---|
| `studycore/TagRelayTests` | builder cases (the extension's §11 table: kinds, sender origin, token/url never from the message, body shape and size), `perform` against MockWebServer (token header, bare `application/json`, `ip` GET, non-2xx, response cap, disconnect, timeout), reply envelope, seam pins (tag has no page-world ingestion fetch; `1.0.22-ext` behaviours; bootstrap `RELAYED_KINDS` == `TagRelay.kinds`; `moveoFetch` on both sides; id echoed) |
| `studycore/ConfigValidatorTests` | 40 rules / 40 entries accepted; Tesco example validates |
| `app/AppViewModelRelayTests` | study-derived token, DEBUG redirect for both kinds, spy seam (events only, lowercased host), off-origin / no-study / demo refusals, release never redirects |
| `app/androidTest/RelaySmokeTest` | real WebView under `connect-src 'none'`: direct fetch blocked, `ip` + `events` relayed from `shop.example.com`, `__moveoFlush` resolves, `1.0.22-ext`, click eV, aria-label fallback, relayed IP in event meta, bridge `initialized`/`session`/`target` |

## 8. QA on a device

```sh
scripts/relay-smoke.sh                       # instrumented smoke test on the connected AVD/device
adb logcat -s moveo-events                   # with MOVEO_EVENT_SPY 1: relayed batches appear here too
```

Against a dev study the relayed requests follow `tracking.apiUrl`
(`effectiveIngestRedirect`), IP lookup included — `MOVEO_INGEST_OVERRIDE`
still wins. Release builds relay to the tag's baked endpoint only.
