package com.zopa.ktor.opentracing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.zopa.ktor.opentracing.util.mockTracer
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.request.path
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.withTestApplication
import io.opentracing.mock.MockTracer
import io.opentracing.util.GlobalTracer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigurationTest {
    @BeforeEach
    fun setup() {
        mockTracer.reset()
        GlobalTracer.registerIfAbsent(mockTracer)
    }

    @Test
    fun `Included tag is added properly to the span`() = withTestApplication {
        val path = "/greeting/ab7ad59a-a0ff-4eb1-90cf-bc6d5c24095f"

        val correlationId = UUID.randomUUID().toString()
        val tagName = "correlationId"

        application.install(OpenTracingServer) {
            addTag(tagName) { correlationId }
        }

        application.routing {
            get(path) {
                fun greeting(): String = span("greeting") {
                    return "hello"
                }

                call.respond(greeting())
            }
        }

        handleRequest(HttpMethod.Get, path) {}.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.OK)
            assertThat(mockTracer.finishedSpans().size).isEqualTo(2)
            assertThat(mockTracer.finishedSpans().first().tags()[tagName]).isEqualTo(correlationId)
            assertThat(mockTracer.finishedSpans().last().tags()[tagName]).isEqualTo(correlationId)
        }
    }

    @Test
    fun `Corrupted tag does not take down span`() = withTestApplication {
        val path = "/greeting/ab7ad59a-a0ff-4eb1-90cf-bc6d5c24095f"
        val tagName = "correlationId"

        application.install(OpenTracingServer) {
            addTag(tagName) { throw Exception("Corrupted") }
        }

        application.routing {
            get(path) {
                fun greeting(): String = span("greeting") {
                    return "hello"
                }

                call.respond(greeting())
            }
        }

        handleRequest(HttpMethod.Get, path) {}.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.OK)
            assertThat(mockTracer.finishedSpans().size).isEqualTo(2)
            assertThat(mockTracer.finishedSpans().first().tags()[tagName]).isNull()
            assertThat(mockTracer.finishedSpans().last().tags()[tagName]).isNull()
        }
    }

    @Test
    fun `Does not create server span when included in filters`() = withTestApplication {
        val path = "/metrics"

        application.install(OpenTracingServer) {
            filter { call -> call.request.path().startsWith("/metrics") }
        }

        application.routing {
            get(path) {
                call.respond(HttpStatusCode.OK)
            }
        }

        handleRequest(HttpMethod.Get, path) {}
            .let { call ->
                assertThat(call.response.status()).isEqualTo(HttpStatusCode.OK)
                assertThat(mockTracer.finishedSpans().size).isEqualTo(0)
            }
    }

    @Test
    fun `Added tag is propagated to the OpenTracingClient`() = withTestApplication {
        val path = "/sqrt"
        val correlationId = UUID.randomUUID().toString()

        application.install(OpenTracingServer) {
            addTag("correlationId") { correlationId }
        }

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
                val clientResponse = client.get<String>("/member/74c144e6-ec05-49af-b3a2-217e1254897f")
                call.respond(clientResponse)
            }
        }

        handleRequest(HttpMethod.Get, path) {}.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.OK)

            with(mockTracer.finishedSpans()) {
                assertThat(size).isEqualTo(2)
                forEach {
                    assertThat(it.tags()["correlationId"]).isEqualTo(correlationId)
                }
            }
        }
    }
}
