package com.zopa.ktor.opentracing

import io.opentracing.Span

public inline fun <T> span(name: String = "defaultSpanName", block: Span.() -> T): T {
    val tracer = getGlobalTracer()
    val span = tracer.buildSpan(name).start()

    span.addConfiguredLambdaTags()

    try {
        tracer.scopeManager().activate(span).use {
            return block(span)
        }
    } finally {
        span.finish()
    }
}
