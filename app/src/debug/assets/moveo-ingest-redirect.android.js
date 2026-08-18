// DEBUG-only ingestion redirect (verbatim from moveo-ingest-redirect.ios.js).
// The vendored tag posts events to its baked-in endpoint (TagEndpoint.apiUrl)
// no matter what the config says; against dev studies the dev-platform token
// is only valid on the dev ingestion host. This script rewrites matching
// fetch URLs so events land where the config declares. Placeholders are
// substituted natively before injection; the whole script lives in the debug
// source set and is absent from release builds.
(function () {
  "use strict";
  var FROM = "__MOVEO_INGEST_FROM__";
  var TO = "__MOVEO_INGEST_TO__";
  if (!TO || TO.indexOf("http") !== 0 || FROM === TO) { return; }
  var origFetch = window.fetch;
  if (!origFetch) { return; }
  window.fetch = function (input, init) {
    try {
      if (typeof input === "string" && input.indexOf(FROM) === 0) {
        input = TO + input.slice(FROM.length);
      } else if (input && typeof input.url === "string" && input.url.indexOf(FROM) === 0) {
        input = new Request(TO + input.url.slice(FROM.length), input);
      }
    } catch (e) {
      // Never break the page over a QA affordance.
    }
    return origFetch.call(this, input, init);
  };
})();
