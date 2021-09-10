package com.zopa.ktor.opentracing

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import com.zopa.ktor.opentracing.util.mockTracer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.withTestApplication
import io.opentracing.Span
import io.opentracing.util.GlobalTracer
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import java.util.Stack

@TestInstance(Lifecycle.PER_CLASS)
class OpenTracingClientTest {
    @BeforeEach
    fun setup() {
        mockTracer.reset()
        GlobalTracer.registerIfAbsent(mockTracer)
    }

    @Test
    fun `Client adds child span of server span for nested call`() = withTestApplication {
        val path = "/sqrt"

        application.install(OpenTracingServer)

        val client = HttpClient(MockEngine) {
            install(OpenTracingClient)
            engine {
                addHandler {
                    respond("OK", HttpStatusCode.OK)
                }
            }
        }

        application.routing {
            get(path) {
                val clientResponse = client.get("/member/74c144e6-ec05-49af-b3a2-217e1254897f").bodyAsText()
                call.respond(clientResponse)
            }
        }

        handleRequest(HttpMethod.Get, path) {}.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.OK)

            with(mockTracer.finishedSpans()) {
                assertThat(size).isEqualTo(2)

                assertThat(first().parentId()).isNotEqualTo(last().parentId())
                assertThat(first().operationName()).isEqualTo("Call to GET localhostmember/<UUID>")
                assertThat(first().tags()["http.status_code"]).isEqualTo(200)
                assertThat(first().tags()["UUID"]).isEqualTo("74c144e6-ec05-49af-b3a2-217e1254897f")

                assertThat(last().parentId()).isEqualTo(0L)
                assertThat(last().operationName()).isEqualTo("GET /sqrt")
                assertThat(last().tags()["span.kind"]).isEqualTo("server")
            }
        }
    }

    @Test
    fun `Client passes trace context in headers and tags and tags uuids`() {
        val client = HttpClient(MockEngine) {
            install(OpenTracingClient)
            engine {
                addHandler { request ->
                    val headers = request.headers.entries()
                        .filter { (_, values) -> values.isNotEmpty() }
                        .associate { (key, values) -> key to values.first() }

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
            runBlockingTest {
                withContext(threadLocalSpanStack.asContextElement(spanStack)) {
                    client.request("/4DCA6409-D958-417E-B4DD-20738C721C48/view").bodyAsText()
                }
            }
        }

        with(mockTracer.finishedSpans()) {
            assertThat(size).isEqualTo(1)
            assertThat(first().tags()).contains(Pair("span.kind", "client"))
            assertThat(first().operationName()).isEqualTo("Call to GET localhost<UUID>/view")
            assertThat(first().tags()).contains(Pair("UUID", "4DCA6409-D958-417E-B4DD-20738C721C48"))
        }
    }
}
