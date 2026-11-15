// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable
import run.ostrom.core.api.UnknownFallbackEnumSerializer

/** Where the daily budget admission applies comes from (`capacity.poolsize.v1`). */
@Serializable(with = PoolSizeSource.Serializer::class)
public enum class PoolSizeSource(
    /** The JSON value. */
    public val value: String,
) {
    /** The value stored by the sizing job. */
    AUTOMATIC("automatic"),

    /** The organization's typed daily budget. */
    MANUAL("manual"),

    /** A value this version does not know, sent by a newer server; never sent. Read it as [MANUAL]. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<PoolSizeSource>(
        "run.ostrom.core.api.models.PoolSizeSource",
        PoolSizeSource.entries,
        PoolSizeSource.UNKNOWN,
        PoolSizeSource::value,
    )
}

/** Why there is no automatic value, so that the typed budget applies. */
@Serializable(with = PoolSizeReason.Serializer::class)
public enum class PoolSizeReason(
    /** The JSON value. */
    public val value: String,
) {
    /** The organization has no shared account. */
    NO_ACCOUNTS("no_accounts"),

    /** No account has a fresh reading and an estimate. */
    NO_ESTIMATE("no_estimate"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<PoolSizeReason>(
        "run.ostrom.core.api.models.PoolSizeReason",
        PoolSizeReason.entries,
        PoolSizeReason.UNKNOWN,
        PoolSizeReason::value,
    )
}

/** What an account contributes to the pool size. */
@Serializable(with = PoolSizeAccountStatus.Serializer::class)
public enum class PoolSizeAccountStatus(
    /** The JSON value. */
    public val value: String,
) {
    /** The account is in the estimate. */
    COUNTED("counted"),

    /** The account cannot serve. */
    UNAVAILABLE("unavailable"),

    /** The account has no fresh reading of its windows. */
    NO_FRESH_OBSERVATION("no_fresh_observation"),

    /** No window of the account has a learned capacity yet. */
    NO_ESTIMATE("no_estimate"),

    /** A value this version does not know, sent by a newer server; never sent. Read it as not counted. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<PoolSizeAccountStatus>(
        "run.ostrom.core.api.models.PoolSizeAccountStatus",
        PoolSizeAccountStatus.entries,
        PoolSizeAccountStatus.UNKNOWN,
        PoolSizeAccountStatus::value,
    )
}

/**
 * The organization's automatic daily budget and the estimate behind it (`GET /api/v1/pools/size`, owners and
 * admins), in USD millionths at the gateway's prices (API-equivalent).
 *
 * @property enabled `autoPoolSize`.
 * @property manualDailyMicros The typed daily budget, absent when there is none.
 * @property automaticDailyMicros The value the job stored last, absent before its first estimate.
 * @property automaticComputedAt ISO time of that value.
 * @property effectiveDailyMicros The daily budget admission applies now, absent when there is none.
 * @property source Where [effectiveDailyMicros] comes from.
 * @property estimatedDailyMicros What is left of the accounts' capacity today, computed now.
 * @property reason Why there is no automatic value.
 * @property unpricedModels Models used over seven days without a price.
 * @property accounts What each shared account contributes.
 * @property capacityDailyMicros The full daily capacity of the accounts that can serve; a floor when
 *   [capacityUnknownAccounts] is above 0.
 * @property capacityUnknownAccounts Accounts whose capacity is unknown.
 * @property capacityDailyTokens [capacityDailyMicros] in tokens at the seven-day model mix.
 * @property tokensPerMicro Tokens per USD millionth at that mix.
 * @property outsideWeeklyMicros What the weekly windows used outside Ostrom; a floor when [outsideUnknownAccounts] is
 *   above 0.
 * @property outsideUnknownAccounts Accounts whose outside use is unknown.
 * @property models Seven days of the organization's tokens by model.
 */
@Serializable
public data class PoolSize(
    val enabled: Boolean,
    val manualDailyMicros: Long? = null,
    val automaticDailyMicros: Long? = null,
    val automaticComputedAt: String? = null,
    val effectiveDailyMicros: Long? = null,
    val source: PoolSizeSource,
    val estimatedDailyMicros: Long,
    val reason: PoolSizeReason? = null,
    val unpricedModels: List<String>,
    val accounts: List<PoolSizeAccount>,
    val capacityDailyMicros: Long,
    val capacityUnknownAccounts: Int,
    val capacityDailyTokens: Long? = null,
    val tokensPerMicro: Double? = null,
    val outsideWeeklyMicros: Long? = null,
    val outsideUnknownAccounts: Int,
    val models: List<PoolSizeModel>,
)

/**
 * One shared account's part of the pool size.
 *
 * @property accountId The account.
 * @property status What it contributes.
 * @property dailyMicros What is left of its capacity today.
 * @property binding The window that gives the smaller daily figure.
 * @property capacityDailyMicros Its full daily capacity, absent while unknown.
 * @property windows The learned capacity of each window.
 * @property usage Its current windows split between Ostrom and the outside.
 */
@Serializable
public data class PoolSizeAccount(
    val accountId: String,
    val status: PoolSizeAccountStatus,
    val dailyMicros: Long,
    val binding: String? = null,
    val capacityDailyMicros: Long? = null,
    val windows: List<PoolSizeWindowCapacity>,
    val usage: List<PoolSizeWindowUsage>,
)

/** Seven days of one model's tokens and their API-equivalent by token type; costs are absent without a price. */
@Serializable
public data class PoolSizeModel(
    val provider: String,
    val model: String,
    val inputTokens: Long,
    val cacheWriteTokens: Long,
    val cacheReadTokens: Long,
    val outputTokens: Long,
    val inputMicros: Long? = null,
    val cacheWriteMicros: Long? = null,
    val cacheReadMicros: Long? = null,
    val outputMicros: Long? = null,
    val totalMicros: Long? = null,
    val unpricedTokens: Long,
)

/**
 * The learned full capacity of one shared window; the capacities are absent below three samples.
 *
 * @property window The window.
 * @property fullWindowMicros The median of the samples, which the budget uses.
 * @property capacityMicros Their upper quartile, the reported capacity.
 * @property samples How many samples.
 */
@Serializable
public data class PoolSizeWindowCapacity(
    val window: String,
    val fullWindowMicros: Long? = null,
    val capacityMicros: Long? = null,
    val samples: Int,
)

/**
 * One current window split between Ostrom's traffic and use of the subscription outside Ostrom since it opened; the
 * split is absent without a learned capacity.
 */
@Serializable
public data class PoolSizeWindowUsage(
    val window: String,
    val since: String,
    val observedAt: String,
    val resetsAt: String,
    val observedUtilization: Double,
    val ostromMicros: Long? = null,
    val ostromUtilization: Double? = null,
    val outsideUtilization: Double? = null,
    val outsideMicros: Long? = null,
    val remainingMicros: Long? = null,
)
