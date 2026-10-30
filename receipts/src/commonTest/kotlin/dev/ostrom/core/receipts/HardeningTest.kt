// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.receipts

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class HardeningTest {

    private val envelope = Vectors.read("private/package.json").let { (it as JsonObject).obj("envelope") }
    private val keys = listOf(Vectors.signingKey.publicKey)

    @Test
    fun aRepeatedPartOfTheSignatureFieldIsNotTrusted() {
        // the prototype keeps the last kid; a field naming two keys is not a field anyone wrote
        val field = SignatureField.parse("ed25519;kid=aaaa;kid=bbbb;sha256=00;sig=AAAA")
        assertNull(field.kid)
        assertEquals("ed25519;kid=aaaa", SignatureField.parse("ed25519;kid=aaaa").toString())
    }

    @Test
    fun aReceiptWhoseFieldNamesTwoKeysIsRefused() {
        val receipt = Vectors.receipt("session-simple").obj("receipt")
        val field = receipt.text("signature")
        val twice = field.replace(";sha256=", ";kid=0000000000000000;sha256=")
        val result = ReceiptVerifierImpl(keys).verify(JsonObject(receipt + ("signature" to JsonPrimitive(twice))))
        assertEquals(ReceiptVerificationFailure.UNKNOWN_KEY_ID, assertIs<ReceiptVerification.Failed>(result).reason)
    }

    @Test
    fun aRevokedPackageIsRefused() {
        val revoked = JsonObject(envelope + ("revoked" to JsonPrimitive(true)))
        assertEquals(PackageVerification.Failed("package revoked"), PackageEnvelopes.verify(revoked, keys))
    }

    @Test
    fun anEnvelopeThatDisagreesWithItsSignedPayloadIsRefused() {
        // version, repository and content digest are copied out of the payload: a copy that differs was edited
        val edits = listOf(
            "version" to JsonPrimitive("v999"),
            "contentDigest" to JsonPrimitive("00"),
            "repo" to JsonPrimitive("other/repo"),
        )
        for ((name, value) in edits) {
            val edited = JsonObject(envelope + (name to value))
            assertEquals(
                PackageVerification.Failed("envelope $name does not match the signed payload"),
                PackageEnvelopes.verify(edited, keys),
                name,
            )
        }
        val withoutCopies = JsonObject(envelope - "version" - "contentDigest" - "repo")
        assertIs<PackageVerification.Verified>(PackageEnvelopes.verify(withoutCopies, keys))
    }

    @Test
    fun aSignedPayloadThatIsNotAPackageIsRefused() {
        for (payload in listOf("[]", "not json")) {
            val signature = PackageEnvelopes.sign(payload, Vectors.signingKey)
            val odd = JsonObject(
                envelope - "version" - "contentDigest" - "repo" + mapOf(
                    "payload" to JsonPrimitive(payload),
                    "digest" to JsonPrimitive(signature.digest),
                    "signature" to JsonPrimitive(signature.signature),
                ),
            )
            assertEquals(PackageVerification.Failed("payload is not a package"), PackageEnvelopes.verify(odd, keys))
        }
    }
}
