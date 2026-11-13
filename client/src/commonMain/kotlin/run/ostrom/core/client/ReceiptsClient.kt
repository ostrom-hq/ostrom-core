// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
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

    /** One receipt (`GET /api/v1/receipts/{id}`). */
    public suspend fun get(id: String): Receipt = transport.json(HttpMethod.Get, "/api/v1/receipts/${segment(id)}")

    /** The public keys that sign the instance's receipts (`GET /api/v1/receipts/keys`). */
    public suspend fun keys(): List<PublicKey> =
        transport.json<ReceiptKeyList>(HttpMethod.Get, "/api/v1/receipts/keys").keys
}
