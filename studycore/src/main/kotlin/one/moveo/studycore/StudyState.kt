@file:UseSerializers(InstantIso8601Serializer::class)

package one.moveo.studycore

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/// ISO-8601 dates in storage (schema/extension parity — the iOS store uses
/// `.iso8601` the same way).
object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/// The active study + lifecycle flags — mirrors the extension's
/// `chrome.storage.local.activeStudy` record so behavior maps one-to-one.
@Serializable
data class ActiveStudy(
    val code: String,
    val config: StudyConfig,
    val enrolledAt: Instant? = null,
    /// Client-minted `e_<uuid>` sent with the enroll call. Nullable only so
    /// records persisted by pre-enrollment-id builds keep decoding.
    val enrollmentId: String? = null,
    /// Panel-provider id from the setup link (`?transaction_id=…`), echoed on
    /// the lead-out URL. null for code-typed activations and plain links.
    val transactionId: String? = null,
    /// Once per participant per study; never unset by later navigation.
    val targetFired: Boolean = false,
    val targetFiredAt: Instant? = null,
    /// Lead-in opened on the FIRST visit to a tracked origin (not enrollment).
    val leadInShownAt: Instant? = null,
    /// A pending lead-out survives an app kill: due-time is persisted, the
    /// in-memory timer is only the fast path. Shown-at is written AFTER
    /// presentation — a lost lead-out self-heals, a shown one never repeats.
    val leadOutDueAt: Instant? = null,
    val leadOutShownAt: Instant? = null,
)

/// Local copy of the consent acceptance (the authoritative record is
/// server-side via the enroll call).
@Serializable
data class ConsentRecord(
    val code: String,
    val acceptedAt: Instant,
    val textVersion: String,
)

/// Terminal record kept after a study ends (participant completion, kill
/// switch, 404 revocation) so the UI can show a friendly end state; the
/// lead-out fields preserve what the active study already showed (parity
/// with the extension's endedStudy).
@Serializable
data class EndedStudy(
    val code: String,
    val name: String,
    val endedAt: Instant,
    val leadOutUrl: String? = null,
    val leadOutShownAt: Instant? = null,
    /// true when the study disappeared (404 on re-validation) rather than
    /// ending normally — the UI shows a distinct message for that.
    val revoked: Boolean? = null,
    /// true when the participant completed the study themselves (target
    /// reached → lead-out shown, or "Finish study") rather than the study
    /// being ended server-side — the UI says "complete", not "has ended".
    val completed: Boolean? = null,
)
