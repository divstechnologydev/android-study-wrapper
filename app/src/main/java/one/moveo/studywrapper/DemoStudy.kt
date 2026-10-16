package one.moveo.studywrapper

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import one.moveo.studycore.ActiveStudy
import one.moveo.studycore.ConfigValidator
import one.moveo.studycore.SiteInput
import one.moveo.studycore.StudyConfig
import one.moveo.studycore.generated.TagEndpoint

/// A complete study that anyone can run without an invitation — the product
/// demo, and the path store review takes when no live study code is to hand
/// (← iOS DemoStudy.swift).
///
/// It is a real run of the real flow (consent → instructions → study browser
/// → finish) against a public website, with one deliberate difference:
/// **nothing is recorded**. The demo never touches the backend — no config
/// fetch, no enrollment, no re-validation — and the tag is never injected
/// into the WebView (`AppViewModel.currentUserScriptSource` returns null for
/// it), so no event can be produced even in principle. Every screen it shows
/// says as much; see `AppViewModel.isDemoCode` for the three interception
/// points.
object DemoStudy {
    /// Reserved code. Typing it is equivalent to tapping "Try a demo study" —
    /// `AppViewModel.activateNow()` intercepts it before any network call —
    /// so the backend must never issue it as a real study code.
    const val CODE = "MOVEODEMO"

    /// Prefilled in the site picker. Our own site, so the demo works out of
    /// the box and the default destination is never a third party's pages.
    const val DEFAULT_SITE = "moveo.one"

    /// Plain text, parsed by `Instructions.parse` exactly like a real study's
    /// — the demo shows the same rendering path, not a special one.
    /// Deliberately site-agnostic: the participant chooses where it runs.
    private val INSTRUCTIONS = """
        This is a demo, so nothing you do is recorded. In a real study the research team writes the tasks here, and they would look something like this:

        - Find the page that explains what this company does
        - Look for pricing
        - Come back to the home page

        When you're done, tap Done in the browser. In a real study that is what tells the research team you've finished.
    """.trimIndent()

    /// The demo on a site the participant chose. Built through
    /// `ConfigValidator` rather than hand-assembled, so a demo config is held
    /// to exactly the contract a config from the backend is — which is also
    /// what keeps a typed hostname from reaching the browser unvalidated.
    /// null ⇒ the input wasn't a usable site.
    ///
    /// The resulting study is scoped to that one origin like any other, so
    /// the study browser stays a one-site browser: `StudyWebView`'s
    /// navigation policy sends tapped off-origin links out to the system
    /// browser.
    fun config(site: SiteInput.Parsed): StudyConfig? {
        val json = buildJsonObject {
            put("schemaVersion", ConfigValidator.SUPPORTED_SCHEMA_VERSION)
            putJsonObject("study") {
                put("id", "demo")
                put("name", "Demo study")
                put("status", "active")
                put("instructions", INSTRUCTIONS)
            }
            putJsonObject("tracking") {
                // Both fields are inert. The demo never injects the tag, so
                // this token never leaves the device and nothing is ever sent
                // to this endpoint; they exist only because the validator
                // requires them of every config.
                put("token", "demo-no-ingestion")
                put("apiUrl", TagEndpoint.apiUrl)
                putJsonArray("origins") { add(site.origin) }
                put("deploymentType", "STATIC_WEBSITE")
            }
            // No lead-in or lead-out: a demo must not open a real survey, and
            // no target action: the participant ends it with Done.
            putJsonObject("flow") {
                // The site box accepts a full URL, so "example.com/pricing"
                // starts the demo on that page — the same field a real study
                // uses.
                site.path?.let { put("prepositionPath", it) }
            }
        }
        // Reachable for a hostname the picker accepted but the schema
        // rejects; the picker validates with the same rule, so null here is
        // belt-and-braces rather than an expected path.
        return ConfigValidator.validate(json).configOrNull
    }

    /// The demo with no picker — the reserved code and the DEBUG auto-code QA
    /// hook. Scripted QA can point it at any site without UI interaction:
    ///   adb shell am start … -e MOVEO_AUTO_CODE MOVEODEMO \
    ///     -e MOVEO_DEMO_SITE example.com/pricing
    fun defaultConfig(site: String? = null): StudyConfig? =
        SiteInput.parse(site?.takeIf { it.isNotEmpty() } ?: DEFAULT_SITE)?.let { config(it) }
}

/// The demo is identified by its reserved code alone — no new persisted
/// field, so records written by earlier builds keep decoding unchanged.
val ActiveStudy.isDemo: Boolean get() = code == DemoStudy.CODE
