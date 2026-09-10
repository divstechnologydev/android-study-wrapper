# a2.9 — Session-id link at study completion

Port of the extension's `e37d054 session id passed on study end (#9)`
(main, 2026-09-10). iOS has not ported this yet — the extension is the
normative reference here. Written 2026-09-10; the plan pointer is
[plan.md §a2.9](plan.md).

**Status:** implemented 2026-09-10 — studycore (`ActiveStudy.sessionId`,
`SessionIds`, `ConfigService.reportSession`, +5 tests), bootstrap `session`
post, model bridge case + completion report (+4 app JVM tests);
`./gradlew build` green. Device pass pending.

## 1. What it does

The vendored tag mints its own tracking-session id (`instance.sessionId`,
sent with every event as `sId`). Until now the backend could not connect an
*enrollment* (billing record) to the *tracking sessions* it produced. Now:

1. After OUR tag init succeeds, the bootstrap reports the id to the native
   side. Yield paths return before this point, so a site's own tag sessions
   are never reported.
2. The model stores the **latest** observed id on the active study record
   (each page load re-reports; latest wins).
3. At participant-side completion (target reached → lead-out shown, or
   "Finish study"), the app POSTs to the new backend endpoint:

   ```
   POST {apiBase}/{code}/sessions
   { "enrollmentId": "e_…", "participantId": "p_…", "sessionId": "…" }
   ```

**Best effort, by contract:** one retry for network / 5xx / 429 (1.5 s
apart), then give up; any other 4xx (endpoint not deployed, malformed) is
not retried. Completion is never blocked or failed by this call — the
analytics stream stays the ground truth for which sessions really
happened. No session observed (participant never opened the browser), or a
record predating enrollment ids ⇒ no call at all. Server-side ends,
revocations and leaving do **not** report (extension parity).

**Trust model:** the id is page-side input (the tag runs in the study
site's page). It is validated against the extension's exact rule —
`^[A-Za-z0-9_-]{8,64}$` (`SessionIds.isValid`) — before storing, and again
inside `reportSession`. A spoofed id at worst links a wrong session; it is
never rendered or executed.

## 2. Android design

| Extension | Android | Note |
|---|---|---|
| bootstrap → `CustomEvent` (MAIN world) → config-bridge (ISOLATED world) → `chrome.runtime.sendMessage(SESSION_OBSERVED)` | bootstrap → `post({ type: "session", sessionId })` | the two-world hop collapses to the existing origin-scoped bridge seam |
| `recordSessionId` promise chain (async `chrome.storage`, tabs race) | plain read-modify-write in `handleBridgeMessage` | bridge posts and all state mutation share the main thread — nothing to serialize |
| `completeStudy` `await`s `reportSession` before `deactivate()` (which wipes the ids) | ids captured into locals, then fire-and-forget `scope.launch`; storage cleared immediately | a2.8's persist-completion-immediately rule outranks request ordering; recorded in [plan §6](plan.md) |
| `enrollment.js reportSession` (fetch + `AbortSignal.timeout`) | `ConfigService.reportSession` (OkHttp, injectable retry delay) | same status handling: 2xx ✓, 4xx (≠429) give up, network/5xx/429 retry once |
| `storage.js` schema comment `activeStudy.sessionId` | `ActiveStudy.sessionId` (`@Serializable`, nullable) | legacy records decode with `null` → completion skips the POST |

The bootstrap now carries one block the iOS bootstrap does not have yet
(the session post). When iOS ports this, the byte-identical-except-`post()`
invariant is restored; until then the diff vs iOS is header + `post()` seam
+ this block.

## 3. Test matrix

studycore (`ConfigServiceTests`, `StudyStoreTests`):

| Case | Asserts |
|---|---|
| happy path | exact path `/{CODE}/sessions` + exact 3-field body; 204 → true |
| 4xx | false, exactly one request (no retry) |
| 5xx→2xx, 429→2xx, 5xx→5xx | retry once; second result decides |
| invalid session id / empty enrollment id / malformed code | false without any network call |
| storage | `sessionId` round-trips; absent key decodes null; not written when null |

app (`AppViewModelFinishTests`):

| Case | Asserts |
|---|---|
| `session` bridge post | stored on the active study; latest wins; UI mirror follows |
| invalid / study-less posts | ignored (charset rule; stale page after leave) |
| completion with a session | exactly one POST with the enrollment's id, `p_abc`, the latest session id |
| completion without a session | no request, conclusively (skip is synchronous) |

Device pass (pending): the mock backend (extension repo, run verbatim) has
no `/sessions` route yet, so the POST 404s — visible as
`POST …/sessions → 404` on `moveo-backend`, and completion proceeds
regardless (which is itself worth observing once). To see the body accepted,
add the route to the extension repo's `scripts/mock-backend.mjs`.
