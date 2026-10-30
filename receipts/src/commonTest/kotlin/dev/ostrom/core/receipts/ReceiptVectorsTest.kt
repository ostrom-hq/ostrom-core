package dev.ostrom.core.receipts

import dev.ostrom.core.api.models.Receipt
import dev.ostrom.core.api.models.ReceiptDraft
import dev.ostrom.core.api.models.ReceiptNode
import dev.ostrom.core.api.models.ReceiptSubjectKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReceiptVectorsTest {

    private val verifier = ReceiptVerifierImpl(listOf(Vectors.signingKey.publicKey))
    private val signer = ReceiptSignerImpl(Vectors.signingKey)

    @Test
    fun everyReceiptOfThePrototypeVerifies() {
        assertEquals(9, Vectors.receipts.size)
        for (vector in Vectors.receipts) {
            val name = vector.text("name")
            val receipt = vector.obj("receipt")
            val body = JsonObject(receipt - "signature")
            assertEquals(vector.text("canonical"), CanonicalJson.encode(body), name)
            assertEquals(vector.text("signedBytesBase64"), Base64.encode(CanonicalJson.bytes(body)), name)
            val result = verifier.verify(receipt)
            assertEquals(ReceiptVerification.Verified(vector.text("sha256"), vector.text("kid")), result, name)
        }
    }

    @Test
    fun signingTheSameBodyAgainGivesTheSameSignature() {
        // Ed25519 is deterministic: the Kotlin signer must produce the prototype's exact field
        for (vector in Vectors.receipts) {
            val receipt = vector.obj("receipt")
            val signed = signer.sign(JsonObject(receipt - "signature"))
            assertEquals(vector.text("signatureField"), signed.text("signature"), vector.text("name"))
        }
    }

    @Test
    fun theSubjectDigestIsTheDigestOfItsPreimage() {
        for (vector in Vectors.receipts.filter { "subjectPreimage" in it }) {
            val preimage = vector.obj("subjectPreimage")
            assertEquals(vector.text("subjectPreimageCanonical"), CanonicalJson.encode(preimage), vector.text("name"))
            assertEquals(vector.obj("receipt").obj("subject").text("digest"), Digests.digestOf(preimage), vector.text("name"))
        }
    }

    @Test
    fun aModifiedReceiptIsRefusedWithThePrototypeReasons() {
        val receipt = Vectors.receipt("session-simple").obj("receipt")
        val modified = JsonObject(receipt + ("initiator" to JsonPrimitive("someone-else")))
        assertEquals(ReceiptVerificationFailure.DIGEST_MISMATCH, assertIs<ReceiptVerification.Failed>(verifier.verify(modified)).reason)
        val stranger = ReceiptVerifierImpl(listOf(ReceiptSigningKey.generate().publicKey))
        assertEquals(ReceiptVerificationFailure.UNKNOWN_KEY_ID, assertIs<ReceiptVerification.Failed>(stranger.verify(receipt)).reason)
        val unsigned = JsonObject(receipt - "signature")
        assertEquals(ReceiptVerificationFailure.MISSING_SIGNATURE, assertIs<ReceiptVerification.Failed>(verifier.verify(unsigned)).reason)
        val field = receipt.text("signature")
        val forged = JsonObject(receipt + ("signature" to JsonPrimitive(field.substringBefore(";sig=") + ";sig=" + Base64.encode(ByteArray(64)))))
        assertEquals(ReceiptVerificationFailure.BAD_SIGNATURE, assertIs<ReceiptVerification.Failed>(verifier.verify(forged)).reason)
        val notBase64 = JsonObject(receipt + ("signature" to JsonPrimitive(field.substringBefore(";sig=") + ";sig=***")))
        assertEquals(ReceiptVerificationFailure.BAD_SIGNATURE, assertIs<ReceiptVerification.Failed>(verifier.verify(notBase64)).reason)
        val otherAlgorithm = JsonObject(receipt + ("signature" to JsonPrimitive("rsa;" + field.substringAfter(";"))))
        assertEquals(ReceiptVerificationFailure.UNKNOWN_ALGORITHM, assertIs<ReceiptVerification.Failed>(verifier.verify(otherAlgorithm)).reason)
        var deep: kotlinx.serialization.json.JsonElement = JsonPrimitive(1)
        repeat(CanonicalJson.MAX_DEPTH + 1) { deep = kotlinx.serialization.json.JsonArray(listOf(deep)) }
        val tooDeep = JsonObject(receipt + ("extra" to deep))
        assertEquals(ReceiptVerificationFailure.MALFORMED, assertIs<ReceiptVerification.Failed>(verifier.verify(tooDeep)).reason)
    }

    @Test
    fun typedReceiptsEncodeBackToTheSignedObject() {
        // the model is the contract: every field the prototype signs survives a decode and an encode
        for (vector in Vectors.receipts.filter { it.text("name") != "odd-values" }) {
            val typed = ReceiptJson.decodeFromJsonElement(Receipt.serializer(), vector.obj("receipt"))
            val encoded = ReceiptJson.encodeToJsonElement(Receipt.serializer(), typed) as JsonObject
            assertEquals(CanonicalJson.encode(vector.obj("receipt")), CanonicalJson.encode(encoded), vector.text("name"))
            assertTrue(verifier.verify(encoded).ok, vector.text("name"))
        }
    }

    @Test
    fun aTypedDraftSignsToTheSameReceiptAsThePrototype() {
        val vector = Vectors.receipt("cost-double")
        val receipt = ReceiptJson.decodeFromJsonElement(Receipt.serializer(), vector.obj("receipt"))
        assertEquals(0.30000000000000004, receipt.costUsd)
        val draft = ReceiptDraft(
            id = receipt.id, subject = receipt.subject, initiator = receipt.initiator, agent = receipt.agent,
            model = receipt.model, rules = receipt.rules, decisions = receipt.decisions, costUsd = receipt.costUsd,
            packageRef = receipt.packageRef, nodes = receipt.nodes, createdAt = receipt.createdAt,
        )
        assertEquals(vector.text("signatureField"), signer.sign(draft).signature)
    }

    @Test
    fun sessionPreimagesAreBuiltAsThePrototypeBuildsThem() {
        for (name in listOf("session-simple", "session-resume-superseded", "session-resume-segment-2", "session-wake-lost", "session-wake-reopened", "session-with-gate")) {
            val vector = Vectors.receipt(name)
            val p = vector.obj("subjectPreimage")
            fun opt(key: String) = p[key]?.jsonPrimitive?.takeIf { it.isString }?.content
            val built = SubjectPreimages.session(
                id = p.text("id"), repo = opt("repo"), dev = p.text("dev"), agent = p.text("agent"), harness = opt("harness"),
                startedAt = p.text("startedAt"), endedAt = p.text("endedAt"),
                gates = p.array("gates").map { it.jsonObject }.map {
                    SubjectPreimages.SessionGate(it.text("id"), it.text("class"), it.text("mode"), it.text("status"), it["receiptId"]?.jsonPrimitive?.takeIf { r -> r.isString }?.content)
                },
                resume = opt("resumedFrom")?.let { SubjectPreimages.Resume(it, opt("previousReceiptId"), p["segment"]?.jsonPrimitive?.intOrNull) },
                exitCode = p["exitCode"]?.jsonPrimitive?.intOrNull,
            )
            assertEquals(vector.obj("receipt").obj("subject").text("digest"), Digests.digestOf(built), name)
        }
    }

    @Test
    fun aPushGateIsBoundToItsCommit() {
        val vector = Vectors.receipt("gate-push-sha")
        val p = vector.obj("subjectPreimage")
        val pushJson = p.obj("push")
        val push = SubjectPreimages.Push(pushJson.text("remote"), pushJson.array("refs").map { it.jsonObject.text("ref") to it.jsonObject.text("sha") })
        val built = SubjectPreimages.gate(
            gate = p.getValue("gate"), cls = p.text("class"), command = p.text("command"), repo = p.text("repo"),
            sessionId = p.text("sessionId"), ci = null, shown = p.text("shown"), push = push,
        )
        assertEquals(vector.obj("receipt").obj("subject").text("digest"), Digests.digestOf(built))
        val actionKey = vector.obj("actionKey")
        val key = SubjectPreimages.pushActionKey("acme-api", "acme/acme-api", "push-main", push, "session:sess-ship")
        assertEquals(actionKey.text("canonical"), CanonicalJson.encode(key))
        assertEquals(actionKey.text("digest"), Digests.digestOf(key))
        val otherCommit = SubjectPreimages.Push(push.remote, listOf("main" to "0".repeat(40)))
        assertTrue(Digests.digestOf(SubjectPreimages.pushActionKey("acme-api", "acme/acme-api", "push-main", otherCommit, "session:sess-ship")) != actionKey.text("digest"))
    }

    @Test
    fun theWorkflowReceiptItsBundleAndThePackageVerify() {
        val vector = Vectors.read("private/workflow-receipt.json").jsonObject
        val entry = vector.obj("receipt")
        val receipt = entry.obj("receipt")
        assertTrue(verifier.verify(receipt).ok)
        assertEquals(entry.text("signatureField"), signer.sign(JsonObject(receipt - "signature")).text("signature"))
        val typed = ReceiptJson.decodeFromJsonElement(Receipt.serializer(), receipt)
        assertEquals(ReceiptSubjectKind.WORKFLOW, typed.subject.kind)
        val nodes = ReceiptJson.decodeFromJsonElement(ListSerializer(ReceiptNode.serializer()), receipt.getValue("nodes"))
        val session = typed.subject.id.substringBeforeLast('@')
        val preimage = SubjectPreimages.workflow(session, assertNotNull(typed.packageRef).digest, nodes)
        assertEquals(typed.subject.digest, Digests.digestOf(preimage))
        val bundle = vector.obj("bundle")
        for (chained in bundle.array("chain")) assertTrue(verifier.verify(chained.jsonObject).ok)
        assertIs<PackageVerification.Verified>(PackageEnvelopes.verify(bundle.obj("package"), listOf(Vectors.signingKey.publicKey)))
    }

    @Test
    fun thePackageEnvelopeVerifiesOnItsExactBytesAndSignsTheSame() {
        val vector = Vectors.read("private/package.json").jsonObject
        val envelope = vector.obj("envelope")
        val keys = listOf(Vectors.signingKey.publicKey)
        assertEquals(PackageVerification.Verified(envelope.text("payload"), envelope.text("kid")), PackageEnvelopes.verify(envelope, keys))
        val signature = PackageEnvelopes.sign(envelope.text("payload"), Vectors.signingKey)
        assertEquals(envelope.text("digest"), signature.digest)
        assertEquals(envelope.text("signature"), signature.signature)
        val tampered = JsonObject(envelope + ("payload" to JsonPrimitive(envelope.text("payload") + " ")))
        assertEquals(PackageVerification.Failed("digest mismatch (package was modified)"), PackageEnvelopes.verify(tampered, keys))
        val stranger = listOf(ReceiptSigningKey.generate().publicKey)
        assertEquals(PackageVerification.Failed("unknown key id"), PackageEnvelopes.verify(envelope, stranger))
        assertTrue(verifier.verify(vector.obj("harnessReceipt").let { it["receipt"]?.jsonObject ?: it }).ok)
    }
}
