package com.zopa.ktor.opentracing

import com.zopa.ktor.opentracing.utils.mockTracer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.request
import io.ktor.http.HttpStatusCode
import io.opentracing.Span
import io.opentracing.util.GlobalTracer
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Stack

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenTracingClientTest {

    @BeforeEach
    fun setup() {
        mockTracer.reset()
        GlobalTracer.registerIfAbsent(mockTracer)
    }

    @Test
    fun `Client passes trace context in headers`() {
        val client = HttpClient(MockEngine) {
            install(OpenTracingClient)
            engine {
                addHandler { request ->
                    val headers = request.headers.entries()
                            .filter { (_, values) -> values.isNotEmpty() }
                            .map { (key, values) -> key to values.first() }
                            .toMap()

                    if (headers.containsKey("traceid"))
                        respond("OK", HttpStatusCode.OK)
                    else
                        throw IllegalStateException("Unhandled request")
                }
            }
        }

        val span = mockTracer.buildSpan("block-span").start()
        val spanStack = Stack<Span>()
        spanStack.push(span)

        assertDoesNotThrow {
            runBlocking {
                withContext(threadLocalSpanStack.asContextElement(spanStack)) {
                    client.request<String>("/")
                }
            }
        }
    }
}