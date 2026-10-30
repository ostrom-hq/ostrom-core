package dev.ostrom.core.receipts

import dev.ostrom.core.api.OstromJson
import dev.ostrom.core.api.models.GateRef
import dev.ostrom.core.api.models.PackageEnvelope
import dev.ostrom.core.api.models.SessionPackage
import dev.ostrom.core.api.models.WorkflowStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelContractTest {

    @Test
    fun aSessionPackageDecodesAndEncodesBackToTheSignedPayload() {
        // every field the prototype's compiler writes has its place in the model, with the same null and default rules
        val envelope = Vectors.read("private/package.json").jsonObject.obj("envelope")
        val payload = envelope.text("payload")
        val pkg = OstromJson.decodeFromString(SessionPackage.serializer(), payload)
        assertEquals(payload, CanonicalJson.encode(OstromJson.encodeToJsonElement(SessionPackage.serializer(), pkg)))
        assertEquals(envelope.text("version"), pkg.version)
        assertEquals(envelope.text("contentDigest"), pkg.contentDigest)
    }

    @Test
    fun aPackageEnvelopeDecodesAndEncodesBack() {
        val envelope = Vectors.read("private/package.json").jsonObject.obj("envelope")
        val typed = OstromJson.decodeFromJsonElement(PackageEnvelope.serializer(), envelope)
        assertEquals(CanonicalJson.encode(envelope), CanonicalJson.encode(OstromJson.encodeToJsonElement(PackageEnvelope.serializer(), typed)))
    }

    @Test
    fun theGateOfTheCatalogKeepsItsDigest() {
        // the gate object is hashed inside the gate receipt's preimage: the model must give the same bytes back
        val gate = Vectors.receipt("gate-push-sha").obj("subjectPreimage").obj("gate")
        val typed = OstromJson.decodeFromJsonElement(GateRef.serializer(), gate)
        assertEquals(WorkflowStatus.ACCEPTED, typed.status)
        assertEquals(Digests.digestOf(gate), Digests.digestOf(OstromJson.encodeToJsonElement(GateRef.serializer(), typed)))
    }

    @Test
    fun unknownFieldsFromANewerServerAreIgnored() {
        val gate = Vectors.receipt("gate-push-sha").obj("subjectPreimage").obj("gate")
        val newer = JsonObject(gate + ("addedLater" to Json.parseToJsonElement("{\"x\":1}")))
        assertEquals(OstromJson.decodeFromJsonElement(GateRef.serializer(), gate), OstromJson.decodeFromJsonElement(GateRef.serializer(), newer))
    }
}
