package com.zopa.ktor.opentracing

import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.ApplicationFeature
import io.ktor.application.call
import io.ktor.http.Headers
import io.ktor.request.httpMethod
import io.ktor.request.path
import io.ktor.routing.HttpMethodRouteSelector
import io.ktor.routing.Routing
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import io.opentracing.Span
import io.opentracing.SpanContext
import io.opentracing.Tracer
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMapAdapter
import io.opentracing.tag.Tags
import io.opentracing.util.GlobalTracer
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.util.Stack


class OpenTracingServer(
        val filters: List<(ApplicationCall) -> Boolean>
) {
    class Configuration {
        val filters = mutableListOf<(ApplicationCall) -> Boolean>()

        fun filter(predicate: (ApplicationCall) -> Boolean) {
            filters.add(predicate)
        }
    }

    companion object Feature : ApplicationFeature<Application, Configuration, OpenTracingServer> {
        override val key = AttributeKey<OpenTracingServer>("OpenTracingServer")

        override fun install(pipeline: Application, configure: Configuration.() -> Unit): OpenTracingServer {
            val config = Configuration().apply(configure)
            val feature = OpenTracingServer(config.filters)

            val tracer: Tracer = getGlobalTracer()

            val tracingPhaseStart = PipelinePhase("OpenTracingStart")
            pipeline.insertPhaseBefore(ApplicationCallPipeline.Features, tracingPhaseStart)

            val tracingPhaseFinish = PipelinePhase("OpenTracingFinish")
            pipeline.insertPhaseAfter(ApplicationCallPipeline.Fallback, tracingPhaseFinish)

            pipeline.intercept(tracingPhaseStart) {
                if (config.filters.any { it(call) }) return@intercept

                val headers: MutableMap<String, String> = call.request.headers.toMap()
                headers.remove("Authorization")

                val clientSpanContext: SpanContext? = tracer.extract(Format.Builtin.HTTP_HEADERS, TextMapAdapter(headers))
                if (clientSpanContext == null) log.info("Tracing context could not be found in request headers. Starting a new server trace.")

                val spanName = "${context.request.httpMethod.value} ${context.request.path()}"

                val spanBuilder = tracer
                    .buildSpan(spanName)
                    .withTag(Tags.SPAN_KIND.key, Tags.SPAN_KIND_SERVER)

                if (clientSpanContext != null) spanBuilder.asChildOf(clientSpanContext)

                val span = spanBuilder.start()
                span.addCleanup()

                val spanStack = Stack<Span>()
                spanStack.push(span)

                withContext(threadLocalSpanStack.asContextElement(spanStack)) {
                    proceed()
                }
            }

            pipeline.environment.monitor.subscribe(Routing.RoutingCallFinished) {call ->
                if (config.filters.any { it(call) }) return@subscribe
                val span = GlobalTracer.get().activeSpan()

                val routePath = call.route.parent.toString()
                val routeMethod = (call.route.selector as HttpMethodRouteSelector).method.value
                val spanName = "$routeMethod $routePath"

                call.parameters.entries().forEach { param ->
                    span?.setTag(param.key, param.value.first())
                }
                span.setOperationName(spanName)
            }

            pipeline.intercept(tracingPhaseFinish) {
                if (config.filters.any { it(call) }) return@intercept

                val spanStack = threadLocalSpanStack.get()
                if (spanStack == null) {
                    log.warn("spanStack is null")
                    return@intercept
                }

                if (spanStack.isEmpty()) {
                    log.error("Active span could not be found in thread local trace context")
                    return@intercept
                }
                val span = spanStack.pop()

                val statusCode = context.response.status()
                Tags.HTTP_STATUS.set(span, statusCode?.value)
                if (statusCode == null || statusCode.value >= 400) {
                    span.setTag("error", true)
                }

                span.finish()
            }

            return feature
        }

        private fun Headers.toMap(): MutableMap<String, String> =
                this.entries()
                    .filter { (_, values) -> values.isNotEmpty() }
                    .map { (key, values) -> key to values.first() }
                    .toMap()
                    .toMutableMap()
    }
}
