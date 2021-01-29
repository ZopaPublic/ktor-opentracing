package com.zopa.ktor.opentracing

import io.opentracing.Span

// cannot be internal see https://youtrack.jetbrains.com/issue/KT-21178 for context
lateinit var tagsToAdd: List<Pair<String, () -> String>>

inline fun <T> span(name: String = "defaultSpanName", block: Span.() -> T): T {
    val tracer = getGlobalTracer()
    val span = tracer.buildSpan(name).start()

    tagsToAdd.forEach {
        try {
            span.setTag(it.first, it.second.invoke())
        } catch (e: Exception) {
            log.warn(e) { "Could not add tag: ${it.first}" }
        }
    }

    try {
        tracer.scopeManager().activate(span).use { scope ->
            return block(span)
        }
    } finally {
        span.finish()
    }
}
