// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client.contract

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlinx.serialization.serializerOrNull
import run.ostrom.core.api.OstromJson
import run.ostrom.core.client.CloudFileSource
import run.ostrom.core.client.OstromClient
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.jvmErasure

/** One HTTP request a client call made, as the check compares it with the document. */
internal data class RecordedCall(
    val method: String,
    val path: String,
    val query: Set<String>,
    /** The model the call sent as JSON, or null without a JSON body. */
    val requestType: KType?,
    /** The model the call read the answer as, or null when it read no JSON model. */
    var responseType: KType? = null,
)

/**
 * Every public call of [OstromClient] (its own and its subclients'), each made against a scripted server that records
 * its requests and answers with an example of the documented answer. Arguments are synthesized from their types: the
 * check needs no hand-written list of calls, so a new client method is checked as soon as it exists.
 */
internal class ClientCalls(private val document: OpenApiDocument) {

    /** A call of the client: `cloudSessions.create`, its receiver and its function. */
    class Call(val name: String, val receiver: (OstromClient) -> Any, val function: KFunction<*>)

    /** The last failure of each call, which says why a call made no request. */
    val failures: MutableMap<String, Throwable> = mutableMapOf()

    /** Every public function of the client and of its subclients. */
    fun all(): List<Call> {
        val own = OstromClient::class.declaredMemberFunctions
            .filter { it.visibility == KVisibility.PUBLIC && it.name != "close" }
            .map { Call(it.name, { client -> client }, it) }
        val subclients = OstromClient::class.memberProperties
            .filter { it.visibility == KVisibility.PUBLIC }
            .filter { (it.returnType.classifier as? KClass<*>)?.qualifiedName?.startsWith(CLIENT_PACKAGE) == true }
            .flatMap { property ->
                val type = property.returnType.classifier as KClass<*>
                type.declaredMemberFunctions.filter { it.visibility == KVisibility.PUBLIC }.map { function ->
                    Call("${property.name}.${function.name}", { client -> property.get(client)!! }, function)
                }
            }
        return (own + subclients).sortedBy { it.name }
    }

    /**
     * The requests [call] makes: first with every argument given, which reaches every query parameter; when that call
     * refuses its synthesized arguments before any request, again with the optional ones left out.
     */
    fun record(call: Call): List<RecordedCall> = record(call, everyArgument = true).ifEmpty {
        record(call, everyArgument = false)
    }

