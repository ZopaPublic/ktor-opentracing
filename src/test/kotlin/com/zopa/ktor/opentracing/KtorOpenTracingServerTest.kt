package com.zopa.ktor.opentracing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import com.zopa.ktor.opentracing.util.mockTracer
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.withTestApplication
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMapAdapter
import io.opentracing.util.GlobalTracer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle

@TestInstance(Lifecycle.PER_CLASS)
class KtorOpenTracingServerTest {
    @BeforeEach
    fun setup() {
        mockTracer.reset()
        GlobalTracer.registerIfAbsent(mockTracer)
    }

    @Test
    fun `Create server span for request without trace context in headers`() = withTestApplication {
        val routePath = "/greeting/{id}/{name}"
        val path = "/greeting/ab7ad59a-a0ff-4eb1-90cf-bc6d5c24095f/Ruth"

        application.install(OpenTracingServer)

        application.routing {
            get(routePath) {
                call.respond("OK")
            }
        }

        handleRequest(HttpMethod.Get, path) {}.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.OK)

            with(mockTracer.finishedSpans()) {
                assertThat(size).isEqualTo(1)
                assertThat(first().parentId()).isEqualTo(0L) // no parent span
                assertThat(first().operationName()).isEqualTo("GET /greeting/{id}/{name}")
                assertThat(first().tags().get("id")).isEqualTo("ab7ad59a-a0ff-4eb1-90cf-bc6d5c24095f")
                assertThat(first().tags().get("name")).isEqualTo("Ruth")
                assertThat(first().tags().get("span.kind")).isEqualTo("server")
                assertThat(first().tags().get("http.status_code")).isEqualTo(200)
            }
        }
    }

    @Test
    fun `(Bug) Server span name incorrectly has all occurrence of request param replaced with value`() = withTestApplication {
        val routePath = "/hello/there/{name}"
        val path = "/hello/there/hello"

        application.install(OpenTracingServer)

        application.routing {
            get(routePath) {
                call.respond("OK")
            }
        }

        handleRequest(HttpMethod.Get, path) {}.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.OK)

            with(mockTracer.finishedSpans()) {
                assertThat(size).isEqualTo(1)
                assertThat(first().parentId()).isEqualTo(0L) // no parent span
                assertThat(first().operationName()).isEqualTo("GET /{name}/there/{name}")
                assertThat(first().tags().get("name")).isEqualTo("hello")
                assertThat(first().tags().get("span.kind")).isEqualTo("server")
                assertThat(first().tags().get("http.status_code")).isEqualTo(200)
            }
        }

    }

    @Test
    fun `Server span with error code has error tag`() = withTestApplication {
        val path = "/greeting"

        application.install(OpenTracingServer)

        application.routing {
            get(path) {
                call.respond(HttpStatusCode.Unauthorized)
            }
        }


        handleRequest(HttpMethod.Get, path) {}.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.Unauthorized)

            with(mockTracer.finishedSpans()) {
                assertThat(size).isEqualTo(1)
                assertThat(first().parentId()).isEqualTo(0L) // no parent span
                assertThat(first().operationName()).isEqualTo("GET /greeting")
                assertThat(first().tags().get("span.kind")).isEqualTo("server")
                assertThat(first().tags().get("http.status_code")).isEqualTo(401)
                assertThat(first().tags().get("error")).isEqualTo(true)
            }
        }
    }

    @Test
    fun `Server is tagged as error if status code is over 400`() = withTestApplication {
        val pathBadRequest = "/greetingWithBadRequest"
        val pathNotModified = "/greetingNotModified"

        application.install(OpenTracingServer)

        application.routing {
            get(pathBadRequest) {
                call.respond(HttpStatusCode.BadRequest)
            }

            get(pathNotModified) {
                call.respond(HttpStatusCode.NotModified)
            }
        }

        handleRequest(HttpMethod.Get, pathBadRequest) {}.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.BadRequest)

            with(mockTracer.finishedSpans()) {
                assertThat(first().tags()["http.status_code"]).isEqualTo(400)
                assertThat(first().tags()["error"]).isEqualTo(true)
            }
        }

        mockTracer.reset()

        handleRequest(HttpMethod.Get, pathNotModified) {}.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.NotModified)

            with(mockTracer.finishedSpans()) {
                assertThat(first().tags()["http.status_code"]).isEqualTo(304)
                assertThat(first().tags()["error"]).isEqualTo(null)
            }
        }
    }

    @Test
    fun `Create server span as child of span context in request headers`() = withTestApplication {
        val path = "/greeting"

        application.install(OpenTracingServer)

        application.routing {
            get(path) {
                call.respond("OK")
            }
        }

        val rootSpan = mockTracer.buildSpan("root").start()

        handleRequest(HttpMethod.Get, path) {
            val map = mutableMapOf<String, String>()
            val httpCarrier = TextMapAdapter(map)
            mockTracer.inject(rootSpan.context(), Format.Builtin.HTTP_HEADERS, httpCarrier)
            map.forEach { (header, value) ->
                addHeader(header, value)
            }
        }.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.OK)

            with(mockTracer.finishedSpans()) {
                assertThat(size).isEqualTo(1)
                assertThat(first().parentId()).isNotEqualTo(0L) // has parent span
                assertThat(first().references().first().referenceType).isEqualTo("child_of")
                assertThat(first().operationName()).isEqualTo("GET /greeting")
                assertThat(first().tags().get("span.kind")).isEqualTo("server")
            }
        }
    }
}
