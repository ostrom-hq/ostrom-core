// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import run.ostrom.core.api.models.PublicKey
import run.ostrom.core.api.models.Receipt
import run.ostrom.core.api.models.ReceiptKeyList
import run.ostrom.core.api.models.ReceiptPage

/** The organization's signed receipts and the keys that sign them. Reached through [OstromClient.receipts]. */
public class ReceiptsClient internal constructor(private val transport: Transport) {
    /**
     * A page of receipts (`GET /api/v1/receipts`).
     *
     * @param before The [ReceiptPage.next] of the previous page; the first page when null.
     * @param limit The page size (1 to 200); the server's default when null.
     */
    public suspend fun list(before: String? = null, limit: Int? = null): ReceiptPage =
        transport.json(HttpMethod.Get, "/api/v1/receipts", query("before" to before, "limit" to limit?.toString()))

    /**
     * A page of receipts as the server wrote them (`GET /api/v1/receipts`), for an offline check with
     * `run.ostrom.core.receipts.ReceiptVerifier`: decoding into [Receipt] drops the fields this version does not know,
     * and a genuine receipt of a newer server would then read as modified.
     *
     * @param before The [RawReceiptPage.next] of the previous page; the first page when null.
     * @param limit The page size (1 to 200); the server's default when null.
     */
    public suspend fun listRaw(before: String? = null, limit: Int? = null): RawReceiptPage =
        transport.json(HttpMethod.Get, "/api/v1/receipts", query("before" to before, "limit" to limit?.toString()))

    /** One receipt as the server wrote it (`GET /api/v1/receipts/{id}`), for the same check as [listRaw]. */
    public suspend fun getRaw(id: String): JsonObject = transport.json(HttpMethod.Get, "/api/v1/receipts/${segment(id)}")

    /** One receipt (`GET /api/v1/receipts/{id}`). */
    public suspend fun get(id: String): Receipt = transport.json(HttpMethod.Get, "/api/v1/receipts/${segment(id)}")

    /** The public keys that sign the instance's receipts (`GET /api/v1/receipts/keys`). */
    public suspend fun keys(): List<PublicKey> =
        transport.json<ReceiptKeyList>(HttpMethod.Get, "/api/v1/receipts/keys").keys
}

/**
 * A page of receipts as the server wrote them, every field kept (see [ReceiptsClient.listRaw]).
 *
 * @property items The receipts of the page, each the JSON object the server sent.
 * @property next The cursor of the following page, absent on the last one.
 */
@Serializable
public data class RawReceiptPage(
    val items: List<JsonObject>,
    val next: String? = null,
)
