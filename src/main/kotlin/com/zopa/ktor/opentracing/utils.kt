package com.zopa.ktor.opentracing

import io.opentracing.Span
import io.opentracing.Tracer
import io.opentracing.noop.NoopTracerFactory
import io.opentracing.util.GlobalTracer
import kotlinx.coroutines.Job
import mu.KotlinLogging
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.coroutines.coroutineContext


val log = KotlinLogging.logger { }

fun getGlobalTracer(): Tracer {
    return GlobalTracer.get()
        ?: NoopTracerFactory.create()
            .also { log.warn("Tracer not registered in GlobalTracer. Using Noop tracer instead.") }
}

internal suspend fun Span.addCleanup() {
    coroutineContext[Job]?.invokeOnCompletion {
        it?.also {
            val errors = StringWriter()
            it.printStackTrace(PrintWriter(errors))
            setTag("error", true)
            log(mapOf("stackTrace" to errors))
        }
        if (it != null) this.finish()
    }
}

fun Span.addConfiguredLambdaTags() {
    OpenTracingServer.config.lambdaTags.forEach {
        try {
            this.setTag(it.first, it.second.invoke())
        } catch (e: Exception) {
            log.warn(e) { "Could not add tag: ${it.first}" }
        }
    }
}

/*
    Helper function to name spans. Should only be used in method of a class as such:
    classAndMethodName(this, object {})
    Note that this function will give unexpected results if used in regular functions, extension functions and init functions. For these spans, it is preferable to define span names explicitly.
*/
fun classAndMethodName(
        currentInstance: Any,
        anonymousObjectCreatedInMethod: Any
): String {
    val className = currentInstance::class.simpleName

    val methodName: String = try {
        anonymousObjectCreatedInMethod.javaClass.enclosingMethod.name
    } catch (e: Exception) {
        ""
    }

    return "$className.$methodName()"
}

