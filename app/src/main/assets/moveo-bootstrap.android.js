// Android bootstrap — runs INSIDE the ScriptBuilder IIFE, immediately after
// the vendored moveo-one.js, only on frames that passed the origin guard.
// Byte-identical to the iOS moveo-bootstrap.ios.js except the post() seam
// (phase-a2 §a2.3): reports go to the native side via the origin-scoped
// window.moveoNative object (WebViewCompat.addWebMessageListener) instead of
// webkit.messageHandlers:
//   { type: "target" }                       event_match target hit
//   { type: "ownTag", hostname }             site ships its own Moveo tag
//   { type: "initialized", hostname }        tag init health ping (QA)
//
// The target-match block between the markers is a verbatim copy of the
// extension's (itself pinned to src/target-match.js by the extension's CI);
// the studycore tests pin the same case table on the Kotlin side.

(function () {
  var TAB_INIT_FLAG = "__moveo_ext_init";

  function post(message) {
    try {
      window.moveoNative.postMessage(JSON.stringify(message));
    } catch (e) {
      // Native handler missing (should not happen) — never break the page.
    }
  }

  function hostnameLower() {
    return String(location.hostname || "").toLowerCase();
  }

  if (!window.MoveoOne || window.MoveoOne.__moveoExtWrapped) return;

  // __MOVEO_TARGET_MATCH_BEGIN__ (verbatim copy of the extension's inline
  // block, which its CI pins to src/target-match.js — never hand-edit)
  const MATCHABLE_KEYS = ["eA", "eT", "eID", "eV", "sg", "sc"];

  function compilePattern(pattern) {
    const source = String(pattern)
      .replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
      .replace(/\\\*/g, ".*");
    return new RegExp(source, "i");
  }

  function matchesEvent(props, spec) {
    try {
      if (spec === null || typeof spec !== "object") return false;
      for (const key of Object.keys(spec)) {
        if (!MATCHABLE_KEYS.includes(key)) return false; // fail closed, first
        const pattern = spec[key];
        if (typeof pattern !== "string" || pattern.length === 0) return false;
        if (/^\*+$/.test(pattern)) continue; // unconstrained — prop may be absent
        const value = props === null || typeof props !== "object" ? undefined : props[key];
        if (typeof value !== "string") return false;
        if (!compilePattern(pattern).test(value)) return false;
      }
      return true;
    } catch {
      return false;
    }
  }
  // __MOVEO_TARGET_MATCH_END__

  /**
   * event_match observation — identical seams to the extension (phase-4
   * §4.2.3): sendEventImmediate (immediate path) and buffer.push (buffered
   * path; buffer is REASSIGNED by the tag, hence the defineProperty trap).
   * Observation only: events are never mutated, blocked, or delayed.
   */
  function installTargetHooks(instance, spec) {
    var reported = false;
    var report = function () {
      if (reported) return; // per-page dedupe; native side is the authority
      reported = true;
      post({ type: "target" });
    };
    var evaluate = function (event) {
      try {
        if (!reported && event && matchesEvent(event.prop, spec)) report();
      } catch (e) {
        // Matching must never disturb the tag pipeline.
      }
    };

    var originalSend = instance.sendEventImmediate;
    instance.sendEventImmediate = function (event) {
      evaluate(event);
      return originalSend.call(this, event);
    };

    var wrapArray = function (arr) {
      arr.push = function () {
        for (var i = 0; i < arguments.length; i++) evaluate(arguments[i]);
        return Array.prototype.push.apply(this, arguments);
      };
      return arr;
    };
    var current = wrapArray(instance.buffer);
    Object.defineProperty(instance, "buffer", {
      configurable: true,
      get: function () {
        return current;
      },
      set: function (value) {
        current = wrapArray(value);
      },
    });

    // Early-event compensation: the page-navigation / existing-session
    // branches emit page_view (and viewport_size) SYNCHRONOUSLY inside
    // init(), before these hooks exist. Evaluate a synthetic page_view once
    // per load so eA:"page_view" targets still work. eID deliberately absent
    // (fails closed), path in BOTH eV and sc — same as the extension.
    var path = location.pathname && location.pathname.trim() ? location.pathname : "unknown";
    evaluate({ prop: { sg: "global", eA: "page_view", eT: "page", eV: path, sc: path } });
  }

  var extInitialized = false;
  var realInit = window.MoveoOne.init;

  // Own-tag detection: at document start we run before any page script, so a
  // site's own Moveo integration can only init AFTER ours — surface it and
  // the app yields on this host from the next page load on.
  window.MoveoOne.init = function (token, options) {
    if (extInitialized && window.MoveoOne.instance) {
      post({ type: "ownTag", hostname: hostnameLower() });
      return window.MoveoOne.instance;
    }
    return realInit.call(window.MoveoOne, token, options);
  };
  window.MoveoOne.__moveoExtWrapped = true;

  if (window.MoveoOne.instance) {
    // A pre-existing instance at document start means someone else's tag —
    // yield to it and report (mirrors the extension's bridge-arrival check).
    post({ type: "ownTag", hostname: hostnameLower() });
    return;
  }

  // Per-tab session semantics (extension Phase-0 decision 0.3, Option A): on
  // the first injection per WebView page-session, wipe the tag's stored
  // session so start_session always fires; same-session navigations reuse it.
  try {
    if (!sessionStorage.getItem(TAB_INIT_FLAG)) {
      ["moveo-session-id", "moveo-session-timestamp", "moveo-last-path"].forEach(function (k) {
        localStorage.removeItem(k);
      });
      sessionStorage.setItem(TAB_INIT_FLAG, "1");
    }
  } catch (e) {
    // Storage can be blocked — init regardless.
  }

  extInitialized = true;
  var instance = realInit.call(window.MoveoOne, __MOVEO_PAYLOAD__.token, __MOVEO_PAYLOAD__.options);
  if (instance && __MOVEO_PAYLOAD__.targetAction && __MOVEO_PAYLOAD__.targetAction.props) {
    installTargetHooks(instance, __MOVEO_PAYLOAD__.targetAction.props);
  }
  if (instance) {
    post({ type: "initialized", hostname: hostnameLower() });
  }
})();
