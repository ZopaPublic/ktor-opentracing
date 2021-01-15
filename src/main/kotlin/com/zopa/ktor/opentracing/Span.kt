package com.zopa.ktor.opentracing

import io.opentracing.Span

inline fun <T> span(name: String = "defaultSpanName", block: Span.() -> T): T {
    val tracer = getGlobalTracer()
    val span = tracer.buildSpan(name).start()
    try {
        tracer.scopeManager().activate(span).use { scope ->
            return block(span)
        }
    } finally {
        span.finish()
    }
}
