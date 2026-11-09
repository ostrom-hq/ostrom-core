package run.ostrom.core.receipts

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CanonicalJsonTest {

    @Test
    fun everyCanonicalCaseOfThePrototypeIsReproducedByteForByte() {
        val cases = Vectors.read("neutral/canonical.json").jsonObject.array("cases")
        assertEquals(12, cases.size)
        for (case in cases.map { it.jsonObject }) {
            val name = case.text("name")
            val text = CanonicalJson.encode(Json.parseToJsonElement(case.text("inputJson")))
            assertEquals(case.text("canonical"), text, name)
            assertEquals(case.text("utf8Hex"), text.encodeToByteArray().toHex(), name)
            assertEquals(case.text("sha256"), Digests.sha256Hex(text.encodeToByteArray()), name)
        }
    }

    @Test
    fun anAbsentKeyIsOmittedAndAnExplicitNullIsWritten() {
        // codeOnly case of the vectors: { a: 1, b: undefined, c: [1, undefined] }
        val value = JsonObject(mapOf("a" to JsonPrimitive(1), "c" to JsonArray(listOf(JsonPrimitive(1), JsonNull))))
        assertEquals("{\"a\":1,\"c\":[1,null]}", CanonicalJson.encode(value))
    }

    @Test
    fun keysSortByUtf16CodeUnitsNotCodePoints() {
        val value = JsonObject(mapOf("｡" to JsonPrimitive(1), "😀" to JsonPrimitive(2)))
        assertTrue(CanonicalJson.encode(value).startsWith("{\"😀\""))
    }

    @Test
    fun numbersAreWrittenAsEcmaScriptWritesThem() {
        // 4 072 doubles written by Node (compat/extra/js-numbers.mjs): fixed edge cases and random bit patterns
        val path = Path(COMPAT_VECTORS_DIR, "../extra/js-numbers.json")
        val pairs = SystemFileSystem.source(path).buffered().use { Json.parseToJsonElement(it.readString()) }.jsonArray
        assertTrue(pairs.size > 4000)
        for (pair in pairs.map { it.jsonArray }) {
            val bits = pair[0].jsonPrimitive.content.toULong(16).toLong()
            val expected = pair[1].jsonPrimitive.content
            assertEquals(expected, JsNumber.format(Double.fromBits(bits)), pair[0].jsonPrimitive.content)
        }
    }

    @Test
    fun numberLiteralsReadFromJsonKeepTheirEcmaScriptText() {
        // the parse step: a receipt's number literal goes through the platform parser before it is written back
        val path = Path(COMPAT_VECTORS_DIR, "../extra/js-numbers.json")
        val pairs = SystemFileSystem.source(path).buffered().use { Json.parseToJsonElement(it.readString()) }.jsonArray
        for (pair in pairs.map { it.jsonArray }) {
            val text = pair[1].jsonPrimitive.content
            assertEquals(text, CanonicalJson.encode(Json.parseToJsonElement(text)), text)
        }
    }

    @Test
    fun aDocumentNestedTooDeepIsRefusedNotRecursedInto() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        assertFailsWith<CanonicalJsonException> { CanonicalJson.parse(deep) }
        val deepObjects = "{\"a\":".repeat(10_000) + "1" + "}".repeat(10_000)
        assertFailsWith<CanonicalJsonException> { CanonicalJson.parse(deepObjects) }
        val shallow = "[".repeat(CanonicalJson.MAX_DEPTH + 1) + "]".repeat(CanonicalJson.MAX_DEPTH + 1)
        assertEquals(shallow, CanonicalJson.encode(CanonicalJson.parse(shallow)))
        var built: JsonElement = JsonArray(emptyList())
        repeat(10_000) { built = JsonArray(listOf(built)) }
        assertFailsWith<CanonicalJsonException> { CanonicalJson.encode(built) }
    }

    @Test
    fun bracketsInsideStringsDoNotCountAsNesting() {
        val text = """{"a":"[[[{{{\\\"]]","b":[{"c":"\\"}]}"""
        assertEquals(text, CanonicalJson.encode(CanonicalJson.parse(text)))
        val inString = "\"" + "[".repeat(10_000) + "\""
        assertEquals(inString, CanonicalJson.encode(CanonicalJson.parse(inString)))
    }

    @Test
    fun parseRefusesTextThatIsNotJson() {
        assertFailsWith<SerializationException> { CanonicalJson.parse("{\"a\":") }
    }

    @Test
    fun negativeZeroNanAndInfinity() {
        assertEquals("0", JsNumber.format(-0.0))
        assertEquals("null", JsNumber.format(Double.NaN))
        assertEquals("null", JsNumber.format(Double.POSITIVE_INFINITY))
    }
}
