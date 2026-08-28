# Hand-off: make `https://app.moveo.one/extension/config/…` open the Android app

**Owner: platform/web team.** Nothing changes in the Android app — the
intent filters have shipped since a1.1 ([AndroidManifest.xml](../app/src/main/AndroidManifest.xml)).
The only missing piece is the Digital Asset Links file on the web host.

Written 2026-08-28.

## Current state (measured 2026-08-28)

| URL | Returns | Verdict |
|---|---|---|
| `https://app.moveo.one/.well-known/assetlinks.json` | `[]` (empty JSON array) | ❌ file exists but declares no app → verification fails |
| `https://dev-app.moveo.one/.well-known/assetlinks.json` | the SPA's `index.html` | ❌ the React catch-all route swallows `/.well-known/*` → not even JSON |

Consequence today: tapping an `https://app.moveo.one/extension/config/<code>`
link opens the **browser**, never the app. The `moveoone://config/<code>`
scheme link works everywhere and is unaffected.

## What to deploy

Serve this at **`https://app.moveo.one/.well-known/assetlinks.json`**, with
`Content-Type: application/json`, HTTP 200, no redirect, and routed **before**
the SPA catch-all:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "one.moveo.studywrapper",
      "sha256_cert_fingerprints": [
        "PLAY_APP_SIGNING_SHA256_GOES_HERE",
        "26:70:28:65:8D:82:FD:8C:85:F7:6E:F7:75:4D:AC:78:0B:4C:A6:8A:5D:0C:1E:81:DB:4B:1B:9A:12:6B:C4:BE"
      ]
    }
  }
]
```

### The three fingerprints

| # | Which key | SHA-256 | Needed for |
|---|---|---|---|
| 1 | **Play App Signing** | ⚠️ not knowable from this repo — see below | Every install from Google Play |
| 2 | **AppGallery distribution** (our `studywrapper-release.jks`) | `26:70:28:65:8D:82:FD:8C:85:F7:6E:F7:75:4D:AC:78:0B:4C:A6:8A:5D:0C:1E:81:DB:4B:1B:9A:12:6B:C4:BE` | Every install from Huawei AppGallery |
| 3 | **Debug** (this Mac's `~/.android/debug.keystore`) | `71:39:13:70:88:DF:E0:1B:B1:65:22:3B:BA:06:C7:30:03:02:F2:E5:F4:A0:5C:C0:63:F0:23:23:7E:91:E2:A1` | Internal builds against `dev-app.moveo.one` only — **do not put this one on the prod host** |

**#1 must come from Play Console**, not from our keystore: Play App Signing
strips our upload signature and re-signs with Google's key, so the
certificate on a user's device is Google's. Get it at
**Play Console → (app) → Test and release → Setup → App signing → App
signing key certificate → SHA-256**. Until the app is uploaded once, that
key does not exist — so this file can only be completed after the first
Play upload. Deploying with #2 alone already fixes AppGallery and sideloaded
release builds.

Multiple certs in one `sha256_cert_fingerprints` array is the supported,
intended shape — no separate entries needed.

### dev host

Same file at `https://dev-app.moveo.one/.well-known/assetlinks.json` with
fingerprint **#3** (and #1/#2 if dev testing uses release builds). The real
fix there is routing: `/.well-known/*` must be served as a static file
before the SPA's catch-all, otherwise it keeps returning `index.html`.

## Verifying after deploy

```sh
# 1. the file itself — must be JSON, 200, no redirect
curl -sI https://app.moveo.one/.well-known/assetlinks.json | head -3
curl -s  https://app.moveo.one/.well-known/assetlinks.json

# 2. Google's verification endpoint (what the device actually asks)
curl -s "https://digitalassetlinks.googleapis.com/v1/statements:list?\
source.web.site=https://app.moveo.one&\
relation=delegate_permission/common.handle_all_urls"

# 3. on a device with the app installed
adb shell pm verify-app-links --re-verify one.moveo.studywrapper
adb shell pm get-app-links one.moveo.studywrapper     # want: app.moveo.one: verified
```

Verification runs at **install time**, so an already-installed app must be
reinstalled (or re-verified with the command above) after the file goes
live.

## Meanwhile: making https links open the app without assetlinks

For QA on a device you control (Android 12+), approve the domain by hand —
this bypasses verification entirely:

- **In Settings:** Apps → Moveo One User Research → Open by default →
  Add link → tick `app.moveo.one` / `dev-app.moveo.one`.
- **By adb:**
  ```sh
  adb shell pm set-app-links-user-selection --user cur --package one.moveo.studywrapper true app.moveo.one
  ```
- **Bypassing resolution completely** (what our QA scripts use — `-n` names
  the component, so verification is never consulted):
  ```sh
  adb shell am start -a android.intent.action.VIEW -n one.moveo.studywrapper/.MainActivity \
    -d "https://app.moveo.one/extension/config/<code>?transaction_id=tx_qa_001"
  ```

None of these help real participants — only the deployed file does.

## Why the scheme link still matters after this is fixed

GMS-less Huawei devices below Android 12 have no Play Services to run App
Link verification, so `https://` links there will **never** auto-open the
app no matter what this file says. `moveoone://config/<code>` remains the
load-bearing path on AppGallery ([plan §h4](plan.md)), which is why the
landing page's "Open in app" button is still required.
