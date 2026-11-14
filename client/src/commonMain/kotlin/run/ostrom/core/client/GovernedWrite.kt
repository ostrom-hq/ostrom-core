// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import run.ostrom.core.api.models.GovernanceConfirmationPending

/**
 * The outcome of a governance write (a workflow created, saved or deleted): applied at once, or held for an owner or
 * an admin to confirm on the console.
 */
public sealed interface GovernedWrite<out T> {
    /**
     * The write was applied (`200`, `201` or `204`).
     *
     * @property value What the server answered: the workflow written, or [Unit] for a deletion.
     */
    public data class Applied<out T>(val value: T) : GovernedWrite<T>

    /**
     * Nothing was written (`202`): a write made with a person's API token opens a confirmation, which an owner or an
     * admin confirms on the console before [GovernanceConfirmationPending.expiresAt].
     *
     * @property confirmation Where and until when it is confirmed.
     */
    public data class Held(val confirmation: GovernanceConfirmationPending) : GovernedWrite<Nothing>
}
