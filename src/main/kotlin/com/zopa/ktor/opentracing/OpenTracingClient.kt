package com.zopa.ktor.opentracing

import io.ktor.client.HttpClient
import io.ktor.client.features.HttpClientFeature
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.http.HttpMethod
import io.ktor.util.AttributeKey
import io.opentracing.Tracer
import io.opentracing.propagation.Format
import io.opentracing.tag.Tags


class OpenTracingClient (
        val clientConfig: Configuration
){
    class Configuration {
        var routes = listOf<Route>()

        fun configureRoutesWithParams(block: Routes.() -> Unit) {
            routes = Routes().apply(block).routes
        }

        class Routes {
            val routes = mutableListOf<Route>()

            fun route(path: String, method: HttpMethod) {
                routes.add(Route(method, path))
            }
            fun get(path: String) = route(path, HttpMethod.Get)
            fun post(path: String) = route(path, HttpMethod.Post)
            fun put(path: String) = route(path, HttpMethod.Put)
        }

    }

    companion object : HttpClientFeature<Configuration, OpenTracingClient> {
        override val key: AttributeKey<OpenTracingClient> = AttributeKey("OpenTracingClient")

        override fun prepare(block: Configuration.() -> Unit): OpenTracingClient {
            return OpenTracingClient(Configuration().apply(block))
        }

        override fun install(feature: OpenTracingClient, scope: HttpClient) {

            val tracer: Tracer = getGlobalTracer()

            scope.sendPipeline.intercept(HttpSendPipeline.State) {
                val spanStack = threadLocalSpanStack.get()
                if (spanStack == null) {
                    log.warn("spanStack is null")
                    return@intercept
                }

                val (path, paramTags) = getPathAndTags(feature.clientConfig.routes, context.method, context.url.encodedPath)
                val name = "Call to ${context.method.value} ${context.url.host}$path"

                val spanBuilder = tracer.buildSpan(name)
                if (spanStack.isNotEmpty()) spanBuilder?.asChildOf(spanStack.peek())
                val span = spanBuilder?.start()
                span?.addCleanup()
                span?.addConfiguredLambdaTags()
                paramTags.forEach { span?.setTag(it.tagName, it.tagValue) }

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