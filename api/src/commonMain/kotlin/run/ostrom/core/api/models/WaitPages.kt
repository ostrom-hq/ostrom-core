// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A page of waits (`GET /api/v1/waits`).
 *
 * @property items The waits of the page.
 * @property next The cursor of the following page, absent on the last one.
 */
@Serializable
public data class WaitPage(
    val items: List<Wait>,
    val next: String? = null,
)

/**
 * An answer to a wait (`POST /api/v1/waits/{id}/answer`).
 *
 * @property action One of the wait's actions.
 * @property values The values of its fields, by field name.
 */
@Serializable
public data class AnswerWaitRequest(
    val action: String,
    val values: Map<String, JsonElement> = emptyMap(),
)
