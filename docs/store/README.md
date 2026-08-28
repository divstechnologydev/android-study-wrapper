# Play / AppGallery listing assets (a4-play-release.md R4)

Generated 2026-08-28 from the app's own brand sources; the client signs
them off before upload.

| File | Play slot | Notes |
|---|---|---|
| `icon-512.png` | Hi-res icon (512×512, 32-bit PNG) | Rendered from `ic_launcher_foreground.xml` on the `#111111` launcher background at Play's listing crop. |
| `feature-graphic-1024x500.png` | Feature graphic (1024×500) | Wordmark from `logo_wordmark.xml` on `#111111`, tagline "User research studies, from your phone". |
| `screenshots/01-consent.png` | Phone screenshot | 1080×2400 from the API 36 emulator against the mock backend. |
| `screenshots/02-home.png` | Phone screenshot | Same. |
| `screenshots/03-browser.png` | Phone screenshot | Same. |

Screenshot caveats (fix before the production listing, fine for the
closed-testing track): they were taken from a **debug** build (the gear
icon is visible bottom-left on the home screen) against the mock study
("Mock backend study", `example.com`). Retake from the release build on a
real study once a prod test code exists — same three screens, same
emulator, `adb exec-out screencap -p`.

Regenerate icon/feature graphic: the converter lives in the session
scratchpad only; the SVG is trivial (VectorDrawable paths → `<path>`,
group translate/scale → `<g transform>`), rendered with macOS
`qlmanage -t -s 1024` and cropped with `sips -c 500 1024`.
