package io.ktor.features

import io.ktor.application.*
import io.ktor.pipeline.*
import io.ktor.util.*
import org.slf4j.*
import java.util.*
import kotlin.reflect.jvm.*

/**
 * A function that retrieves or generates call id using provided call
 */
typealias CallIdProvider = (call: ApplicationCall) -> String?

/**
 * A call id that is retrieved or generated by [CallId] feature or `null` (this is possible if there is no
 * call id provided and no generators configured or [CallId] feature is not installed)
 */
val ApplicationCall.callId: String? get() = attributes.getOrNull(CallId.callIdKey)

/**
 * Retrieves and generates if necessary a call id. A call id (or correlation id) could be retrieved_ from a call
 * via [CallId.Configuration.retrieve] function. Multiple retrieve functions could be configured that will be invoked
 * one by one until one of them return non-null value. If no value has been provided by retrievers then a generator
 * could be applied to generate a new call id. Generators could be provided via [CallId.Configuration.generate] function.
 * Similar to retrieve, multiple generators could be configured so they will be invoked one by one.
 * Usually call id is passed via [io.ktor.http.HttpHeaders.XRequestId] so
 * one could use [CallId.Configuration.header] function to retrieve call id from a header.
 *
 * Once a call id is retrieved or generated, it could be accessed via [ApplicationCall.callId] otherwise it will be
 * always `null`.
 *
 * [CallId] feature will be installed to [CallId.phase] into [ApplicationCallPipeline].
 */
class CallId private constructor(
        private val providers: Array<CallIdProvider>
) {
    /**
     * [CallId] feature's configuration
     */
    class Configuration {
        internal val retrievers = ArrayList<CallIdProvider>()
        internal val generators = ArrayList<CallIdProvider>()

        /**
         * [block] will be used to retrieve call id from a call. It should return `null` if no call id found in request
         */
        fun retrieve(block: CallIdProvider) {
            retrievers.add(block)
        }

        /**
         * [block] function will be applied when there is no call id retrieved. It should generate a string to be used
         * as call id or `null` if it is impossible to generate call id for some reason
         */
        fun generate(block: CallIdProvider) {
            generators.add(block)
        }

        /**
         * Fetch call id from a request header named [headerName] that is treated as optional
         */
        fun header(headerName: String) {
            retrieve { it.request.headers[headerName] }
        }
    }

    /**
     * Installable feature for [CallId]
     */
    companion object Feature : ApplicationFeature<ApplicationCallPipeline, CallId.Configuration, CallId> {
        /**
         * [ApplicationCallPipeline]'s phase which this feature will be installed to
         */
        val phase: PipelinePhase = PipelinePhase("CallId")

        internal val callIdKey = AttributeKey<String>("ExtractedCallId")

        override val key: AttributeKey<CallId> = AttributeKey("CallId")
        private val logger by lazy { LoggerFactory.getLogger(CallId::class.jvmName) }

        override fun install(pipeline: ApplicationCallPipeline, configure: Configuration.() -> Unit): CallId {
            val configuration = Configuration().apply(configure)

            val instance = CallId(
                    providers = (configuration.retrievers + configuration.generators).toTypedArray()
            )

            pipeline.insertPhaseBefore(ApplicationCallPipeline.Infrastructure, phase)

            if (instance.providers.isEmpty()) {
                logger.warn("CallId feature is not configured: neither retrievers nor generators were configured")
                return instance // don't install interceptor
            }

            pipeline.intercept(phase) {
                val call = call
                val providers = instance.providers

                for (i in 0 until providers.size) {
                    val callId = providers[i](call) ?: continue
                    call.attributes.put(callIdKey, callId)
                    break
                }
            }

            return instance
        }
    }
}

/**
 * The default call id's generator dictionary
 */
const val CALL_ID_DEFAULT_DICTIONARY: String = "abcdefghijklmnopqrstuvwxyz0123456789+/=-"

/**
 * Generates fixed [length] call ids using the specified [dictionary]
 * @param length of call ids to be generated, should be positive
 * @param dictionary to be used to generate ids, shouldn't be empty and it shouldn't contain duplicates
 */
fun CallId.Configuration.generate(length: Int = 64, dictionary: String = CALL_ID_DEFAULT_DICTIONARY) {
    require(length >= 1) { "Call id should be at least one characters length: $length" }
    require(dictionary.length > 1) { "Dictionary should consist of several different characters" }

    val dictionaryCharacters = dictionary.toCharArray().distinct().toCharArray()

    require(dictionaryCharacters.size == dictionary.length) {
        "Dictionary should not contain duplicates, found: ${dictionary.duplicates()}"
    }

    generate { Random().nextString(length, dictionaryCharacters) }
}

private fun String.duplicates() = toCharArray().groupBy { it }.filterValues { it.size > 1 }.keys.sorted()
private fun Random.nextString(length: Int, dictionary: CharArray): String {
    val chars = CharArray(length)
    val dictionarySize = dictionary.size

    for (index in 0 until length) {
        chars[index] = dictionary[nextInt(dictionarySize)]
    }

    return String(chars)
}