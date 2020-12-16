package com.zopa.ktor.opentracing

import io.opentracing.Scope
import io.opentracing.ScopeManager
import io.opentracing.Span
import io.opentracing.noop.NoopScopeManager
import java.util.Stack


internal val threadLocalSpanStack = ThreadLocal<Stack<Span>>()

class ThreadContextElementScopeManager: ScopeManager {
    override fun activate(span: Span?): Scope {
        val spanStack = threadLocalSpanStack.get()
        if (spanStack == null) {
            log.error { "spanStack is null" }
            return NoopScopeManager.NoopScope.INSTANCE
        }
        spanStack.push(span)
        return CoroutineThreadLocalScope()
    }

    override fun activeSpan(): Span? {
        val spanStack = threadLocalSpanStack.get() ?: return null
        return if (spanStack.isEmpty()) null else spanStack.peek()
    }
}

internal class CoroutineThreadLocalScope: Scope {
    override fun close() {
        val spanStack = threadLocalSpanStack.get()
        if (spanStack == null) {
            log.error { "spanStack is null" }
            return
        }

        spanStack.pop()
    }
}


