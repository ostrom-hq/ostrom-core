// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

// Ownership transfers of cloud sessions (`runners.cloud.transfer.v1`, TeamCode's cloud.transfer.v1,
// docs/cloud-sessions.md "Transfer"). States and failure codes stay strings: a newer server may add one.

/** Wire names of transfer states. Clients tolerate unknown values. */
public object CloudTransferStates {
    public const val OFFERED: String = "offered"
    public const val ACCEPTED: String = "accepted"
    public const val COMPLETED: String = "completed"
    public const val DECLINED: String = "declined"
    public const val CANCELLED: String = "cancelled"
    public const val EXPIRED: String = "expired"
    public const val FAILED: String = "failed"

    /** Every state, in the order of a transfer's life. */
    public val all: List<String> = listOf(OFFERED, ACCEPTED, COMPLETED, DECLINED, CANCELLED, EXPIRED, FAILED)
}

/**
 * `POST /api/v1/cloud/sessions/{id}/transfer`, with an `Idempotency-Key`.
 *
 * @property toMemberId The recipient's member id or login.
 * @property resume Resume the session for the recipient once the owner changed.
 */
@Serializable
public data class CreateCloudTransferRequest(
    val toMemberId: String,
    val resume: Boolean = false,
)

/**
 * `POST /api/v1/cloud/transfers/{id}/accept`.
 *
 * @property resume Resume once the session is the recipient's; null keeps what the offer asked for.
 */
@Serializable
public data class AcceptCloudTransferRequest(
    val resume: Boolean? = null,
)

/**
 * A change of owner of a cloud session. An administrator's transfer of their own session starts `accepted`; an
 * owner's offer starts `offered` and waits for the recipient. `accepted` lasts until the session reached its paused
 * boundary and the owner changed (`completed`), or the transfer could not happen (`failed`, `cancelled`).
 *
 * @property initiatedBy The member who asked.
 * @property decidedBy The member who accepted, declined or cancelled.
 * @property expiresAt Offers: the recipient must accept before this time (ISO 8601).
 * @property atEpoch The last execution run for the former owner; later executions run for the recipient.
 * @property failure Why the transfer failed or was cancelled (`recipient_ineligible` codes, `transfer_timeout`,
 *   `session_not_running`, `owner_changed`).
 */
@Serializable
public data class CloudTransfer(
    val id: String,
    val sessionId: String,
    val fromMemberId: String,
    val toMemberId: String,
    val state: String,
    val initiatedBy: String,
    val decidedBy: String? = null,
    val resume: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val expiresAt: String? = null,
    val decidedAt: String? = null,
    val completedAt: String? = null,
    val atEpoch: Long? = null,
    val failure: CloudSessionFailure? = null,
)

/** A page of the caller's transfers, newest first; [next] is the cursor of the following page. */
@Serializable
public data class CloudTransferPage(
    val items: List<CloudTransfer>,
    val next: String? = null,
)

/**
 * A member a session can be given to, as a picker shows them. A listed member can still be refused for a given
 * session (`422 recipient_ineligible`).
 */
@Serializable
public data class CloudTransferRecipient(
    val id: String,
    val login: String,
    val administrator: Boolean,
)

/** `GET /api/v1/cloud/transfer-recipients`, by login, at most 500. */
@Serializable
public data class CloudTransferRecipients(
    val recipients: List<CloudTransferRecipient>,
)
