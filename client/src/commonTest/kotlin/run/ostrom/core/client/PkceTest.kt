// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The PKCE pair, against the example of RFC 7636 appendix B. */
class PkceTest {
    // RFC 7636, appendix B: the 32 octets of the example verifier.
    private val rfcOctets = intArrayOf(
        116, 24, 223, 180, 151, 153, 224, 37, 79, 250, 96, 125, 216, 173, 187, 186,
        22, 212, 37, 77, 105, 214, 191, 240, 91, 88, 5, 88, 83, 132, 141, 121,
    ).map { it.toByte() }.toByteArray()

    @Test
    fun thePairOfTheRfcExampleIsReproduced() {
        val asked = mutableListOf<Int>()
        val pkce = Pkce.create { size -> asked += size; rfcOctets }
        assertEquals(listOf(32), asked)
        assertEquals("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", pkce.verifier)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
        assertEquals("S256", Pkce.METHOD)
    }

    @Test
    fun base64UrlHasNoPaddingAndUsesTheUrlAlphabet() {
        assertEquals("", Pkce.base64Url(ByteArray(0)))
        assertEquals("-_8", Pkce.base64Url(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", Pkce.base64Url(ByteArray(16)))
    }

    @Test
    fun aSourceThatGivesTheWrongNumberOfBytesIsRefused() {
        assertFailsWith<IllegalArgumentException> { Pkce.create { ByteArray(16) } }
    }

    @Test
    fun thePairIsNeverPrinted() {
        val pkce = Pkce.create { rfcOctets }
        assertEquals("Pkce([REDACTED])", pkce.toString())
    }
}