    private fun record(call: Call, everyArgument: Boolean): List<RecordedCall> {
        val calls = mutableListOf<RecordedCall>()
        val client = OstromClient(
            serverUrl = ORIGIN,
            token = { "synthetic-token" },
            createHttpClient = { configure ->
                HttpClient(engine(calls)) { configure(this) }.also { http -> observe(http, calls) }
            },
        )
        runTest {
            try {
                val result = invoke(call, client, everyArgument)
                // A function that only builds a URL (a browser redirect) is a GET of that URL.
                if (!call.function.isSuspend && result is String && result.startsWith(ORIGIN)) {
                    val url = Url(result)
                    calls += RecordedCall("GET", url.encodedPath, url.parameters.names(), null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The answer was only an example: what the call did with it does not matter, its requests do.
                failures[call.name] = error
            } catch (_: EnoughRequests) {
                // A call that keeps asking (a stream reconnecting) has shown its requests.
            }
        }
        client.close()
        return calls
    }

    private fun engine(calls: MutableList<RecordedCall>) = MockEngine { request ->
        if (calls.size >= MAX_REQUESTS) throw EnoughRequests()
        val json = request.body.contentType?.match(ContentType.Application.Json) == true
        val index = calls.size
        calls += RecordedCall(
            method = request.method.value,
            path = request.url.encodedPath,
            query = request.url.parameters.names(),
            requestType = if (json) request.attributes.getOrNull(BODY_TYPE) else null,
        )
        request.attributes.put(INDEX, index)
        val operation = document.operation(request.method.value, request.url.encodedPath)
        val (status, schema) = operation?.let { document.responseSchemas(it).entries.minByOrNull { (code) -> code } }
            ?.let { (code, schema) -> code.toInt() to schema } ?: (HttpStatusCode.OK.value to null)
        if (schema == null) {
            respond(ByteReadChannel(""), HttpStatusCode.fromValue(status))
        } else {
            respond(
                ByteReadChannel(OstromJson.encodeToString(JsonElement.serializer(), document.example(schema))),
                HttpStatusCode.fromValue(status),
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
    }

    /** Records the type each request body has, and the type each answer is read as. */
    private fun observe(http: HttpClient, calls: List<RecordedCall>) {
        http.requestPipeline.intercept(HttpRequestPipeline.Before) {
            context.bodyType?.kotlinType?.let { context.attributes.put(BODY_TYPE, it) }
        }
        http.responsePipeline.intercept(HttpResponsePipeline.Receive) { container ->
            val index = context.request.attributes.getOrNull(INDEX)
            val type = container.expectedType.kotlinType
            if (index != null && type != null && type.isModel()) calls[index].responseType = type
        }
    }

    private suspend fun invoke(call: Call, client: OstromClient, everyArgument: Boolean): Any? {
        val function = call.function
        val arguments = mutableMapOf<KParameter, Any?>(function.instanceParameter!! to call.receiver(client))
        for (parameter in function.valueParameters) {
            if (parameter.isOptional && !everyArgument) continue
            val omitted = !everyArgument && parameter.type.isMarkedNullable
            arguments[parameter] = if (omitted) null else value(parameter.type)
        }
        return if (function.isSuspend) function.callSuspendBy(arguments) else function.callBy(arguments)
    }

    /** A value of [type], for an argument. */
    private fun value(type: KType): Any? {
        val erasure = type.jvmErasure
        val argument = type.arguments.firstOrNull()?.type
        return when {
            erasure == String::class -> "s1"
            erasure == Int::class -> 1
            erasure == Long::class -> 1L
            erasure == Boolean::class -> true
            erasure == Double::class -> 1.0
            erasure == ByteArray::class -> byteArrayOf(1, 2, 3)
            erasure == CloudFileSource::class -> CloudFileSource.of(byteArrayOf(1, 2, 3))
            erasure == List::class -> listOf(value(argument!!))
            erasure == Set::class -> setOf(value(argument!!))
            erasure == Map::class -> mapOf("k1" to value(type.arguments[1].type!!))
            erasure.java.name.startsWith("kotlin.jvm.functions.Function") -> function(erasure.java)
            else -> {
                val serializer = serializerOrNull(type) ?: error("No value for an argument of type $type")
                OstromJson.decodeFromJsonElement(serializer, json(serializer.descriptor, depth = 0))
            }
        }
    }

    /** A lambda that does nothing and returns `Unit` (a suspend lambda returns it too, without suspending). */
    private fun function(type: Class<*>): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.name) {
                "toString" -> "lambda"
                "hashCode" -> 0
                "equals" -> false
                else -> Unit
            }
        }

    /** A JSON value of [descriptor]: every property, the first enum value, the first variant. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun json(descriptor: SerialDescriptor, depth: Int): JsonElement = when {
        // JsonElement's own descriptor is a sealed one without the variants of a sealed class.
        descriptor.serialName.startsWith("kotlinx.serialization.json.") -> JsonPrimitive("s1")
        descriptor.isInline -> json(descriptor.getElementDescriptor(0), depth + 1)
        else -> when (val kind = descriptor.kind) {
            is PrimitiveKind -> primitive(kind)
            SerialKind.ENUM -> JsonPrimitive(descriptor.getElementName(0))
            StructureKind.LIST -> JsonArray(
                if (depth > MAX_DEPTH) emptyList() else listOf(json(descriptor.getElementDescriptor(0), depth + 1)),
            )
            PolymorphicKind.SEALED -> {
                val variants = descriptor.getElementDescriptor(1)
                val variant = json(variants.getElementDescriptor(0), depth + 1) as JsonObject
                JsonObject(mapOf("type" to JsonPrimitive(variants.getElementName(0))) + variant)
            }
            StructureKind.CLASS, StructureKind.OBJECT -> JsonObject(
                (0 until descriptor.elementsCount)
                    .filter { depth <= MAX_DEPTH || !descriptor.isElementOptional(it) }
                    .associate { index ->
                        descriptor.getElementName(index) to json(descriptor.getElementDescriptor(index), depth + 1)
                    },
            )
            else -> JsonObject(emptyMap())
        }
    }

    private fun primitive(kind: PrimitiveKind): JsonPrimitive = when (kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> JsonPrimitive("s1")
        PrimitiveKind.BOOLEAN -> JsonPrimitive(true)
        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> JsonPrimitive(1.0)
        else -> JsonPrimitive(1)
    }

    private fun KType.isModel(): Boolean {
        val name = (classifier as? KClass<*>)?.qualifiedName ?: return false
        return MODEL_PREFIXES.any { name.startsWith(it) }
    }

    /** Thrown by the scripted server once a call has made enough requests; an [Error], so no retry catches it. */
    private class EnoughRequests : Error("enough requests")

    companion object {
        private const val ORIGIN = "https://ostrom.example"
        private const val CLIENT_PACKAGE = "run.ostrom.core.client."
        private const val MAX_REQUESTS = 6
        private const val MAX_DEPTH = 4
        private val MODEL_PREFIXES = listOf("run.ostrom.", "kotlin.collections.", "kotlinx.serialization.json.")
        private val BODY_TYPE = AttributeKey<KType>("ContractBodyType")
        private val INDEX = AttributeKey<Int>("ContractCallIndex")

        /** The serializer of a recorded type. */
        fun serializerOf(type: KType) = serializer(type)
    }
}
