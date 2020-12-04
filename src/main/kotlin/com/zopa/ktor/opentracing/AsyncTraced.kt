package com.zopa.ktor.opentracing

import io.opentracing.Span
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import java.util.Stack


fun <T> CoroutineScope.asyncTraced(
        block: suspend CoroutineScope.() -> T
): Deferred<T> {
    val activeSpan: Span = threadLocalSpanStack.get()?.peek()
        ?: return async { block() }

    val newStack = Stack<Span>()
    newStack.push(activeSpan)

    return async(threadLocalSpanStack.asContextElement(newStack)) {
        block()
    }
}