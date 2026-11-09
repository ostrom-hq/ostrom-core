package run.ostrom.core.receipts

import kotlinx.serialization.json.Json

/**
 * The JSON configuration that turns typed receipts into the objects the prototype signs: a null optional field is
 * omitted (JavaScript's `undefined`), unknown fields of a received receipt are ignored when decoding to the model.
 */
public val ReceiptJson: Json = Json {
    explicitNulls = false
    encodeDefaults = true
    ignoreUnknownKeys = true
}
