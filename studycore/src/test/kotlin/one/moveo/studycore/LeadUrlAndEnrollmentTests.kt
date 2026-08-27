package one.moveo.studycore

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Port of the extension's `buildLeadUrl` behavior (via iOS
/// LeadURLAndEnrollmentTests): participant id only on opt-in, transaction id
/// only when the caller passes one (lead-out).
class LeadUrlTests {
    private fun flow(appendParticipantId: Boolean) = StudyConfig.Flow(appendParticipantId = appendParticipantId)

    private var participantReads = 0
    private fun participantId(): String {
        participantReads += 1
        return "p_abc"
    }

    @Test
    fun unchangedWhenNothingToAppend() {
        val url = LeadUrl.build(
            "https://forms.example/exit?src=moveo&x=a+b", flow(appendParticipantId = false),
            participantId = ::participantId,
        )
        assertEquals("not even re-encoded", "https://forms.example/exit?src=moveo&x=a+b", url)
        assertEquals("participant id is not read unless it is appended", 0, participantReads)
    }

    @Test
    fun participantIdOnlyOnOptIn() {
        val url = LeadUrl.build("https://forms.example/exit", flow(appendParticipantId = true), ::participantId)
        assertEquals("https://forms.example/exit?participantId=p_abc", url)
        assertEquals(1, participantReads)
    }

    @Test
    fun transactionIdAppendedWithSameParamNameAsInbound() {
        val url = LeadUrl.build(
            "https://forms.example/exit?src=moveo", flow(appendParticipantId = false),
            participantId = ::participantId, transactionId = "tx_8f31",
        )
        assertEquals("https://forms.example/exit?src=moveo&transaction_id=tx_8f31", url)
        assertEquals(0, participantReads)
    }

    @Test
    fun bothAppended() {
        val url = LeadUrl.build(
            "https://forms.example/exit", flow(appendParticipantId = true),
            participantId = ::participantId, transactionId = "tx_8f31",
        )
        assertEquals("https://forms.example/exit?participantId=p_abc&transaction_id=tx_8f31", url)
    }

    @Test
    fun existingParamsAreReplacedNotDuplicated() {
        // URLSearchParams.set semantics.
        val url = LeadUrl.build(
            "https://forms.example/exit?transaction_id=stale&participantId=old",
            flow(appendParticipantId = true), participantId = ::participantId, transactionId = "tx_new",
        )
        assertEquals("https://forms.example/exit?participantId=p_abc&transaction_id=tx_new", url)
    }

    @Test
    fun malformedRawIsNull() {
        assertNull(LeadUrl.build("http://exa mple/ x", flow(appendParticipantId = false), participantId = { "p" }))
        assertNull("non-http schemes never open as lead sheets", LeadUrl.build("mailto:x@y", flow(appendParticipantId = false), participantId = { "p" }))
    }
}

class EnrollmentIdTests {
    @Test
    fun shapeMatchesExtension() {
        // Extension: "e_" + crypto.randomUUID() — lowercase UUID.
        val id = Enrollment.generateId()
        assertTrue(id.startsWith("e_"))
        val uuid = id.removePrefix("e_")
        assertNotNull(UUID.fromString(uuid))
        assertEquals(uuid, uuid.lowercase())
        assertNotEquals("unique per call", id, Enrollment.generateId())
    }
}
