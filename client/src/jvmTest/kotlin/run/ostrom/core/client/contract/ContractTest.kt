// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializerOrNull
import run.ostrom.core.api.models.ApiError
import java.io.File
import java.util.zip.ZipFile
import kotlin.reflect.full.starProjectedType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The typed client and the models of `api` follow the OpenAPI document the server generates (ostrom-core
 * docs/decisions/0001-generated-openapi.md): each call is a documented operation (method, path, query parameters), it
 * sends and reads models of the documented schemas, and each model named like a schema has its shape. The document is
 * the `@ostrom-hq/openapi` version of `client/openapi.version`, which CI installs; without it the check is skipped,
 * except where `OSTROM_OPENAPI_REQUIRED=true`.
 */
class ContractTest {

    @Test
    fun everyClientCallIsADocumentedOperationWithItsSchemas() {
        val problems = check?.callProblems ?: return
        assertTrue(problems.isEmpty(), "The client disagrees with the server's document:\n" + problems.lines())
    }

    @Test
    fun everyModelNamedLikeASchemaHasItsShape() {
        val problems = check?.modelProblems ?: return
        assertTrue(problems.isEmpty(), "The models disagree with the server's document:\n" + problems.lines())
    }

    @Test
    fun everyKnownServerGapIsStillAGap() {
        val check = check ?: return
        val stale = (ServerGaps.properties.keys - check.usedGaps) + (ServerGaps.queryParameters.keys - check.usedGaps)
        assertTrue(
            stale.isEmpty(),
            "The server serves these now: remove them from ServerGaps:\n" + stale.sorted().joinToString("\n"),
        )
    }

    /** One run of the check against [document], with the server gaps it met. */
    private class Check(private val document: OpenApiDocument) {
        val usedGaps = mutableSetOf<String>()

        /** Computed once for the three tests: the client calls are made in [calls]. */
        val callProblems: List<String> = calls()
        val modelProblems: List<String> = models()

        /** The differences of every client call. */
        private fun calls(): List<String> {
            val calls = ClientCalls(document)
            return calls.all().flatMap { call ->
                val requests = calls.record(call)
                if (requests.isEmpty()) {
                    listOf("${call.name}: made no request with synthesized arguments (${calls.failures[call.name]})")
                } else {
                    requests.flatMap { request -> differences(call.name, request) }
                }
            }
        }

        /** The differences of every model named like a schema of the document. */
        private fun models(): List<String> = modelSerializers().flatMap { serializer ->
            val name = serializer.descriptor.serialName.substringAfterLast('.')
            if (name !in document.schemas) return@flatMap emptyList()
            val reference = JsonObject(mapOf("\$ref" to JsonPrimitive("#/components/schemas/$name")))
            compare(name, serializer.descriptor, reference)
        }

        private fun differences(name: String, request: RecordedCall): List<String> {
            val at = "$name (${request.method} ${request.path})"
            val operation = document.operation(request.method, request.path)
                ?: return listOf("$at: no such operation in the document")
            val query = (request.query - document.queryParameters(operation))
                .filterNot { parameter -> gap("$name?$parameter", ServerGaps.queryParameters.keys) }
                .map { "$at: query parameter $it is not documented on ${operation.key}" }
            val body = request.requestType?.let { type ->
                val schema = document.requestSchema(operation)
                    ?: return@let listOf("$at: sends a JSON body, ${operation.key} takes none")
                compare("$at request", ClientCalls.serializerOf(type).descriptor, schema)
            }.orEmpty()
            val answer = request.responseType?.let { type ->
                val schemas = document.responseSchemas(operation).values.filterNotNull()
                if (schemas.isEmpty()) return@let listOf("$at: reads a JSON answer, ${operation.key} documents none")
                val descriptor = ClientCalls.serializerOf(type).descriptor
                // A call may read any of its successful answers (a governed write's 200 or 202): one must match.
                schemas.map { compare("$at answer", descriptor, it) }.minBy { it.size }
            }.orEmpty()
            return query + body + answer
        }

        private fun compare(at: String, descriptor: SerialDescriptor, schema: JsonObject): List<String> {
            val comparison = SchemaComparison(document)
            return comparison.differences(at, descriptor, schema).also { usedGaps += comparison.usedGaps }
        }

        private fun gap(key: String, gaps: Set<String>): Boolean = (key in gaps).also { if (it) usedGaps += key }
    }

    private companion object {
        const val MODELS_PACKAGE = "run.ostrom.core.api.models"

        /** One run of the check for the whole class, or null without a document. */
        val check: Check? by lazy { OpenApiDocument.load()?.let(::Check) }

        fun List<String>.lines(): String = joinToString("\n")

        /** The serializers of the top-level classes of `run.ostrom.core.api.models`. */
        fun modelSerializers(): List<KSerializer<*>> {
            val location = File(ApiError::class.java.protectionDomain.codeSource.location.toURI())
            val prefix = MODELS_PACKAGE.replace('.', '/') + "/"
            val entries = if (location.isDirectory) {
                location.walk().filter { it.isFile }.map { it.relativeTo(location).invariantSeparatorsPath }.toList()
            } else {
                ZipFile(location).use { zip -> zip.entries().toList().map { it.name } }
            }
            return entries
                .filter { it.startsWith(prefix) && it.endsWith(".class") }
                .filter { '$' !in it && '/' !in it.removePrefix(prefix) }
                .map { Class.forName(it.removeSuffix(".class").replace('/', '.')).kotlin }
                .filter { it.typeParameters.isEmpty() }
                .mapNotNull { serializerOrNull(it.starProjectedType) }
                .sortedBy { it.descriptor.serialName }
        }
    }
}
