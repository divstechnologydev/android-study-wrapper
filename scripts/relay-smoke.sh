#!/usr/bin/env bash
# CSP-relay smoke test on a real Android System WebView — the Android
# analogue of the iOS repo's Scripts/relay-smoke.swift. Runs the instrumented
# app/src/androidTest/.../RelaySmokeTest.kt on the connected device/emulator:
# a page with `connect-src 'none'` + the production injection (vendored tag +
# bootstrap) + a stand-in moveoFetch listener; passes when the direct fetch
# is blocked by the CSP while events and the IP lookup still arrive through
# the relay, the flush bridge resolves, and the bridge posts keep working.
#
#   scripts/relay-smoke.sh            # one booted AVD / device over adb
#
# Re-run after every scripts/vendor-tag.sh (studycore's TagRelayTests pin
# the seams; this catches behaviour). Any extra arguments go to gradle.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
exec ./gradlew :app:connectedPlayDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=one.moveo.studywrapper.RelaySmokeTest \
  "$@"
