package run.ostrom.core.api

import kotlinx.serialization.json.Json

/**
 * The JSON configuration of the API and of session packages, shared by every Ostrom repository.
 *
 * - a field equal to its default is omitted (an optional field left null is absent, as JavaScript's `undefined`);
 * - a nullable field without default is written, `null` included (a package's `repo: null`);
 * - unknown fields are ignored, so an older client reads a newer server.
 *
 * Receipts are signed with their own configuration (`run.ostrom.core.receipts.ReceiptJson`).
 */
public val OstromJson: Json = Json {
    encodeDefaults = false
    explicitNulls = true
    ignoreUnknownKeys = true
}
