# a2.8 — Finishing a study (Done / Finish study / target reached)

Port of the iOS `done-finish` branch (commit `b49a2f4 dinishing study
logic`), itself the port of the extension's `done-finish` branch
(`e8e718c finish study`). Written 2026-08-28. Companion of
[a1-transactions.md](a1-transactions.md); the plan pointer is
[plan.md §a2.8](plan.md).

**Status:** implemented 2026-08-28 — studycore (+2 tests), model + browser
flush bridge + activity, UI (finish dialogs, home block, "Study complete"
screen), app name; 11 new app JVM tests. Emulator pass 2026-08-28 vs the
mock (Pixel_7 / Chrome Custom Tabs): Done → dialog → lead-out with
`transaction_id` → storage completed while the tab was up → back →
`completion flush → sent N timedOut false` → "Study complete"; target page →
lead-out after 2 s → same completion; 30 s idle after closing the lead-in
tab changes nothing (study stays active). Physical-device pass pending.

## 1. What changes for the participant

Before: reaching the target opened the closing page (lead-out) and the study
simply stayed active; "Done" in the browser only closed it. There was no
way for a participant to say "I'm finished".

After (three platforms identical):

| # | Rule | Extension | iOS | Android |
|---|---|---|---|---|
| 1 | **Done** (browser) and **Finish study** (home) ask for confirmation first: "Finish this study?" — *Tracking stops and you'll be taken to the closing page.* (with a lead-out) / *Tracking stops and the study is marked complete on this device.* (without). Confirm = "Finish", cancel = "Not yet". | popup `finishConfirm` | `FinishCopy` | `FinishStudyDialog` / `FinishCopy` |
| 2 | Confirmed finish with an unshown lead-out → open the lead-out **now**, with the panel `transaction_id` (so the provider credits the completion), then complete the study. | `finishStudy()` | `finishStudy()` | `finishStudy()` |
| 3 | A lead-out already shown by the target flow is never shown again; finish then completes immediately. | same | same | same |
| 4 | **Target reached** → the usual 2 s delay → lead-out opens → the study is **completed** the moment the lead-out is shown (extension `openDueLeadOut → completeStudy`). | `openDueLeadOut` | `leadSheetPresented(.leadOut)` | `leadSheetLaunched(LEAD_OUT)` |
| 5 | Target reached with **no** lead-out configured → completes right at the target; the browser gives way after the same 2 s so the participant sees their own confirmation page. | `handleTargetReached` | `targetReached` | `targetReached` |
| 6 | Completion = ended record written (`completed: true`) **and** active study + consent + own-tag map cleared from storage immediately — crash-safe; the *screen* transition waits until the closing page is closed. | `completeStudy` | `completeStudy` + `pendingCompletion` | `completeStudy` + `pendingCompletion` |
| 7 | Terminal screen says **"Study complete"** — *You've completed "<name>" — thank you for participating! Tracking has stopped and the study has been removed from this device.* (vs. "has ended" for a server-side end, "no longer available" for a revoked code). | popup notice | `EndedStudyView` | `EndedStudyScreen` |
| 8 | Before the study browser is torn down, the app asks the injected tag to **flush** its buffered events and waits (bounded, 3 s) for the POSTs to land — otherwise up to one 5 s batch of events would be lost with the web process. | n/a (tab outlives) | `__moveoFlush` via `callAsyncJavaScript` | `__moveoFlush` via `evaluateJavascript` + bridge (§2.2) |
| 9 | Completion clears the study-origin website data like leaving (logins on the study sites do not outlive the study). | n/a | per-origin | wholesale (as leave, plan §6) |
| 10 | Re-validation never resurrects a study that storage no longer holds (the participant may finish during the fetch); an active record whose lead-out was already shown (pre-this-build) is completed on the next foreground. | n/a | `revalidateNow` guards | same |
| 11 | Display name: **Moveo One User Research**. | — | `CFBundleDisplayName` | `app_name` |

## 2. Android design

### 2.1 "Closing page dismissed" = activity resume

iOS gets `leadSheetDismissed` from the SFSafariViewController sheet. Android
opens lead surveys in a Custom Tab (or, GMS-less Huawei fallback, the
system browser via `ACTION_VIEW`), so the only signal that the participant
closed the closing page is **`MainActivity.onResume`**. `onResume` now calls
`AppViewModel.activityResumed()`: apply a pending completion if there is one,
else the existing `presentDueLeadOut()` retry. Because the completion is
already persisted (rule 6), it does not matter whether the return happens
seconds later (Custom Tab closed), minutes later (external browser, separate
task) or never (process killed) — a cold start lands on "Study complete"
straight from storage.

### 2.2 Event flush bridge

`WebView.evaluateJavascript` cannot await a Promise (a Promise serializes to
`{}`), so the iOS `callAsyncJavaScript` call becomes a two-step bridge:

