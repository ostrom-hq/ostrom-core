// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.receipts

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** What is refused at the edges: malformed fields, keys of the wrong size, envelopes missing a part. */
class RefusalsTest {

    private val key = Vectors.signingKey
    private val keys = listOf(key.publicKey)
    private val envelope = Vectors.read("private/package.json").let { (it as JsonObject).obj("envelope") }

    @Test
    fun aSignatureFieldKeepsOnlyThePartsItHas() {
        val field = SignatureField.parse("ed25519;bare")
        assertNull(field.kid)
        assertNull(field.sha256)
        assertNull(field.signature)
        assertEquals("ed25519", field.toString())
    }

    @Test
    fun aSeedOrASignatureOfTheWrongSizeIsRefused() {
        assertFailsWith<IllegalArgumentException> { ReceiptSigningKey.fromSeed(ByteArray(31)) }
        val bytes = "body".encodeToByteArray()
        assertFalse(key.publicKey.verify(bytes, key.sign(bytes).copyOf(63)))
    }

    @Test
    fun aRawPublicKeyReadsBackWithTheSameKeyId() {
        assertEquals(key.publicKey.kid, ReceiptPublicKey.fromRaw(key.publicKey.toRaw()).kid)
    }

    @Test
    fun aSignatureThatIsNotTextIsAMissingSignature() {
        val receipt = Vectors.receipt("session-simple").obj("receipt")
        for (signature in listOf(JsonObject(emptyMap()), JsonPrimitive(1), JsonNull)) {
            val result = ReceiptVerifierImpl(keys).verify(JsonObject(receipt + ("signature" to signature)))
            val failed = assertIs<ReceiptVerification.Failed>(result)
            assertEquals(ReceiptVerificationFailure.MISSING_SIGNATURE, failed.reason)
            assertFalse(failed.ok)
        }
    }

    @Test
    fun aBodyThatIsAlreadySignedIsNotSignedAgain() {
        val signer = ReceiptSignerImpl(key)
        assertEquals(key.publicKey.kid, signer.publicKey.kid)
        assertFailsWith<IllegalArgumentException> { signer.sign(buildJsonObject { put("signature", "x") }) }
    }

    @Test
    fun anEnvelopeMissingAPartIsRefusedWithThePrototypeReason() {
        val zeroSignature = Base64.encode(ByteArray(64))
        val cases = listOf(
            "not an ostrom.package/1 envelope" to JsonObject(envelope + ("format" to JsonPrimitive("other"))),
            "not an ostrom.package/1 envelope" to JsonObject(envelope + ("format" to JsonObject(emptyMap()))),
            "unknown algorithm" to JsonObject(envelope + ("alg" to JsonPrimitive("rsa"))),
            "missing payload" to JsonObject(envelope - "payload"),
            "missing payload" to JsonObject(envelope + ("payload" to JsonPrimitive(1))),
            "unknown key id" to JsonObject(envelope + ("kid" to JsonPrimitive("0000000000000000"))),
            "bad signature" to JsonObject(envelope - "signature"),
            "bad signature" to JsonObject(envelope + ("signature" to JsonPrimitive("not base64!"))),
            "bad signature" to JsonObject(envelope + ("signature" to JsonPrimitive(zeroSignature))),
        )
        for ((reason, edited) in cases) {
            assertEquals(PackageVerification.Failed(reason), PackageEnvelopes.verify(edited, keys), reason)
        }
    }

    @Test
    fun ruleChangeAndDecisionPreimagesWriteAbsentStatesAsNull() {
        val after = JsonPrimitive("on")
        assertEquals(
            """{"action":"accepted","after":"on","before":null,"ruleId":"r1"}""",
            CanonicalJson.encode(SubjectPreimages.ruleChange("r1", "accepted", null, after)),
        )
        assertEquals(
            """{"action":"edited","after":"on","before":"off","ruleId":"r1"}""",
            CanonicalJson.encode(SubjectPreimages.ruleChange("r1", "edited", JsonPrimitive("off"), after)),
        )
        assertEquals(
            """{"action":"owner","after":null,"before":null,"subject":"w1"}""",
            CanonicalJson.encode(SubjectPreimages.decision("w1", "owner", null, null)),
        )
        assertEquals(
            """{"action":"owner","after":"b","before":"a","subject":"w1"}""",
            CanonicalJson.encode(SubjectPreimages.decision("w1", "owner", JsonPrimitive("a"), JsonPrimitive("b"))),
        )
    }

    @Test
    fun aFirstSegmentWithoutExitCodeHasNoResumeFields() {
        val preimage = SubjectPreimages.session(
            id = "s1",
            repo = null,
            dev = "d",
            agent = "claude",
            harness = null,
            startedAt = "a",
            endedAt = "b",
            gates = emptyList(),
        )
        assertEquals(
            """{"agent":"claude","dev":"d","endedAt":"b","gates":[],"harness":null,"id":"s1","repo":null,""" +
                """"startedAt":"a"}""",
            CanonicalJson.encode(preimage),
        )
    }
}
