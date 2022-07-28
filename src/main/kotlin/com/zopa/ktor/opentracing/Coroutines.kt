package com.zopa.ktor.opentracing

import io.opentracing.Span
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.Stack
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

public fun tracingContext(): CoroutineContext {
    val activeSpan: Span? = getGlobalTracer().scopeManager().activeSpan()

    val spanStack = Stack<Span>()
    if (activeSpan != null) {
        spanStack.push(activeSpan)
    }

    return threadLocalSpanStack.asContextElement(spanStack)
}

public fun CoroutineScope.launchTraced(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit
): Job = launch(context + tracingContext(), start, block)

@Suppress("DeferredIsResult")
@Deprecated("Use tracedAsync instead", ReplaceWith("tracedAsync(context, start, block)"))
public fun <T> CoroutineScope.asyncTraced(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> T
): Deferred<T> = tracedAsync(context, start, block)

public fun <T> CoroutineScope.tracedAsync(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> T
): Deferred<T> = async(context + tracingContext(), start) {
    block()
}