1. Native evaluates a snippet that returns `"skipped"` synchronously when
   `window.__moveoFlush` is missing (no tag on this page — off-origin page,
   yielded host, injection disabled) or `"pending"` after kicking
   `__moveoFlush(timeoutMs).then(r => moveoNative.postMessage({type:
   "flushed", …}))`.
2. `"pending"` waits for the `flushed` bridge message on a
   `CompletableDeferred`, bounded by `withTimeoutOrNull(timeout + 500 ms)` —
   a hung web process can never trap the participant on a finished study.

`__moveoFlush` itself is the iOS bootstrap's `installFlushBridge`, verbatim
(the bootstraps stay byte-identical except the `post()` seam). Debug builds
log `completion flush → sent N timedOut B` on `moveo-backend`.

### 2.3 System back stays "close the browser" (ledger entry)

iOS has no way out of the full-screen browser except Done (= finish) and a
debug menu item. Android's system back is non-negotiable navigation: at the
end of the page history it returns to the study **home** (tracking
continues), exactly as before. The home screen carries the explicit
"Finish study" button, so nothing is lost — and no confirmation dialog is
hijacking a back gesture. Recorded in plan §6; the iOS debug-only "Close
browser without finishing" item is therefore not needed here.

### 2.4 Storage-mirroring on apply

`applyPendingCompletion` mirrors storage into the UI state (`activeStudy =
store.activeStudy`, `endedStudy = store.endedStudy`) rather than forcing
nulls: a setup link can arrive via `onNewIntent` while the closing page is
up (delivered before `onResume`), and the activation it starts must not be
clobbered by the completion transition.

### 2.5 Huawei

Nothing store-specific: no Custom Tabs provider ⇒ `ACTION_VIEW` to the
system browser, which runs in its own task; the participant returns via the
launcher/Recents and `onResume` applies the completion — or a cold start
shows it from storage. Same code path, later timing.

## 3. Port map

| iOS (done-finish) | Android | Notes |
|---|---|---|
| `StudyState.EndedStudy.completed` | `studycore/StudyState.kt` | nullable, absent in legacy records |
| `FlowConstants.completionFlushTimeout` | `FlowConstants.COMPLETION_FLUSH_TIMEOUT_SECONDS` | 3 s |
| `moveo-bootstrap.ios.js installFlushBridge` | `moveo-bootstrap.android.js` | verbatim |
| `BrowserProxy.flushEvents` + coordinator impl | `browser/StudyWebView.kt` | evaluateJavascript + `flushed` bridge message |
| `AppModel.pendingCompletion`, `finishStudy`, `completeStudy`, `applyPendingCompletion`, `leadSheetDismissed`, `targetReached`, `leadSheetPresented`, `revalidateNow` guards, `clearWebsiteData` | `AppViewModel.kt` | `leadSheetDismissed` ⇒ `activityResumed()` |
| `RootView.EndedStudyView` copy | `ui/EndedStudyScreen.kt` | |
| `StudyBrowserView` Done → confirm | `ui/StudyBrowserScreen.kt` | back button unchanged (§2.3) |
| `StudyHomeView` finish block + `FinishCopy` | `ui/StudyHomeScreen.kt`, `ui/FinishStudy.kt` | |
| `CFBundleDisplayName` | `res/values/strings.xml app_name` | |
| README "Finishing a study" | README | |

## 4. Test matrix (app JVM, `AppViewModelFinishTests`)

| Case | Asserts |
|---|---|
| finish with lead-out | lead-out URL carries `transaction_id`; after launch: storage cleared + ended `completed=true`, UI still on the browser; `applyPendingCompletion` → flush called **before** the browser closes, script removed, ended screen |
| finish without lead-out | no lead sheet; completes immediately |
| finish after target already showed the lead-out | no second lead-out |
| target reached, no lead-out | storage completed at once; UI transitions after the 2 s delay |
| target reached with lead-out | completion only once the lead-out launched |
| lead-out launch failure | study stays active, due time kept (self-heal) |
| resume with a pending completion | `activityResumed()` applies it; without one it only retries the lead-out |
| ended record shape | `completed=true`, `revoked=false`, `leadOutShownAt` set when shown |
| legacy shown-lead-out record | `revalidateNow` completes it without a fetch |
| finish during re-validation fetch | fetch result never resurrects the study |
| leave / decline | `pendingCompletion` cleared, own-tag map cleared |

studycore: `EndedStudy` round-trips `completed`; a record without the key
decodes with `completed == null`.

Device pass (step 4): on the emulator vs the mock — target page → lead-out
tab with `transaction_id` → close tab → "Study complete"; Done → dialog →
lead-out → complete; home Finish study; no-lead-out config completes at the
target after 2 s; `adb logcat -s moveo-backend` shows the flush line.
