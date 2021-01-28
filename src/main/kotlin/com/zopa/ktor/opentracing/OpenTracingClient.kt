package com.zopa.ktor.opentracing

import io.ktor.client.HttpClient
import io.ktor.client.features.HttpClientFeature
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.util.AttributeKey
import io.opentracing.Tracer
import io.opentracing.propagation.Format
import io.opentracing.tag.Tags

class OpenTracingClient(
        val regexToReplaceInPathAndTagSpan: List<Pair<String, Regex>>
) {
    class Configuration {
        val regexToReplaceInPathAndTagSpan: MutableList<Pair<String, Regex>> = mutableListOf(uuidTagAndReplace)

        fun replaceInPathAndTagSpan(tagName: String, regex: Regex) {
            regexToReplaceInPathAndTagSpan.add(tagName to regex)
        }
    }

    companion object : HttpClientFeature<Configuration, OpenTracingClient> {
        override val key: AttributeKey<OpenTracingClient> = AttributeKey("OpenTracingClient")

        override fun prepare(block: Configuration.() -> Unit): OpenTracingClient {
            val config = OpenTracingClient.Configuration().apply(block)
            return OpenTracingClient(config.regexToReplaceInPathAndTagSpan)
        }

        override fun install(feature: OpenTracingClient, scope: HttpClient) {

            val tracer: Tracer = getGlobalTracer()

            scope.sendPipeline.intercept(HttpSendPipeline.State) {
                val spanStack = threadLocalSpanStack.get()
                if (spanStack == null) {
                    log.warn("spanStack is null")
                    return@intercept
                }

                val (path, tagsFromPath) = context.url.encodedPath.toPathAndTags(feature.regexToReplaceInPathAndTagSpan)
                val name = "Call to ${context.method.value} ${context.url.host}$path"

                val spanBuilder = tracer.buildSpan(name)
                tagsFromPath.forEach { tag ->
                    spanBuilder.withTag(tag.key, tag.value)
                }

                if (spanStack.isNotEmpty()) spanBuilder?.asChildOf(spanStack.peek())
                val span = spanBuilder?.start()
                span?.addCleanup()

                Tags.SPAN_KIND.set(span, Tags.SPAN_KIND_CLIENT)
                Tags.HTTP_METHOD.set(span, context.method.value)
                Tags.HTTP_URL.set(span, "${context.url.host}${context.url.encodedPath}")

                spanStack.push(span)
                tracer.inject(span?.context(), Format.Builtin.HTTP_HEADERS, RequestBuilderCarrier(context.headers))
            }

            scope.receivePipeline.intercept(HttpReceivePipeline.State) {
                val spanStack = threadLocalSpanStack.get()
                if (spanStack == null) {
                    log.warn("spanStack is null")
                    return@intercept
                }

                if (spanStack.isEmpty()) {
                    log.error("span could not be found in thread local span context")
                    return@intercept
                }
                val span = spanStack.pop()

                val statusCode = context.response.status
                Tags.HTTP_STATUS.set(span, statusCode.value)
                if (statusCode.value >= 400) span.setTag("error", true)

                span.finish()
            }
        }
    }
}