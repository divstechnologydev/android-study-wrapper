package one.moveo.studycore

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// Pins the HTTP behavior of ConfigService: status-code → error mapping from
/// the backend contract, the tag-endpoint guard, and the exact enroll body
/// (the a0.2 client-marker decision). MockWebServer replaces the iOS
/// URLProtocol stub.
class ConfigServiceTests {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun service(appVersion: String = "1.0", enforceTagEndpoint: Boolean = true) = ConfigService(
        apiBase = server.url("/api/v1/extension-config"),
        appVersion = appVersion,
        enforceTagEndpoint = enforceTagEndpoint,
    )

    private fun respond(status: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        val response = MockResponse().setResponseCode(status).setBody(body)
        headers.forEach { (k, v) -> response.addHeader(k, v) }
        server.enqueue(response)
    }

    private fun minimalWithTracking(key: String, value: String): String {
        val json = Fixtures.json("valid-minimal.json")
        val tracking = json["tracking"]!!.jsonObject
        return JsonObject(
            json + ("tracking" to JsonObject(tracking + (key to JsonPrimitive(value))))
        ).toString()
    }

    // MARK: - fetchConfig

    @Test
    fun fetchValidConfig() = runBlocking {
        respond(200, body = Fixtures.text("valid-minimal.json"))
        val result = service().fetchConfig(code = "test-code-1234")
        val config = checkNotNull(result.valueOrNull) { "expected success, got $result" }
        assertEquals("st_min001", config.study.id)
        // Code was normalized into the URL path (separators stripped, uppercased).
        assertEquals("/api/v1/extension-config/TESTCODE1234", server.takeRequest().path)
    }

    @Test
    fun malformedCodeIsNotFoundWithoutNetwork() = runBlocking {
        val result = service().fetchConfig(code = "ab")
        assertEquals(ActivationError.NotFound, result.errorOrNull)
        assertEquals("no request expected", 0, server.requestCount)
    }

    @Test
    fun notFoundMapsToNotFound() = runBlocking {
        respond(404, body = "{\"detail\":\"Not found\"}")
        val result = service().fetchConfig(code = "UNKNOWNCODE1")
        assertEquals(ActivationError.NotFound, result.errorOrNull)
    }

    @Test
    fun rateLimitedMapsWithRetryAfter() = runBlocking {
        respond(429, headers = mapOf("Retry-After" to "30"))
        val result = service().fetchConfig(code = "TESTCODE1234")
        assertEquals(ActivationError.RateLimited(retryAfterSeconds = 30.0), result.errorOrNull)
    }

    @Test
    fun invalidConfigMapped() = runBlocking {
        respond(200, body = Fixtures.text("invalid-missing-token.json"))
        val result = service().fetchConfig(code = "BROKEN0CODE1")
        if (result.errorOrNull !is ActivationError.InvalidConfig) {
            fail("expected invalidConfig, got $result")
        }
    }

    @Test
    fun futureSchemaVersionMapsToNeedsAppUpdate() = runBlocking {
        respond(200, body = Fixtures.text("invalid-future-version.json"))
        val result = service().fetchConfig(code = "FUTURE0CODE1")
        assertEquals(ActivationError.NeedsAppUpdate, result.errorOrNull)
    }

    @Test
    fun tagEndpointMismatchRefused() = runBlocking {
        // Valid per schema, but apiUrl differs from the vendored tag's baked-in
        // endpoint — the tag would silently post there anyway, so refuse.
        respond(200, body = minimalWithTracking("apiUrl", "https://elsewhere.example/api/events"))
        val result = service().fetchConfig(code = "TESTCODE1234")
        val error = result.errorOrNull as? ActivationError.InvalidConfig
            ?: return@runBlocking fail("expected invalidConfig, got $result")
        assertTrue(error.errors.joinToString().contains("differs from the tag's endpoint"))
    }

    @Test
    fun tagEndpointMismatchAcceptedWhenGuardDisabled() = runBlocking {
        // DEBUG builds disable the guard to work against dev studies whose
        // configs declare the dev ingestion URL.
        respond(200, body = minimalWithTracking("apiUrl", "https://dev-api.moveo.one/api/analytic/event/tag"))
        val result = service(enforceTagEndpoint = false).fetchConfig(code = "TESTCODE1234")
        assertEquals(
            "https://dev-api.moveo.one/api/analytic/event/tag",
            result.valueOrNull?.tracking?.apiUrl,
        )
    }

    @Test
    fun endedStudyConfigStillReturned() = runBlocking {
        val json = Fixtures.json("valid-minimal.json")
        val study = json["study"]!!.jsonObject
        val body = JsonObject(
            json + ("study" to JsonObject(study + ("status" to JsonPrimitive("ended"))))
        ).toString()
        respond(200, body = body)
        val result = service().fetchConfig(code = "ENDED0CODE01")
        assertEquals("lifecycle is the caller's job", StudyConfig.Status.ENDED, result.valueOrNull?.study?.status)
    }

    // MARK: - enroll

