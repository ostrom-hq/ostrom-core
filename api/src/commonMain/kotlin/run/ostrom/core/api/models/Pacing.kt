// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** How the capacity controller advises an agent to pace its calls (`capacity.pacing.v1`). */
@OptIn(InternalSerializationApi::class)
@Serializable(with = DirectivePace.Serializer::class)
public enum class DirectivePace(
    /** The JSON value. */
    public val value: String,
) {
    /** Observations are missing; also what a value this version does not know reads as. */
    UNKNOWN("unknown"),
    NORMAL("normal"),
    SLOW_DOWN("slow_down"),
    CAN_INCREASE("can_increase"),
    PAUSE("pause"),
    ;

    /**
     * Reads a value this version does not know as [UNKNOWN]. `unknown` is also a value of the contract (observations
     * are missing), so unlike the other fallback enums it is a known value, written and listed in the descriptor.
     */
    public object Serializer : KSerializer<DirectivePace> {
        override val descriptor: SerialDescriptor = buildSerialDescriptor(
            "run.ostrom.core.api.models.DirectivePace",
            SerialKind.ENUM,
        ) {
            for (entry in DirectivePace.entries) {
                element(entry.value, buildSerialDescriptor("${serialName}.${entry.value}", StructureKind.OBJECT))
            }
        }

        override fun serialize(encoder: Encoder, value: DirectivePace) {
            encoder.encodeString(value.value)
        }

        override fun deserialize(decoder: Decoder): DirectivePace {
            val value = decoder.decodeString()
            return DirectivePace.entries.firstOrNull { it.value == value } ?: UNKNOWN
        }
    }
}

/** The stage of an advice's delivery to an agent. Sent by the CLI, never read from a newer server. */
@Serializable
public enum class DirectiveStage(
    /** The JSON value. */
    public val value: String,
) {
    /** The CLI received the advice. */
    @SerialName("received_by_cli")
    RECEIVED_BY_CLI("received_by_cli"),

    /** The local bridge emitted the advice to the agent's hook. */
    @SerialName("emitted_to_hook")
    EMITTED_TO_HOOK("emitted_to_hook"),
}

/**
 * The capacity controller's advice (`capacity.pacing.v1`). Pacing never caps parallelism: it only spaces the
 * admission of new calls, and the server enforces it whatever the agent does.
 *
 * @property id Changes whenever the advice or its context changes.
 * @property pace The advice's gist.
 * @property maxConcurrent The concurrency limit that applies, 0 for none.
 * @property minIntervalMillis The spacing between two admissions of the caller's calls, in milliseconds.
 * @property message The advice in words, for the agent.
 * @property createdAt ISO time the advice was made.
 * @property expiresAt ISO time it expires.
 */
@Serializable
public data class Directive(
    val id: String,
    val pace: DirectivePace,
    val maxConcurrent: Int,
    val minIntervalMillis: Long,
    val message: String,
    val createdAt: String,
    val expiresAt: String,
)

/**
 * The caller's current advice (`GET` and `POST /api/v1/directives/current`); [directive] is absent when
 * there is none.
 */
@Serializable
public data class DirectiveResponse(val directive: Directive? = null)

/** The body of `POST /api/v1/directives/current`: the agent session whose calls scope the advice. */
@Serializable
public data class DirectiveScope(val sessionId: String)

/** The body of `POST /api/v1/directives/claim`. */
@Serializable
public data class DirectiveClaimRequest(val sessionId: String, val stage: DirectiveStage)

/**
 * A claimed delivery; both fields are absent when nothing new is to be delivered.
 *
 * @property directive The advice to deliver.
 * @property claimId The claim to settle with a [DirectiveReceipt].
 */
@Serializable
public data class DirectiveClaim(val directive: Directive? = null, val claimId: String? = null)

/**
 * The body of `POST /api/v1/directives/receipt`: an advice reached a stage of its delivery.
 *
 * @property id The advice's id.
 * @property stage The stage reached.
 * @property sessionId The session, absent for the member-wide advice.
 * @property claimId The claim it settles.
 */
@Serializable
public data class DirectiveReceipt(
    val id: String,
    val stage: DirectiveStage,
    val sessionId: String? = null,
    val claimId: String? = null,
)

/**
 * The capacity controller's settings (`GET` and `PUT /api/v1/policy/pacing`). On a write, a field omitted keeps its
 * stored value.
 *
 * @property observationOnly The controller keeps forecasting but neither spaces admissions nor sends advice.
 * @property autoPoolSize Size the daily budget from the shared accounts instead of the typed one.
 */
@Serializable
public data class PacingSettings(
    val observationOnly: Boolean? = null,
    val autoPoolSize: Boolean? = null,
)

/**
 * Informational advice, never sent to an agent by itself (`GET /api/v1/directives/preview`).
 *
 * @property settings The settings.
 * @property directive The advice the controller would give the caller now.
 */
@Serializable
public data class PacingPreview(val settings: PacingSettings, val directive: Directive? = null)
