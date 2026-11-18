package one.moveo.studycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Deferred setup link (docs/plan.md §a1.5): install referrer → setup link.
class InstallReferrerLinkTests {
    @Test
    fun codeOnly() {
        val link = SetupLink.linkFromInstallReferrer("moveo_code=KTZQ")
        assertEquals("moveoone://config/KTZQ", link)
        assertEquals(SetupLink.Parsed(code = "KTZQ", transactionId = null), SetupLink.parse(link!!))
    }

    @Test
    fun codeAndTransactionId() {
        val link = SetupLink.linkFromInstallReferrer("moveo_code=KTZQ&transaction_id=tx_1")
        assertEquals("moveoone://config/KTZQ?transaction_id=tx_1", link)
        assertEquals(SetupLink.Parsed(code = "KTZQ", transactionId = "tx_1"), SetupLink.parse(link!!))
    }

    @Test
    fun otherParametersAndOrderDoNotMatter() {
        assertEquals(
            "moveoone://config/KTZQ?transaction_id=tx_1",
            SetupLink.linkFromInstallReferrer("utm_source=moveo&transaction_id=tx_1&moveo_code=KTZQ"),
        )
    }

    @Test
    fun organicReferrerIsIgnored() {
        assertNull(SetupLink.linkFromInstallReferrer("utm_source=google-play&utm_medium=organic"))
        assertNull("sideloaded build", SetupLink.linkFromInstallReferrer("utm_source=(not%20set)&utm_medium=(not%20set)"))
        assertNull("id without a code", SetupLink.linkFromInstallReferrer("transaction_id=tx_1"))
    }

    @Test
    fun emptyIsIgnored() {
        assertNull(SetupLink.linkFromInstallReferrer(null))
        assertNull(SetupLink.linkFromInstallReferrer(""))
        assertNull(SetupLink.linkFromInstallReferrer("   "))
        assertNull(SetupLink.linkFromInstallReferrer("moveo_code="))
        assertNull(SetupLink.linkFromInstallReferrer("moveo_code"))
    }

    @Test
    fun malformedEncoding() {
        assertNull("undecodable code", SetupLink.linkFromInstallReferrer("moveo_code=%ZZ"))
        assertEquals(
            "an undecodable id never costs the code",
            "moveoone://config/KTZQ",
            SetupLink.linkFromInstallReferrer("moveo_code=KTZQ&transaction_id=%ZZ"),
        )
    }

    @Test
    fun stillEncodedReferrerIsDecodedOnce() {
        assertEquals(
            "moveoone://config/KTZQ?transaction_id=tx_1",
            SetupLink.linkFromInstallReferrer("moveo_code%3DKTZQ%26transaction_id%3Dtx_1"),
        )
    }

    @Test
    fun valuesCannotReshapeTheLink() {
        // Presence only here — but a hostile value must stay inside its own
        // component so `parse` judges exactly what the referrer carried.
        val link = checkNotNull(SetupLink.linkFromInstallReferrer("moveo_code=AB%2F..%3Fx%3D1%23f&transaction_id=a%26b%3Dc"))
        assertFalse(link.removePrefix("moveoone://config/").substringBefore('?').contains('/'))
        assertNull("not a valid code", SetupLink.parse(link))
        val odd = checkNotNull(SetupLink.linkFromInstallReferrer("moveo_code=test-code-1234&transaction_id=a%26b%3Dc"))
        assertEquals(SetupLink.Parsed(code = "TESTCODE1234", transactionId = null), SetupLink.parse(odd))
        assertTrue(odd.startsWith("moveoone://config/test-code-1234?transaction_id="))
    }
}
