// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// The organization's event feed (`events.v1`) and its outgoing webhooks (`webhooks.v1`). Ported from TeamCode
// (docs/cloud-conversations.md "Event feed", docs/webhooks.md, api/models/Webhooks.kt, commit a286c99); times are
// ISO-8601 strings, as everywhere in Ostrom.

/**
 * One event of the organization's feed (`GET /api/v1/events`), and the `data` of a webhook delivery.
 *
 * @property seq The organization's sequence: gap-free, increasing, allocated with the change it reports.
 * @property type The event type, also the SSE `event:` field; an open list, so a client ignores a type it does not
 *   know.
 * @property subject What the event is about (`session`, `wait`, `workflow_run`...).
 * @property subjectId Which one.
 * @property memberId The member the subject belonged to when it happened; null for an event of the whole
 *   organization.
 * @property at ISO instant of the change.
 * @property data The type's facts: identifiers and short labels, never a transcript, a prompt or a secret.
 */
@Serializable
public data class OrgEvent(
    val seq: Long,
    val type: String,
    val subject: String,
    val subjectId: String,
    val memberId: String? = null,
    val at: String,
    val data: Map<String, JsonElement>,
)

/**
 * The data of the feed's `event: gap`: events after the client's cursor were purged (kept 7 days), and the stream
 * goes on at [oldestSeq], so the client reloads what it shows.
 *
 * @property oldestSeq The oldest sequence still kept.
 */
@Serializable
public data class EventGap(val oldestSeq: Long)

/**
 * An outgoing webhook: each event of [types] is POSTed to [url], signed with its secret (see the contract's
 * `Webhook` schema for the request and the retries).
 *
 * @property id Webhook id.
 * @property url The `https` target.
 * @property types The event types it receives, sorted.
 * @property description A label, at most 200 characters.
 * @property enabled Whether it delivers.
 * @property disabledReason `disabled` (paused by a person) or `too_many_failures`; null while enabled.
 * @property createdBy The member who created it.
 * @property createdAt ISO creation.
 * @property updatedAt ISO last change.
 * @property version Sent back by an update, which is refused when it is no longer current.
 * @property consecutiveFailures Final failures in a row since the last success; 20 disable it.
 */
@Serializable
public data class Webhook(
    val id: String,
    val url: String,
    val types: List<String>,
    val description: String,
    val enabled: Boolean,
    val disabledReason: String? = null,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String,
    val version: Int,
    val consecutiveFailures: Int,
)

/**
 * The organization's webhooks, oldest first (at most 20).
 *
 * @property items The webhooks.
 */
@Serializable
public data class WebhookList(val items: List<Webhook>)

/**
 * A webhook and its signing secret, in the answer to its creation or a rotation only.
 *
 * @property webhook The webhook.
 * @property secret `whsec_…`: store it now, it is never readable again. Never log it.
 */
@Serializable
public data class CreatedWebhook(val webhook: Webhook, val secret: String)

/**
 * Adds a webhook.
 *
 * @property url An `https` URL on the public internet, without credentials or fragment.
 * @property types 1 to 64 event types of the feed.
 * @property description At most 200 characters.
 */
@Serializable
public data class CreateWebhookRequest(val url: String, val types: List<String>, val description: String? = null)

/**
 * Changes a webhook.
 *
 * @property version The version being replaced.
 * @property url The target.
 * @property types The event types.
 * @property description The label.
 * @property enabled Null keeps the current state, so editing a paused webhook never enables it again.
 */
@Serializable
public data class UpdateWebhookRequest(
    val version: Int,
    val url: String,
    val types: List<String>,
    val description: String? = null,
    val enabled: Boolean? = null,
)

/**
 * One event for one webhook, and its attempts.
 *
 * @property id Delivery id, also the `Ostrom-Delivery` header.
 * @property eventSeq The event's sequence; null for a test.
 * @property eventType The event type (`webhook.test` for a test).
 * @property state `pending`, `delivered` or `failed`.
 * @property attempts Attempts made.
 * @property nextAttemptAt ISO time of the next attempt, while pending.
 * @property lastStatus The last HTTP status the receiver answered.
 * @property lastError A short code (`timeout`, `connect`, `tls`, `dns`, `host_not_allowed`, `secret_unreadable`,
 *   `expired`), never the receiver's answer.
 * @property createdAt ISO time it was queued.
 * @property deliveredAt ISO time of the 2xx.
 * @property test Whether it is a test, sent once.
 */
@Serializable
public data class WebhookDelivery(
    val id: String,
    val eventSeq: Long? = null,
    val eventType: String,
    val state: String,
    val attempts: Int,
    val nextAttemptAt: String? = null,
    val lastStatus: Int? = null,
    val lastError: String? = null,
    val createdAt: String,
    val deliveredAt: String? = null,
    val test: Boolean,
)

/**
 * A webhook's delivery log, newest first.
 *
 * @property items The deliveries.
 */
@Serializable
public data class WebhookDeliveryList(val items: List<WebhookDelivery>)
