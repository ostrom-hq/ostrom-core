// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.telemetry.analytics

import dev.ostrom.core.telemetry.export.ExportResult
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * Where product events go: a PostHog project (Cloud EU by default).
 *
 * @property host `https://eu.i.posthog.com`, or a self-hosted PostHog.
 * @property projectApiKey The project API key (`phc_…`): a write-only key PostHog designs to be public, still read
 * from configuration rather than written in code.
 */
public class PostHogConfig(public val host: String, public val projectApiKey: String) {
    override fun toString(): String = "PostHogConfig($host)"

    /** Defaults. */
    public companion object {
        /** PostHog Cloud, EU region (data stays in the EU, RGPD). */
        public const val EU_HOST: String = "https://eu.i.posthog.com"
    }
}

/**
 * Encodes a batch for PostHog's `/batch/` endpoint.
 *
 * @param config The project.
 * @param library `ostrom-server` or `ostrom-cli`, sent as `$lib`.
 * @param libraryVersion The build version, sent as `$lib_version`.
 */
public class PostHogEncoder(
    private val config: PostHogConfig,
    private val library: String,
    private val libraryVersion: String,
) {
    /** @return The JSON body as UTF-8 bytes. */
    public fun encode(events: List<ProductEvent>): ByteArray = buildJsonObject {
        put("api_key", config.projectApiKey)
        put(
            "batch",
            buildJsonArray {
                events.forEach { add(event(it)) }
            },
        )
    }.toString().encodeToByteArray()

    private fun event(event: ProductEvent): JsonObject = buildJsonObject {
        put("event", event.name)
        put("distinct_id", event.distinctId)
        put("timestamp", Instant.fromEpochMilliseconds(event.epochMillis).toString())
        putJsonObject("properties") {
            event.properties.forEach { (key, value) -> put(key, value.toJson()) }
            put("\$lib", library)
            put("\$lib_version", libraryVersion)
            // The server never sends the user's IP; the CLI's would only identify its network.
            put("\$ip", JsonNull)
            // And no city or country derived from the request's address.
            put("\$geoip_disable", true)
            event.organizationId?.let { orgId -> putJsonObject("\$groups") { put("organization", orgId) } }
        }
    }

    private fun PropertyValue.toJson(): JsonPrimitive = when (this) {
        is PropertyValue.Text -> JsonPrimitive(value)
        is PropertyValue.Number -> JsonPrimitive(value)
        is PropertyValue.Flag -> JsonPrimitive(value)
    }
}

/**
 * Sends encoded batches to PostHog. Like the OTLP transport, it never throws for a backend or network failure.
 *
 * @param client A client of its own, with a request timeout.
 * @param config The project.
 */
public class PostHogClient(private val client: HttpClient, private val config: PostHogConfig) {
    /** @return The outcome of sending one encoded batch. */
    public suspend fun send(body: ByteArray): ExportResult {
        val status = try {
            client.post("${config.host.trimEnd('/')}/batch/") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.status.value
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Network failure: retryable; the cause is not logged.
            null
        }
        return if (status == null) ExportResult.Retryable() else classify(status)
    }

    private fun classify(status: Int): ExportResult = when {
        status in POSTHOG_RETRYABLE -> ExportResult.Retryable()
        status / STATUS_CLASS == SUCCESS_STATUS_CLASS -> ExportResult.Success
        else -> ExportResult.Rejected(status)
    }
}

private val POSTHOG_RETRYABLE = setOf(429, 500, 502, 503, 504)
private const val STATUS_CLASS = 100
private const val SUCCESS_STATUS_CLASS = 2
