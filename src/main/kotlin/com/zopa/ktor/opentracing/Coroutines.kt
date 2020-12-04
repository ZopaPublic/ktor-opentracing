package com.zopa.ktor.opentracing

import io.opentracing.Span
import kotlinx.coroutines.*
import java.util.Stack
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


fun tracingContext(): CoroutineContext {
    val activeSpan: Span? = threadLocalSpanStack.get()?.peek()
    val spanStack = Stack<Span>()
    if (activeSpan != null) {
        spanStack.push(activeSpan)
    }
    return threadLocalSpanStack.asContextElement(spanStack)
}

fun <T> CoroutineScope.asyncTraced(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> T
): Deferred<T> {
    return async(context + tracingContext(), start) {
        block()
    }
}
