package one.moveo.studycore

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import one.moveo.studycore.generated.TagEndpoint

/// Swift-Result analogue so failures stay typed (kotlin.Result erases the
/// error type).
sealed class ApiResult<out T, out E> {
    data class Success<T>(val value: T) : ApiResult<T, Nothing>()
    data class Failure<E>(val error: E) : ApiResult<Nothing, E>()

    val valueOrNull: T? get() = (this as? Success)?.value
    val errorOrNull: E? get() = (this as? Failure)?.error
}

/// The three public backend calls, shared with the extension:
/// `GET /{code}` (config fetch — also the kill switch),
/// `POST /{code}/enroll` (the billing truth) and
/// `POST /{code}/sessions` (best-effort enrollment↔session link).
class ConfigService(
    val apiBase: HttpUrl,
    /// The app's versionName; sent as `extensionVersion: "android/<this>"`.
    val appVersion: String,
    private val client: OkHttpClient = OkHttpClient(),
    /// Release invariant: a config whose `tracking.apiUrl` differs from the
    /// vendored tag's baked-in endpoint is refused (the tag would silently
    /// post elsewhere than the config claims). DEBUG builds may disable this
    /// to work against dev studies, whose configs declare the dev ingestion
    /// URL — the DEBUG ingest redirect then routes events accordingly.
    val enforceTagEndpoint: Boolean = true,
    /// DEBUG-only backend-call log sink. Method/path/status only — never
    /// bodies or tokens (security invariant §6: the token must not appear in
    /// app logs). Release builds pass null (the compile-time analogue of the
    /// iOS `#if DEBUG` logger).
    private val debugLog: ((String) -> Unit)? = null,
) {
    private data class HttpReply(val status: Int, val body: ByteArray, val headers: Map<String, String>)

    private suspend fun execute(request: Request): HttpReply = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            HttpReply(
                status = response.code,
                body = response.body?.bytes() ?: ByteArray(0),
                headers = response.headers.names().associateWith { response.header(it).orEmpty() },
            )
        }
    }

    /// Fetch + validate the study config. Returns the validated config even
    /// when `study.status == ENDED` — lifecycle handling (terminal screens,
    /// kill switch) is the caller's job, mirroring the extension's split
    /// between validator and config-service.
    suspend fun fetchConfig(code: String): ApiResult<StudyConfig, ActivationError> {
        // Malformed input → same generic outcome as an unknown code; the
        // backend deliberately doesn't distinguish them either.
        val normalized = Codes.normalize(code) ?: return ApiResult.Failure(ActivationError.NotFound)
        val url = apiBase.newBuilder().addPathSegment(normalized).build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            // The endpoint is Cache-Control: no-store; make the client side
            // match so a stale cached config can never mask the kill switch.
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        val reply = try {
            execute(request)
        } catch (e: IOException) {
            debugLog?.invoke("GET $url → network error: ${e.message}")
            return ApiResult.Failure(ActivationError.Network(e.message ?: "network error"))
        }
        debugLog?.invoke("GET $url → ${reply.status} (${reply.body.size} bytes)")

        return when (reply.status) {
            200 -> when (val result = ConfigValidator.validate(reply.body.decodeToString())) {
                is ConfigValidator.ValidationResult.Valid -> {
                    val config = result.config
                    // The vendored tag posts events to its baked-in endpoint no
                    // matter what the config says — refuse mismatches instead of
                    // silently mis-routing data (extension's tag-endpoint guard).
                    if (enforceTagEndpoint && config.tracking.apiUrl != TagEndpoint.apiUrl) {
                        ApiResult.Failure(ActivationError.InvalidConfig(listOf(
                            "tracking.apiUrl: ${config.tracking.apiUrl} differs from the tag's endpoint ${TagEndpoint.apiUrl}"
                        )))
                    } else {
                        ApiResult.Success(config)
                    }
                }
                is ConfigValidator.ValidationResult.Invalid ->
                    ApiResult.Failure(
                        if (result.needsAppUpdate) ActivationError.NeedsAppUpdate
                        else ActivationError.InvalidConfig(result.errors)
                    )
            }
            404 -> ApiResult.Failure(ActivationError.NotFound)
            429 -> ApiResult.Failure(
                ActivationError.RateLimited(retryAfterSeconds = reply.headers["Retry-After"]?.toDoubleOrNull())
            )
            else -> ApiResult.Failure(ActivationError.Server(reply.status))
        }
    }

    data class EnrollSuccess(
        val participantId: String,
        val enrolledAt: String?,
        /// true on 409 — an existing enrollment record; success by contract.
        val alreadyEnrolled: Boolean,
    )

    /// Called after the participant accepts consent; retried with the SAME
    /// `enrollmentId` if the participant taps Accept again after a network
    /// failure. `transactionId` is the optional panel-provider id from the
    /// setup link — sent only when present so the request is unchanged for
    /// plain links and typed codes (extension parity: key absent, not null).
    /// `consentTextVersion` must be the exact version of the wording that
    /// was displayed — it is the server-side GDPR audit reference.
    suspend fun enroll(
        code: String,
        participantId: String,
        enrollmentId: String,
        transactionId: String? = null,
        consentTextVersion: String,
    ): ApiResult<EnrollSuccess, EnrollError> {
        val normalized = Codes.normalize(code) ?: return ApiResult.Failure(EnrollError.Validation)
        val url = apiBase.newBuilder().addPathSegment(normalized).addPathSegment("enroll").build()
        val body = buildJsonObject {
            put("participantId", participantId)
            put("enrollmentId", enrollmentId)
            if (transactionId != null) put("transactionId", transactionId)
            put("consent", true)
            put("consentTextVersion", consentTextVersion)
            put("extensionVersion", "${BackendConstants.CLIENT_MARKER}/$appVersion")
            put("client", BackendConstants.CLIENT_MARKER)
        }
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .build()

        val reply = try {
            execute(request)
        } catch (e: IOException) {
            debugLog?.invoke("POST $url → network error: ${e.message}")
            return ApiResult.Failure(EnrollError.Network(e.message ?: "network error"))
        }
        debugLog?.invoke("POST $url → ${reply.status}")

        return when (reply.status) {
            201, 409 -> {
                val json = try {
                    Json.parseToJsonElement(reply.body.decodeToString()).jsonObject
                } catch (_: Exception) {
                    null
                }
                ApiResult.Success(EnrollSuccess(
                    participantId = (json?.get("participantId") as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: participantId,
                    enrolledAt = (json?.get("enrolledAt") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    alreadyEnrolled = reply.status == 409,
                ))
            }
            410 -> ApiResult.Failure(EnrollError.StudyEnded)
            403 -> ApiResult.Failure(EnrollError.EnrollmentClosed)
            400 -> ApiResult.Failure(EnrollError.ConsentRequired)
            422 -> ApiResult.Failure(EnrollError.Validation)
            else -> ApiResult.Failure(EnrollError.Server(reply.status))
        }
    }

    /// Link the tag's tracking session id to an enrollment at study
    /// completion (target reached, or "Finish study"). `sessionId` is the
    /// most recently observed one. Best effort — one retry for network /
    /// 5xx / 429, then give up: completion truth lives in the analytics
    /// stream, this endpoint is a convenience link, and completion must
    /// never be blocked on it (extension enrollment.js `reportSession`).
    /// Returns true when the backend accepted (2xx), false otherwise.
    suspend fun reportSession(
        code: String,
        participantId: String,
        enrollmentId: String,
        sessionId: String,
        /// Injectable so studycore tests don't sit through the real backoff.
        retryDelayMillis: Long = 1_500,
    ): Boolean {
        val normalized = Codes.normalize(code) ?: return false
        if (enrollmentId.isEmpty() || !SessionIds.isValid(sessionId)) return false
        val url = apiBase.newBuilder().addPathSegment(normalized).addPathSegment("sessions").build()
        val body = buildJsonObject {
            put("enrollmentId", enrollmentId)
            put("participantId", participantId)
            put("sessionId", sessionId)
        }.toString()

        repeat(2) { attempt ->
            val reply = try {
                execute(
                    Request.Builder()
                        .url(url)
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .header("Accept", "application/json")
                        .build(),
                )
            } catch (e: IOException) {
                debugLog?.invoke("POST $url → network error: ${e.message}")
                null
            }
            reply?.let { debugLog?.invoke("POST $url → ${it.status}") }
            when {
                reply != null && reply.status in 200..299 -> return true
                // 4xx (endpoint not deployed yet, malformed) — a retry
                // fixes nothing.
                reply != null && reply.status < 500 && reply.status != 429 -> return false
                attempt == 0 -> delay(retryDelayMillis)
            }
        }
        return false
    }
}
