package one.moveo.studycore

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import one.moveo.studycore.generated.TagEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/// Port of the extension's run-tests.mjs §11 "CSP relay: request builder +
/// wiring seam pins" (via iOS `TagRelayTests.swift`). The builder is pure and
/// tested directly; `perform` runs against a MockWebServer; the seams are
/// pinned so a tag re-vendor that reintroduces a page-world fetch, or a
/// rename on one side of the bootstrap ↔ native handshake, fails here instead
/// of silently dropping every event on CSP-strict sites.
class TagRelayTests {
    private val sender = "www.tesco.com"
    private val events = """{"events":[{"type":"track","prop":{"eA":"click"}}]}"""

    private fun study(token: String = "tok-123", origins: List<String> = listOf("tesco.com")): ActiveStudy {
        val base = checkNotNull(ConfigValidator.validate(Fixtures.text("valid-minimal.json")).configOrNull)
        val config = base.copy(tracking = base.tracking.copy(token = token, origins = origins))
        return ActiveStudy(code = "ABCD", config = config)
    }

    private fun message(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) {
            when (v) {
                null -> {}
                is String -> put(k, v)
                is Int -> put(k, v)
                is JsonObject -> put(k, v)
                else -> error("unsupported $v")
            }
        }
    }

    private fun build(
        message: JsonObject,
        study: ActiveStudy? = study(),
        sender: String? = this.sender,
        ingestUrl: String = TagEndpoint.apiUrl,
    ): Request? = TagRelay.buildRequest(message = message, study = study, senderHost = sender, ingestUrl = ingestUrl)

    private fun bodyOf(request: Request): String = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    // MARK: - Builder

    @Test
    fun eventsBecomePostToIngestionWithStudyToken() {
        val request = checkNotNull(build(message("kind" to "events", "body" to events)))
        assertEquals(TagEndpoint.apiUrl, request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("tok-123", request.header("Authorization"))
        assertEquals("application/json", request.header("Content-Type"))
        assertEquals(events, bodyOf(request))
    }

    @Test
    fun ipBecomesUnauthenticatedGetOnIngestionHost() {
        val request = checkNotNull(build(message("kind" to "ip")))
        assertEquals("https://api.moveo.one/api/my-ip", request.url.toString())
        assertEquals("GET", request.method)
        assertNull(request.header("Authorization"))
    }

    @Test
    fun ingestRedirectMovesBothKinds() {
        // DEBUG dev studies: events AND the IP lookup follow the redirected host
        // (the extension derives IP_LOOKUP_URL from TAG_API_URL the same way).
        val dev = "https://dev-api.moveo.one/api/analytic/event/tag"
        assertEquals(dev, build(message("kind" to "events", "body" to events), ingestUrl = dev)?.url.toString())
        assertEquals("https://dev-api.moveo.one/api/my-ip", build(message("kind" to "ip"), ingestUrl = dev)?.url.toString())
    }

    @Test
    fun senderOnBareOrSubdomainOriginAccepted() {
        assertNotNull(build(message("kind" to "ip"), sender = "tesco.com"))
        assertNotNull(build(message("kind" to "ip"), sender = "secure.checkout.tesco.com"))
        assertNotNull("case-insensitive like every other origin check", build(message("kind" to "ip"), sender = "WWW.Tesco.COM"))
    }

    @Test
    fun senderOffStudyOriginsRefused() {
        assertNull(build(message("kind" to "ip"), sender = "nottesco.com"))
        assertNull(build(message("kind" to "ip"), sender = "tesco.com.evil.example"))
    }

    @Test
    fun missingSenderRefused() {
        assertNull(build(message("kind" to "ip"), sender = null))
        assertNull(build(message("kind" to "ip"), sender = ""))
    }

    @Test
    fun noActiveStudyRefused() {
        assertNull(build(message("kind" to "ip"), study = null))
    }

    @Test
    fun studyWithoutTokenRefused() {
        assertNull(build(message("kind" to "ip"), study = study(token = "")))
    }

    @Test
    fun unknownKindRefused() {
        assertNull(build(message("kind" to "predict", "body" to events)))
        assertNull(build(message()))
        assertNull(build(message("kind" to 42)))
    }

    @Test
    fun eventsWithoutBodyRefused() {
        assertNull(build(message("kind" to "events")))
        assertNull(build(message("kind" to "events", "body" to "")))
        assertNull(build(message("kind" to "events", "body" to buildJsonObject { put("events", JsonArray(emptyList())) })))
    }

    @Test
    fun eventsBodyThatIsNotAnEventsDocumentRefused() {
        assertNull(build(message("kind" to "events", "body" to "nope")))
        assertNull(build(message("kind" to "events", "body" to """{"foo":1}""")))
        assertNull(build(message("kind" to "events", "body" to """[{"events":[]}]""")))
    }

    @Test
    fun oversizedEventsBodyRefused() {
        val huge = """{"events":["""" + "x".repeat(TagRelay.MAX_BODY_LENGTH) + """"]}"""
        assertNull(build(message("kind" to "events", "body" to huge)))
    }

    @Test
    fun tokenAndUrlNeverComeFromTheMessage() {
        val request = checkNotNull(build(message(
            "kind" to "events", "body" to events, "token" to "evil", "url" to "https://evil.example",
        )))
        assertEquals("tok-123", request.header("Authorization"))
        assertEquals(TagEndpoint.apiUrl, request.url.toString())
    }

    // MARK: - perform (OkHttp against a MockWebServer)

    private fun withServer(block: suspend (MockWebServer, String) -> Unit) = runTest {
        val server = MockWebServer()
        server.start()
        try {
            block(server, server.url("/api/analytic/event/tag").toString())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun performRefusedRequestRepliesFailed() = runTest {
        val reply = TagRelay.perform(null)
        assertEquals(TagRelay.Reply.FAILED, reply)
        assertEquals(0, reply.status)
        assertFalse(reply.ok)
    }

    @Test
    fun performPostsEventsWithTheStudyTokenAndRelaysTheResponse() = withServer { server, ingest ->
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}"""))
        val reply = TagRelay.perform(build(message("kind" to "events", "body" to events), ingestUrl = ingest))
        assertEquals(TagRelay.Reply(ok = true, status = 200, body = """{"accepted":1}"""), reply)

        val recorded = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", recorded.method)
        assertEquals("/api/analytic/event/tag", recorded.path)
        assertEquals("tok-123", recorded.getHeader("Authorization"))
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertEquals(events, recorded.body.readUtf8())
    }

    @Test
    fun performIpLookupIsAPlainGetOnTheIngestHost() = withServer { server, ingest ->
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ip":"203.0.113.9"}"""))
        val reply = TagRelay.perform(build(message("kind" to "ip"), ingestUrl = ingest))
        assertEquals(TagRelay.Reply(ok = true, status = 200, body = """{"ip":"203.0.113.9"}"""), reply)
        val recorded = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", recorded.method)
        assertEquals("/api/my-ip", recorded.path)
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun performReportsNonSuccessStatusesAsNotOk() = withServer { server, ingest ->
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        val reply = TagRelay.perform(build(message("kind" to "events", "body" to events), ingestUrl = ingest))
        assertEquals(TagRelay.Reply(ok = false, status = 401, body = "nope"), reply)
    }

    @Test
    fun performCapsTheResponseBody() = withServer { server, ingest ->
        server.enqueue(MockResponse().setResponseCode(200).setBody("y".repeat(TagRelay.MAX_RESPONSE_LENGTH + 10)))
        val reply = TagRelay.perform(build(message("kind" to "ip"), ingestUrl = ingest))
        assertEquals(TagRelay.MAX_RESPONSE_LENGTH, reply.body.length)
        assertTrue(reply.ok)
    }

    @Test
    fun performNetworkFailureRepliesFailed() = withServer { server, ingest ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val reply = TagRelay.perform(build(message("kind" to "ip"), ingestUrl = ingest))
        assertEquals(TagRelay.Reply.FAILED, reply)
    }

    @Test
    fun performTimesOutInsteadOfHangingTheRelay() = withServer { server, ingest ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val quick = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        val reply = TagRelay.perform(build(message("kind" to "ip"), ingestUrl = ingest), client = quick)
        assertEquals(TagRelay.Reply.FAILED, reply)
    }

    @Test
    fun capRepairsASplitSurrogatePair() {
        val text = "a".repeat(TagRelay.MAX_RESPONSE_LENGTH - 1) + "😀" + "tail"
        val capped = TagRelay.capResponse(text)
        assertEquals(TagRelay.MAX_RESPONSE_LENGTH, capped.length)
        assertEquals('�', capped.last())
        assertEquals("unchanged under the cap", "short", TagRelay.capResponse("short"))
    }

    @Test
    fun replyMessageCarriesTheRequestIdAndTheFetchLikeShape() {
        val json = Json.parseToJsonElement(TagRelay.Reply(ok = true, status = 200, body = "{\"ip\":\"1\"}").toMessage("r-7")).jsonObject
        assertEquals("r-7", json["id"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), json["ok"])
        assertEquals(JsonPrimitive(200), json["status"])
        assertEquals("{\"ip\":\"1\"}", json["body"]?.jsonPrimitive?.content)

        val failed = Json.parseToJsonElement(TagRelay.Reply.FAILED.toMessage("r-8")).jsonObject
        assertEquals(JsonPrimitive(0), failed["status"])
        assertEquals(JsonPrimitive(false), failed["ok"])
        assertEquals(JsonPrimitive(""), failed["body"])
    }

    // MARK: - Seam pins (vendored tag ↔ bootstrap ↔ native handler)

    private fun repoFile(path: String): String {
        val file = File(Fixtures.repoRoot(), path)
        assumeTrue("$path not reachable from the test working directory", file.isFile)
        return file.readText()
    }

    private fun count(needle: String, haystack: String): Int = haystack.split(needle).size - 1

    private fun matches(pattern: String, text: String): Boolean =
        Regex(pattern, RegexOption.DOT_MATCHES_ALL).containsMatchIn(text)

    @Test
    fun vendoredTagRoutesEveryIngestionCallThroughTheTransportSeam() {
        val tag = repoFile("app/src/main/assets/moveo-one.js")
        assertEquals("no direct fetch of the ingestion URL", 0, count("fetch(API_URL", tag))
        assertFalse("no direct fetch of the IP lookup", matches("""fetch\(\s*["']https://api\.moveo\.one/api/my-ip""", tag))
        assertEquals("four event sends go through sendRequest", 4, count("this.sendRequest(\"events\", API_URL", tag))
        assertEquals("one ip lookup goes through sendRequest", 1, count("this.sendRequest(\"ip\", IP_LOOKUP_URL", tag))
        assertTrue("init applies options.transport", matches("""instance\.transport\s*=\s*typeof options\.transport === "function"""", tag))
        assertTrue("transport is not treated as a meta field", tag.contains("key === \"transport\""))
    }

    @Test
    fun vendoredTagCarriesTheExtensionBuildBehaviours() {
        val tag = repoFile("app/src/main/assets/moveo-one.js")
        assertTrue("extension build, not the CDN tag", tag.contains("const LIB_VERSION = \"1.0.22-ext\";"))
        assertTrue("click listener is capture-phase", tag.contains("document.addEventListener(\"click\", handleClick, true);"))
        assertTrue("icon-only controls fall back to aria-label", matches("""if \(text\) return text;.{0,600}getAttribute\("aria-label"\)""", tag))
        assertFalse("no cap on sg rule count", tag.contains("SG_RULES_MAX_RULES"))
        assertFalse("no cap on tracked element count", tag.contains("TRACK_SELECTORS_MAX_RULES"))
    }

    @Test
    fun bootstrapAndNativeSideAgreeOnTheHandshake() {
        val bootstrap = repoFile("app/src/main/assets/moveo-bootstrap.android.js")
        val webView = repoFile("app/src/main/java/one/moveo/studywrapper/browser/StudyWebView.kt")

        assertTrue("bootstrap passes the transport into init", matches("""transport:\s*relayTransport""", bootstrap))

        val literal = checkNotNull(Regex("""RELAYED_KINDS = (\[[^\]]*\])""").find(bootstrap)).groupValues[1]
        val kinds = Json.parseToJsonElement(literal).jsonArray.map { it.jsonPrimitive.content }
        assertEquals("bootstrap and native relay agree on the kinds", TagRelay.kinds, kinds)

        assertTrue("bootstrap posts to the moveoFetch object", bootstrap.contains("window.moveoFetch.postMessage"))
        assertTrue("bootstrap listens for the correlated reply", bootstrap.contains("addEventListener(\"message\", onRelayReply)"))
        assertTrue(
            "native side registers the moveoFetch listener",
            matches("""addWebMessageListener\(\s*webView,\s*"moveoFetch"""", webView),
        )
        assertTrue("native side answers over the reply proxy", webView.contains("replyProxy.postMessage("))
        assertTrue("reply carries the request id", webView.contains(".toMessage(id)"))
    }
}
