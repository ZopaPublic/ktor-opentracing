package com.zopa.ktor.opentracing

import io.opentracing.Span
import io.opentracing.Tracer
import io.opentracing.noop.NoopTracerFactory
import io.opentracing.util.GlobalTracer
import kotlinx.coroutines.Job
import mu.KotlinLogging
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Exception
import kotlin.coroutines.coroutineContext


val log = KotlinLogging.logger { }

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

data class PathUuid(val path: String, val uuid: String?)
fun String.UuidFromPath(): PathUuid {
    val match = """\b[0-9a-f]{8}\b-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-\b[0-9a-f]{12}\b""".toRegex().find(this)

    if (match == null)
        return PathUuid(this, null)
    else {
        val uuid = match.value
        val pathWithReplacement = this.replace(uuid, "<UUID>")
        return PathUuid(pathWithReplacement, uuid)
    }
}

fun getGlobalTracer(): Tracer {
    return GlobalTracer.get()
            ?: NoopTracerFactory.create()
                    .also { log.warn("Tracer not registered in GlobalTracer. Using Noop tracer instead.") }
}

suspend fun Span.addCleanup() {
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

