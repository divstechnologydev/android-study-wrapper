// Android bootstrap — runs INSIDE the ScriptBuilder IIFE, immediately after
// the vendored moveo-one.js, only on frames that passed the origin guard.
// Byte-identical to the iOS moveo-bootstrap.ios.js except the post() seam
// (phase-a2 §a2.3): reports go to the native side via the origin-scoped
// window.moveoNative object (WebViewCompat.addWebMessageListener) instead of
// webkit.messageHandlers:
//   { type: "target", leafId }               event_match leaf hit (positional
//     id from the payload's flat leaf list; the native side latches it and
//     evaluates the and/or tree — config-schema §4.3)
//   { type: "ownTag", hostname }             site ships its own Moveo tag
//   { type: "initialized", hostname }        tag init health ping (QA)
//   { type: "session", sessionId }           tag's tracking-session id
//     (latest wins; linked to the enrollment at study completion)
//   - native → page: window.__moveoFlush(timeoutMs) drains the tag's event
//     buffer and resolves once the POSTs have landed (or the timeout hits),
//     so the app can finish a study without losing buffered events. (Native
//     evaluates it and relays the result as a { type: "flushed" } post —
//     evaluateJavascript cannot await a Promise.)
//   - the tag's network calls are relayed through the native side
//     (window.moveoFetch, a second origin-scoped WebMessageListener whose
//     replies come back over its JavaScriptReplyProxy) — see the CSP relay
//     block below.
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

  /**
   * CSP relay (studycore/TagRelay.kt has the full picture; extension:
   * moveo-bootstrap.js relayTransport + src/tag-relay.js; iOS: the
   * webkit.messageHandlers.moveoFetch reply handler). This script runs in
   * the page's own world, so it is bound by the host page's
   * Content-Security-Policy: a direct fetch to api.moveo.one is refused on
   * sites with a strict connect-src (Tesco, most banks). The tag exposes a
   * `transport` init option for exactly this: every request it would make
   * goes through the function below, which hands it to the native side as a
   * WebMessageListener post and resolves when the reply arrives. Only `kind`
   * and the body cross the boundary — the native side derives URL, headers
   * and token itself. The message is posted synchronously, so the
   * pagehide/beforeunload drains are on their way before an unloading page
   * is torn down (the native request outlives the page).
   *
   * Android handshake: a WebMessageListener has no per-message reply (unlike
   * WKScriptMessageHandlerWithReply), so each request carries an id and the
   * reply — posted to this frame's JavaScriptReplyProxy, delivered as a
   * "message" event on window.moveoFetch — echoes it; the pending map below
   * matches them up. The id is scoped to this document so a late reply can
   * never settle a different page's promise.
   *
   * Every transport promise is also tracked for window.__moveoFlush, which
   * is how study completion knows the drain has landed.
   */
  var RELAYED_KINDS = ["events", "ip"];
  var RELAY_TIMEOUT_MS = 20000;
  var inFlight = [];
  var pendingReplies = {};
  var relaySeq = 0;
  var relayTag = Math.random().toString(36).slice(2, 10);

  function track(promise) {
    var settled = Promise.resolve(promise).then(
      function () {},
      function () {}
    );
    inFlight.push(settled);
    settled.then(function () {
      var i = inFlight.indexOf(settled);
      if (i >= 0) inFlight.splice(i, 1);
    });
    return promise;
  }

  function relayHandler() {
    var handler = window.moveoFetch;
    return handler && typeof handler.postMessage === "function" ? handler : null;
  }

  function onRelayReply(event) {
    var reply;
    try {
      reply = JSON.parse(event && event.data);
    } catch (e) {
      return;
    }
    if (!reply || typeof reply.id !== "string") return;
    var settle = pendingReplies[reply.id];
    if (!settle) return; // timed out, or not ours
    delete pendingReplies[reply.id];
    settle(reply);
  }

  (function installRelayReplyListener() {
    var handler = relayHandler();
    if (!handler) return;
    try {
      if (typeof handler.addEventListener === "function") {
        handler.addEventListener("message", onRelayReply);
      } else {
        handler.onmessage = onRelayReply;
      }
    } catch (e) {
      // No reply channel ⇒ every relayed request times out ⇒ the tag sees
      // failed fetches, exactly as on a CSP-blocked page. Never throw here.
    }
  })();

  function nativeFetch(kind, body) {
    return new Promise(function (resolve, reject) {
      var handler = relayHandler();
      if (!handler) {
        reject(new Error("MoveoOne relay: no native handler"));
        return;
      }
      var id = relayTag + "-" + ++relaySeq;
      var done = false;
      var timer = setTimeout(function () {
        if (done) return;
        done = true;
        delete pendingReplies[id];
        reject(new Error("MoveoOne relay: timed out"));
      }, RELAY_TIMEOUT_MS);
      var finish = function (settle, value) {
        if (done) return;
        done = true;
        clearTimeout(timer);
        delete pendingReplies[id];
        settle(value);
      };
      pendingReplies[id] = function (result) {
        if (!result || typeof result.status !== "number" || result.status === 0) {
          // Same contract as a fetch that never reached the server.
          finish(reject, new Error("MoveoOne relay: request failed"));
          return;
        }
        finish(resolve, {
          ok: !!result.ok,
          status: result.status,
          body: typeof result.body === "string" ? result.body : "",
        });
      };
      try {
        window.moveoFetch.postMessage(JSON.stringify({ id: id, kind: kind, body: body }));
      } catch (e) {
        finish(reject, new Error("MoveoOne relay: request failed"));
      }
    });
  }

  function relayTransport(request) {
    if (RELAYED_KINDS.indexOf(request.kind) === -1) {
      // A kind this bootstrap predates: keep the tag's own behaviour.
      return track(
        fetch(request.url, {
          method: request.method,
          headers: request.headers,
          body: request.body,
          keepalive: request.keepalive,
        }).then(function (r) {
          return r.text().then(function (text) {
            return { ok: r.ok, status: r.status, body: text };
          });
        })
      );
    }
    return track(nativeFetch(request.kind, typeof request.body === "string" ? request.body : ""));
  }

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
  function installTargetHooks(instance, leaves) {
    // Per-page, per-leaf dedupe; the native side is the authority (it latches
    // each leaf once and evaluates the and/or tree — no tree logic here).
    var reported = {};
    var reportedCount = 0;
    var report = function (leafId) {
      reported[leafId] = true;
      reportedCount++;
      post({ type: "target", leafId: leafId });
    };
    var evaluate = function (event) {
      try {
        if (!event || reportedCount === leaves.length) return;
        for (var i = 0; i < leaves.length; i++) {
          var leaf = leaves[i];
          if (!reported[leaf.id] && matchesEvent(event.prop, leaf.props)) report(leaf.id);
        }
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
    // per load (against every leaf) so eA:"page_view" leaves still work. eID
    // deliberately absent (fails closed), path in BOTH eV and sc — same as
    // the extension.
    var path = location.pathname && location.pathname.trim() ? location.pathname : "unknown";
    evaluate({ prop: { sg: "global", eA: "page_view", eT: "page", eV: path, sc: path } });
  }

  /**
   * Flush bridge for study completion. The tag batches events (5 s interval /
   * size threshold) and its senders — flush() and sendEventImmediate() — do
   * not return their request promises, so nothing can be awaited as-is.
   * Every request now goes through relayTransport, which tracks each promise
   * in `inFlight`; window.__moveoFlush(timeoutMs) drains the buffer and
   * resolves when every tracked request has settled, or at the timeout.
   * Observation only: nothing is mutated, blocked, or delayed.
   */
  function installFlushBridge(inst) {
    window.__moveoFlush = function (timeoutMs) {
      try {
        inst.flush();
      } catch (e) {
        // Never let a tag error block completion.
      }
      return new Promise(function (resolve) {
        var waiting = [];
        var timer = setTimeout(function () {
          resolve({ sent: waiting.length, timedOut: true });
        }, typeof timeoutMs === "number" ? timeoutMs : 2500);
        // The tag hands a request to the transport from a microtask
        // (sendRequest: Promise.resolve().then(transport)), so the in-flight
        // list is read one macrotask later to include the drain just issued.
        setTimeout(function () {
          waiting = inFlight.slice();
          Promise.all(waiting).then(function () {
            clearTimeout(timer);
            resolve({ sent: waiting.length, timedOut: false });
          });
        }, 0);
      });
    };
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
  // The transport is attached here, not in the native payload: functions
  // cannot be inlined as JSON, and this is the only init we own.
  var options = Object.assign({}, __MOVEO_PAYLOAD__.options, { transport: relayTransport });
  var instance = realInit.call(window.MoveoOne, __MOVEO_PAYLOAD__.token, options);
  // Flat list of unfired event_match leaves { id, props } (the native side
  // walked the tree — ScriptBuilder); shape-check before trusting it.
  var rawLeaves =
    __MOVEO_PAYLOAD__.targetAction && Array.isArray(__MOVEO_PAYLOAD__.targetAction.leaves)
      ? __MOVEO_PAYLOAD__.targetAction.leaves
      : [];
  var leaves = rawLeaves.filter(function (leaf) {
    return leaf && typeof leaf.id === "string" && leaf.props !== null && typeof leaf.props === "object";
  });
  if (instance && leaves.length) {
    installTargetHooks(instance, leaves);
  }
  if (instance) {
    installFlushBridge(instance);
    post({ type: "initialized", hostname: hostnameLower() });

    // Report the tag's session id to the native side so the app can link
    // the tracking session to the enrollment at study completion. Only OUR
    // init reaches this point (yield paths return above), so a site's own
    // tag sessions are never reported (extension moveo-bootstrap.js
    // SESSION_EVENT; the bridge post is the Android seam).
    try {
      var sessionId = instance.sessionId;
      if (typeof sessionId === "string" && sessionId) {
        post({ type: "session", sessionId: sessionId });
      }
    } catch (e) {
      // Never break the host page — a later page load reports again.
    }
  }
})();