    private fun lastBodyJson(): JsonObject =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test
    fun enrollBodyCarriesClientMarker() = runBlocking {
        respond(201, body = "{\"participantId\":\"p_abc\",\"enrolledAt\":\"2026-08-18T10:00:00Z\"}")
        val result = service(appVersion = "1.2.3").enroll(
            code = "test code 1234", participantId = "p_abc", enrollmentId = "e_1",
            consentTextVersion = "android-2026-08-18",
        )
        val success = checkNotNull(result.valueOrNull) { "expected success, got $result" }
        assertEquals("p_abc", success.participantId)
        assertFalse(success.alreadyEnrolled)

        val request = server.takeRequest()
        assertEquals("/api/v1/extension-config/TESTCODE1234/enroll", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        // The a0.2 decision (mirroring the locked iOS i0.2), pinned: both
        // markers, exact Android values.
        assertEquals("android", body["client"]?.jsonPrimitive?.content)
        assertEquals("android/1.2.3", body["extensionVersion"]?.jsonPrimitive?.content)
        assertEquals("true", body["consent"]?.jsonPrimitive?.content)
        assertEquals("p_abc", body["participantId"]?.jsonPrimitive?.content)
        assertEquals("android-2026-08-18", body["consentTextVersion"]?.jsonPrimitive?.content)
    }

    @Test
    fun enrollBodyCarriesEnrollmentIdAndOmitsAbsentTransactionId() = runBlocking {
        respond(201, body = "{\"participantId\":\"p_abc\"}")
        service().enroll(
            code = "TESTCODE1234", participantId = "p_abc", enrollmentId = "e_0f6c2d", consentTextVersion = "v",
        )
        val body = lastBodyJson()
        assertEquals("always sent", "e_0f6c2d", body["enrollmentId"]?.jsonPrimitive?.content)
        // Extension parity: plain links / typed codes leave the request
        // unchanged — the key must be ABSENT, not null.
        assertFalse(body.containsKey("transactionId"))
    }

    @Test
    fun enrollBodyCarriesTransactionIdWhenPresent() = runBlocking {
        respond(201, body = "{\"participantId\":\"p_abc\"}")
        service().enroll(
            code = "TESTCODE1234", participantId = "p_abc", enrollmentId = "e_0f6c2d",
            transactionId = "tx_8f31-AbC_9", consentTextVersion = "v",
        )
        val body = lastBodyJson()
        assertEquals("e_0f6c2d", body["enrollmentId"]?.jsonPrimitive?.content)
        assertEquals("tx_8f31-AbC_9", body["transactionId"]?.jsonPrimitive?.content)
    }

    @Test
    fun conflictIsSuccess() = runBlocking {
        respond(409, body = "{\"participantId\":\"p_abc\",\"enrolledAt\":\"2026-08-01T09:00:00Z\"}")
        val result = service().enroll(
            code = "TESTCODE1234", participantId = "p_abc", enrollmentId = "e_1", consentTextVersion = "v",
        )
        val success = checkNotNull(result.valueOrNull) { "409 must be success, got $result" }
        assertTrue(success.alreadyEnrolled)
        assertEquals("2026-08-01T09:00:00Z", success.enrolledAt)
    }

    // MARK: - reportSession

    /// The retry backoff is real time — zero it so failure cases don't
    /// stall the suite.
    private suspend fun report(
        code: String = "TESTCODE1234",
        enrollmentId: String = "e_1",
        sessionId: String = "sess_0123456789",
    ): Boolean = service().reportSession(
        code = code, participantId = "p_abc", enrollmentId = enrollmentId,
        sessionId = sessionId, retryDelayMillis = 0,
    )

    @Test
    fun reportSessionPostsTheLinkBody() = runBlocking {
        respond(204)
        assertTrue(report(code = "test code 1234"))
        val request = server.takeRequest()
        assertEquals("/api/v1/extension-config/TESTCODE1234/sessions", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("e_1", body["enrollmentId"]?.jsonPrimitive?.content)
        assertEquals("p_abc", body["participantId"]?.jsonPrimitive?.content)
        assertEquals("sess_0123456789", body["sessionId"]?.jsonPrimitive?.content)
    }

    @Test
    fun reportSessionDoesNotRetryClientErrors() = runBlocking {
        // Endpoint not deployed yet / malformed — a retry fixes nothing
        // (extension parity), and completion has already moved on anyway.
        respond(404)
        assertFalse(report())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun reportSessionRetriesOnceOnServerErrorsAndRateLimits() = runBlocking {
        respond(500)
        respond(204)
        assertTrue("second attempt lands", report())
        assertEquals(2, server.requestCount)

        respond(429)
        respond(200)
        assertTrue("429 is retryable", report())
        assertEquals(4, server.requestCount)

        respond(503)
        respond(503)
        assertFalse("one retry only, then give up", report())
        assertEquals(6, server.requestCount)
    }

    @Test
    fun reportSessionRefusesInvalidInputWithoutANetworkCall() = runBlocking {
        assertFalse("charset rule", report(sessionId = "bad session id!"))
        assertFalse("too short", report(sessionId = "short"))
        assertFalse("missing enrollment id", report(enrollmentId = ""))
        assertFalse("malformed code", report(code = "ab"))
        assertEquals("no request expected", 0, server.requestCount)
    }

    @Test
    fun enrollErrorMapping() = runBlocking {
        val cases = listOf(
            410 to EnrollError.StudyEnded,
            403 to EnrollError.EnrollmentClosed,
            400 to EnrollError.ConsentRequired,
            422 to EnrollError.Validation,
            500 to EnrollError.Server(500),
        )
        for ((status, expected) in cases) {
            respond(status)
            val result = service().enroll(
                code = "TESTCODE1234", participantId = "p_x", enrollmentId = "e_1", consentTextVersion = "v",
            )
            assertEquals("status $status", expected, result.errorOrNull)
            server.takeRequest(1, TimeUnit.SECONDS)
        }
    }
}
