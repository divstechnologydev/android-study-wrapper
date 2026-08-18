# Moveo One Android Study Wrapper

Android participant app for Moveo One research studies — activation, consent,
and a locked-down in-app browser (WebView) that injects the Moveo analytics
tag into the study's websites. The Android sibling of the iOS study wrapper.

**Status: in progress.** Phase a0 done (project skeleton: `:app` +
`:studycore`, vendored tag + fixtures, `scripts/`); implementation follows
[docs/plan.md](docs/plan.md) phase by phase. Build: `./gradlew build`; unit
tests: `./gradlew :studycore:test`. [docs/](docs/) is the plan and context
package:

| Doc | What |
|---|---|
| [docs/README.md](docs/README.md) | What we're building, architecture, phases at a glance, key constraints |
| [docs/context.md](docs/context.md) | Why this app exists and why this shape (investigation + rejected alternatives) |
| [docs/plan.md](docs/plan.md) | Phase-by-phase implementation plan (a0–a4), StudyKit→studycore port map, milestones, platform-difference ledger |

## Sibling repos (read-only dependencies)

- `../../chrome-extension/moveo_one_chrome_extension/` — the normative config
  schema, backend contract, the tag file (vendored byte-identical), mock
  backend, schema fixtures.
- `../../ios-study-wrapper/ios-study-wrapper/` — the reference
  implementation this app mirrors screen-for-screen and rule-for-rule.
