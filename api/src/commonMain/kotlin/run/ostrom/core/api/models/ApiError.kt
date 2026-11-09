package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * The error body of every refusal.
 *
 * @property error What was refused.
 */
@Serializable
public data class ApiError(
    val error: ErrorDetail,
)

/**
 * A refusal: a stable snake_case key and a human message.
 *
 * @property type Stable `snake_case` key the clients switch on (`gate_not_found`); never changes once published.
 * @property message Human message; never a secret, a prompt, a token or a raw upstream body.
 */
@Serializable
public data class ErrorDetail(
    val type: String,
    val message: String,
)
