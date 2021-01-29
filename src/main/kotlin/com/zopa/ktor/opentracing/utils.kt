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

internal val uuidTagAndReplace = Pair(
        "UUID",
        """\b[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-\b[0-9a-fA-F]{12}\b""".toRegex()
)

internal data class PathAndTags(val path: String, val tags: Map<String, String>)

internal fun String.toPathAndTags(regexReplaces: List<Pair<String, Regex>>): PathAndTags {
    var path = this
    val tags = mutableMapOf<String, String>()

    regexReplaces.forEach { regexReplace ->
        val matches = regexReplace.second.findAll(path)
        var counter = -2
        matches.forEach { match ->
            val tagName = regexReplace.first + if (++counter < 0) "" else "_$counter"
            tags[tagName] = match.value
            path = path.replace(match.value, "<${tagName}>")
        }
    }
    return PathAndTags(path, tags)
}

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

fun Span.addConfiguredTags() {
    SharedConfig.tags.forEach {
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
    Note that this function will give unexpected results if used in functions outside of a class, extension functions
    and init functions. For these spans, it is preferable to define span names explicitly.
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

