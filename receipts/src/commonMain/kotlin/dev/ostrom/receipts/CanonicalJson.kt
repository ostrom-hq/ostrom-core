package dev.ostrom.receipts

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
     */
    public fun encode(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    /**
     * Returns the UTF-8 bytes of the canonical text of [element]: what is hashed and signed.
     *
     * @param element The JSON value to canonicalize.
     *
     * @return The signed bytes.
     */
    public fun bytes(element: JsonElement): ByteArray = encode(element).encodeToByteArray()

    private fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                element.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(element.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
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
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
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

    private fun hex4(c: Char): String = c.code.toString(16).padStart(4, '0')
}
