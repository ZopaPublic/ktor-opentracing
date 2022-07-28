package com.zopa.ktor.opentracing

import io.opentracing.Scope
import io.opentracing.ScopeManager
import io.opentracing.Span
import mu.KotlinLogging
import java.util.Stack

private val logger = KotlinLogging.logger {}

internal val threadLocalSpanStack = ThreadLocal<Stack<Span>>()

public class ThreadContextElementScopeManager: ScopeManager {
    override fun activate(span: Span?): Scope {
        var spanStack = threadLocalSpanStack.get()

        if (spanStack == null) {
            logger.info { "Span stack is null, instantiating a new one." }
            spanStack = Stack<Span>()
            threadLocalSpanStack.set(spanStack)
        }

        spanStack.push(span)
        return CoroutineThreadLocalScope()
    }

    override fun activeSpan(): Span? {
        val spanStack = threadLocalSpanStack.get() ?: return null
        return if (spanStack.isNotEmpty()) spanStack.peek() else null
    }
}

internal class CoroutineThreadLocalScope: Scope {
    override fun close() {
        val spanStack = threadLocalSpanStack.get()
        if (spanStack == null) {
            logger.error { "spanStack is null" }
            return
        }

        if (spanStack.isNotEmpty()) {
            spanStack.pop()
        }
    }
}


