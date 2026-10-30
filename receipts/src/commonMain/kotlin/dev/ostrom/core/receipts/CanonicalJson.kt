package dev.ostrom.core.receipts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical JSON, format v1: the exact bytes the TypeScript prototype signs (`canonical()` of
 * `mvp/server/src/receipts.ts`), byte for byte.
 *
 * - objects: keys sorted by UTF-16 code units at every level, no whitespace;
 * - arrays: elements in their order;
 * - strings: as ES2019 `JSON.stringify` writes them (short escapes, other controls as lowercase `\u00xx`, lone
 *   surrogates as lowercase `\udxxx`, everything else verbatim);
 * - numbers: as ECMAScript `Number.prototype.toString` writes them (see [JsNumber]);
 * - `true`, `false`, `null` verbatim.
 *
 * A JavaScript `undefined` is a key that is absent from the [JsonObject]; an explicit [JsonNull] is written `null`.
 */
public object CanonicalJson {

    /**
     * Returns the canonical text of [element].
     *
     * @param element The JSON value to canonicalize.
     *
     * @return The canonical text, to be encoded in UTF-8 before hashing or signing.
     *
     * @throws CanonicalJsonException When [element] is nested deeper than [MAX_DEPTH].
     */
    public fun encode(element: JsonElement): String = StringBuilder().also { write(element, it, 0) }.toString()

    /** The deepest nesting accepted: a received document deeper than this is refused, never recursed into. */
    public const val MAX_DEPTH: Int = 64

    private const val HEX_RADIX = 16
    private const val ESCAPE_DIGITS = 4

    /** The short escapes of `JSON.stringify`; any other control character is written `\u00xx`. */
    private val SHORT_ESCAPES = mapOf(
        '"' to "\\\"",
        '\\' to "\\\\",
        '\b' to "\\b",
        '\u000C' to "\\f",
        '\n' to "\\n",
        '\r' to "\\r",
        '\t' to "\\t",
    )

    /**
     * Parses received JSON text, refusing a document nested deeper than [MAX_DEPTH] before any parser recurses into
     * it (the JSON parser recurses once per level: ten thousand `[` overflow a JVM thread's stack).
     *
     * @param text The received text.
     *
     * @return The parsed value.
     *
     * @throws CanonicalJsonException When [text] nests containers deeper than [MAX_DEPTH].
     * @throws SerializationException When [text] is not JSON.
     */
    public fun parse(text: String): JsonElement {
        // a value at depth MAX_DEPTH may itself be a container: encode() keeps the exact bound
        if (containerDepth(text) > MAX_DEPTH + 1) throw CanonicalJsonException()
        return Json.parseToJsonElement(text)
    }

    /** The deepest nesting of `[` and `{` in [text], outside strings. */
    private fun containerDepth(text: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (c in text) {
            when {
                escaped -> escaped = false
                inString -> {
                    escaped = c == '\\'
                    inString = c != '"'
                }
                c == '"' -> inString = true
                c == '[' || c == '{' -> deepest = maxOf(deepest, ++depth)
                c == ']' || c == '}' -> depth--
            }
        }
        return deepest
    }

    /**
     * Returns the UTF-8 bytes of the canonical text of [element]: what is hashed and signed.
     *
     * @param element The JSON value to canonicalize.
     *
     * @return The signed bytes.
     *
     * @throws CanonicalJsonException When [element] is nested deeper than [MAX_DEPTH].
     */
    public fun bytes(element: JsonElement): ByteArray = encode(element).encodeToByteArray()

    private fun write(element: JsonElement, out: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) throw CanonicalJsonException()
        when (element) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                element.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(element.getValue(key), out, depth + 1)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out, depth + 1)
                }
                out.append(']')
            }
            is JsonPrimitive -> when {
                element.isString -> writeString(element.content, out)
                element.content == "true" || element.content == "false" -> out.append(element.content)
                else -> out.append(JsNumber.format(element.content.toDouble()))
            }
        }
    }

    /** Writes [value] as ES2019 `JSON.stringify` does ("well-formed JSON.stringify"). */
    internal fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val escape = SHORT_ESCAPES[c]
            when {
                escape != null -> out.append(escape)
                c < ' ' -> out.append("\\u").append(hex4(c))
                c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    out.append(c).append(value[i + 1])
                    i++
                }
                c.isSurrogate() -> out.append("\\u").append(hex4(c))
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private fun hex4(c: Char): String = c.code.toString(HEX_RADIX).padStart(ESCAPE_DIGITS, '0')
}

/** A JSON value nested deeper than [CanonicalJson.MAX_DEPTH]: no receipt or package of the format is. */
public class CanonicalJsonException : SerializationException("JSON nested deeper than ${CanonicalJson.MAX_DEPTH}")
