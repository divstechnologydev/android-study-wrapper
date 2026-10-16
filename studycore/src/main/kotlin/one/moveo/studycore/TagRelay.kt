package one.moveo.studycore

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import one.moveo.studycore.generated.TagEndpoint

/// CSP relay for the injected tag — port of the extension's `src/tag-relay.js`
/// (via iOS `StudyKit/TagRelay.swift`).
///
/// The tag runs in the study page's own JavaScript world (a document-start
/// script), so every request it makes is governed by the host site's
/// Content-Security-Policy. Sites with a strict `connect-src` (Tesco, most
/// banks) block `api.moveo.one` outright and no event ever leaves the page.
/// Native code is not subject to the page's policy, so the tag's network
/// calls are routed here:
///
///     tag (page)  ──options.transport──►  moveo-bootstrap.android.js
///        window.moveoFetch.postMessage (origin-scoped WebMessageListener)
///        ──►  StudyWebViewController  ──►  AppViewModel.relayTagRequest
///        ──►  TagRelay.buildRequest / perform → OkHttp
///        ... and the reply travels back over the frame's JavaScriptReplyProxy,
///        correlated by the request id the bootstrap minted.
///
/// Trust model (identical to the extension): everything that arrives here
/// crossed the page boundary, so a page script could have forged it. The
/// message therefore carries only a `kind` and an opaque body — never a URL,
/// headers or token. The URL and the Authorization header are derived HERE
/// from the active study, which is the same token the injected payload
/// already hands the tag, so a forged message gains the page nothing it could
/// not do with its own fetch. Side-effect bounds: the sending frame must be on
/// a study origin, a study must be active, and body/response sizes are capped.
object TagRelay {
    /// Request kinds the tag's `sendRequest` seam emits. Mirrored in the
    /// bootstrap (`RELAYED_KINDS`) — `TagRelayTests` pins the agreement.
    val kinds: List<String> = listOf("events", "ip")

    /// Upper bound for a relayed event batch (the tag flushes at 100 events).
    /// Measured in UTF-16 units like the extension's `body.length` (Kotlin's
    /// `String.length` already counts UTF-16 units).
    const val MAX_BODY_LENGTH = 1024 * 1024
    const val MAX_RESPONSE_LENGTH = 64 * 1024
    const val TIMEOUT_SECONDS = 15L

    /// Reply shape for "the request was not made / did not complete" — the
    /// same contract as a fetch that never reached the server; the bootstrap
    /// rejects the tag's promise on `status == 0`.
    data class Reply(val ok: Boolean, val status: Int, val body: String) {
        /// What travels back to the page (`JavaScriptReplyProxy.postMessage`
        /// takes a string). `id` is the request id the bootstrap minted — the
        /// Android handshake needs it because a WebMessageListener has no
        /// per-message reply channel (iOS's `WKScriptMessageHandlerWithReply`
        /// does), so the page side correlates replies itself.
        fun toMessage(id: String): String = buildJsonObject {
            put("id", id)
            put("ok", ok)
            put("status", status)
            put("body", body)
        }.toString()

        companion object {
            val FAILED = Reply(ok = false, status = 0, body = "")
        }
    }

    /// The tag hard-codes its IP lookup at `api.moveo.one`; when the
    /// ingestion host differs (DEBUG dev redirect) the lookup follows it,
    /// exactly as the extension derives it from `TAG_API_URL`. A missing
    /// endpoint there only costs the "ipAddress" metadata field ("Unknown").
    fun ipLookupUrl(ingestUrl: String): HttpUrl? =
        ingestUrl.toHttpUrlOrNull()?.resolve("/api/my-ip")

    /// Pure: (message, active study, sending frame's host) → the request to
    /// make, or null when it must not be made. Branch-for-branch port of the
    /// extension's `buildTagRequest`; `ingestUrl` is the tag's endpoint
    /// (release) or the DEBUG ingest redirect target. `message` is the parsed
    /// bridge message — JSON semantics on purpose (`kind`/`body` must be
    /// string primitives, never coerced).
    fun buildRequest(
        message: JsonObject,
        study: ActiveStudy?,
        senderHost: String?,
        ingestUrl: String = TagEndpoint.apiUrl,
    ): Request? {
        val kind = string(message["kind"])?.takeIf { it in kinds } ?: return null
        study ?: return null
        val tracking = study.config.tracking
        if (tracking.token.isEmpty() || tracking.origins.isEmpty()) return null

        if (senderHost.isNullOrEmpty() || !Origins.hostnameMatches(senderHost, tracking.origins)) return null

        if (kind == "events") {
            val body = string(message["body"])?.takeIf { it.isNotEmpty() && it.length <= MAX_BODY_LENGTH }
                ?: return null
            // Cheap shape check so the relay only ever forwards what the tag
            // emits: a `{ "events": [...] }` document.
            val parsed = try {
                Json.parseToJsonElement(body)
            } catch (_: Exception) {
                return null
            }
            if ((parsed as? JsonObject)?.get("events") !is JsonArray) return null
            val url = ingestUrl.toHttpUrlOrNull() ?: return null
            // Bytes, not the String overload: that one appends "; charset=utf-8"
            // to the media type, and OkHttp's bridge interceptor rewrites the
            // Content-Type header from the body — the tag sends the bare
            // "application/json", so the relay does too.
            return Request.Builder()
                .url(url)
                .post(body.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE))
                .header("Authorization", tracking.token)
                .header("Content-Type", "application/json")
                .build()
        }

        // kind == "ip" — unauthenticated GET, exactly as the tag does it.
        val url = ipLookupUrl(ingestUrl) ?: return null
        return Request.Builder().url(url).get().build()
    }

    /// Performs a request `buildRequest` produced. Never throws; any failure
    /// — including a refused request (`null`) — replies `FAILED`. Runs on the
    /// IO dispatcher; callers on the main thread just `await` it.
    suspend fun perform(request: Request?, client: OkHttpClient = defaultClient): Reply {
        request ?: return Reply.FAILED
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    Reply(ok = response.isSuccessful, status = response.code, body = capResponse(text))
                }
            } catch (_: IOException) {
                Reply.FAILED
            } catch (_: IllegalStateException) {
                Reply.FAILED
            }
        }
    }

    /// `text.slice(0, RELAY_MAX_RESPONSE_BYTES)` — UTF-16 units, and a split
    /// surrogate pair is repaired (U+FFFD) rather than dropping the body or
    /// emitting a lone surrogate, which the JSON reply could not carry.
    fun capResponse(text: String): String {
        if (text.length <= MAX_RESPONSE_LENGTH) return text
        val capped = text.substring(0, MAX_RESPONSE_LENGTH)
        return if (capped.last().isHighSurrogate()) capped.dropLast(1) + "�" else capped
    }

    /// The relay's own client: the 15 s bound is the whole call (connect +
    /// headers + body), the OkHttp analogue of the extension's
    /// `AbortSignal.timeout` and the iOS `timeoutInterval`.
    val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private fun string(v: kotlinx.serialization.json.JsonElement?): String? =
        (v as? JsonPrimitive)?.takeIf { it.isString }?.content
}
