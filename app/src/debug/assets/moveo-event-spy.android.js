// DEBUG-only event spy (phase-a3 §a3.1 parity capture). Injected as a
// SEPARATE user script, only in DEBUG builds and only when enabled in the
// debug settings — never part of a release build's injection (the asset
// itself lives in the debug source set).
//
// Wraps window.fetch and mirrors the tag's event POST bodies to the native
// side (WebMessageListener "moveoDebug") BEFORE they leave for the ingestion
// endpoint. The tag posts exclusively via fetch (its "sendBeacon" mentions
// are comments only — verified against the vendored source), so this one
// seam sees every event: buffered flushes, immediate sends, and unload
// drains. Observation only: the original fetch always proceeds untouched.
(function () {
  if (window.__moveoSpyInstalled) return;
  window.__moveoSpyInstalled = true;

  var INGEST_MARKER = "/api/analytic/event";
  var originalFetch = window.fetch;

  window.fetch = function (input, init) {
    try {
      var url = typeof input === "string" ? input : (input && input.url) || "";
      var method = (init && init.method) || (input && input.method) || "GET";
      var body = init && init.body;
      if (
        String(method).toUpperCase() === "POST" &&
        String(url).indexOf(INGEST_MARKER) !== -1 &&
        typeof body === "string"
      ) {
        try {
          window.moveoDebug.postMessage(
            JSON.stringify({
              type: "event-post",
              url: String(url),
              host: String(location.hostname || "").toLowerCase(),
              body: body,
            })
          );
        } catch (e) {
          // Native listener absent (off-study origin or spy off) — silently off.
        }
      }
    } catch (e) {
      // Spy must never affect the page or the tag.
    }
    return originalFetch.apply(this, arguments);
  };
})();
