package com.zopa.ktor.opentracing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.request.path
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.withTestApplication
import io.opentracing.Span
import io.opentracing.mock.MockTracer
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMapAdapter
import io.opentracing.util.GlobalTracer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import java.util.Stack
import java.util.UUID
import kotlin.math.sqrt

@TestInstance(Lifecycle.PER_CLASS)
class KtorOpenTracingTest {
    val mockTracer = MockTracer(ThreadContextElementScopeManager())

    @BeforeEach
    fun setup() {
        mockTracer.reset()
        GlobalTracer.registerIfAbsent(mockTracer)
    }

    @Test
    fun `Create server span for request without trace context in headers`() = withTestApplication {
        val definedPath = "/greeting/{id}"
        val path = "/greeting/ab7ad59a-a0ff-4eb1-90cf-bc6d5c24095f"

        application.install(OpenTracingServer)

        application.routing {
            get(definedPath) {
                call.parameters.entries()
                call.respond("OK")
            }
            get("/another/path") {
                call.respond("OK")
            }
        }

        handleRequest(HttpMethod.Get, path) {}.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.OK)

            with(mockTracer.finishedSpans()) {
                assertThat(size).isEqualTo(1)
                assertThat(first().parentId()).isEqualTo(0L) // no parent span
                assertThat(first().operationName()).isEqualTo("GET /greeting/{id}")
                assertThat(first().tags().get("id")).isEqualTo("ab7ad59a-a0ff-4eb1-90cf-bc6d5c24095f")
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


    @Test
    fun `Manually instrumented span is child of server span`() = withTestApplication {
        val path = "/sqrt"

        application.install(OpenTracingServer)

        application.routing {
            get(path) {
                fun sqrtOfInt(i: Int): Double = span("sqrtOfInt") {
                    setTag("i", i)
                    return sqrt(i.toDouble())
                }

                suspend fun sqrtOfIntSuspend(i: Int): Double = span {
                    delay(10)
                    return sqrt(i.toDouble())
                }

                val sqrt: Double = sqrtOfInt(2)
                val sqrtSuspend: Double = runBlocking {
                    sqrtOfIntSuspend(10)
                }

                call.respond("Square root of 2: $sqrt, Square root of 10: $sqrtSuspend")
            }
        }

        handleRequest(HttpMethod.Get, path) {}.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.OK)

            with(mockTracer.finishedSpans()) {
                assertThat(size).isEqualTo(3)

                // server span
                assertThat(last().parentId()).isEqualTo(0L)
                assertThat(last().operationName()).isEqualTo("GET /sqrt")
                assertThat(last().tags().get("span.kind")).isEqualTo("server")

                // first child span
                assertThat(first().context().traceId()).isEqualTo(last().context().traceId())
                assertThat(first().parentId()).isEqualTo(last().context().spanId())
                assertThat(first().operationName()).isEqualTo("sqrtOfInt")
                assertThat(first().tags()["i"]).isEqualTo(2)

                // second child span
                assertThat(this[1].context().traceId()).isEqualTo(last().context().traceId())
                assertThat(this[1].parentId()).isEqualTo(last().context().spanId())
                assertThat(this[1].operationName()).isEqualTo("defaultSpanName")
            }
        }
    }


    @Test
    fun `Client adds child span of server span for nested call`() = withTestApplication {
        val path = "/sqrt"

        application.install(OpenTracingServer)

        val client = HttpClient(MockEngine) {
            install(OpenTracingClient) {
                configureRoutesWithParams {
                    get("/member/{memberid}")
                }
            }
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

                assertThat(first().parentId()).isNotEqualTo(last().parentId())
                assertThat(first().operationName()).isEqualTo("Call to GET localhost/member/{memberid}")
                assertThat(first().tags().get("http.status_code")).isEqualTo(200)
                assertThat(first().tags().get("memberid")).isEqualTo("74c144e6-ec05-49af-b3a2-217e1254897f")

                assertThat(last().parentId()).isEqualTo(0L)
                assertThat(last().operationName()).isEqualTo("GET /sqrt")
                assertThat(last().tags().get("span.kind")).isEqualTo("server")
            }
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

        assertDoesNotThrow { runBlocking {
            withContext(threadLocalSpanStack.asContextElement(spanStack)) {
                client.request<String>("/")
            }
        } }
    }

    @Test
    fun `concurrent async spans are children of the same parent span`() = withTestApplication {
        val path = "/greeting"

        application.install(OpenTracingServer)

        fun getDoubleSecondTime(num: Int): Int = span {
            setTag("grandParentSpanName", num)
            return num * 2
        }

        fun getDouble(parentSpanName: Int): Int = span {
            setTag("parentSpanName", parentSpanName)
            return getDoubleSecondTime(parentSpanName)*2
        }

        application.routing {
            get(path) {
                val result: List<Int> = listOf(1, 10).map { id ->
                        asyncTraced {
                            span(id.toString()) {
                                getDouble(id)
                            }
                        }
                    }
                    .awaitAll()



                call.respond(HttpStatusCode.OK, result.toString())
            }
        }

        handleRequest(HttpMethod.Get, path) {}.let { call ->
            assertThat(call.response.content).isEqualTo("[4, 40]")

            with(mockTracer.finishedSpans()) {
                assertThat(size).isEqualTo(7)

                val span1 = this.first { it.operationName() == "1" }
                val span1Child = this.first { it.tags()["parentSpanName"] == 1 }
                val span1GrandChild = this.first { it.tags()["grandParentSpanName"] == 1 }

                val span10 = this.first { it.operationName() == "10" }
                val span10Child = this.first { it.tags()["parentSpanName"] == 10 }
                val span10GrandChild = this.first { it.tags()["grandParentSpanName"] == 10 }

                assertThat(span1Child.parentId()).isEqualTo(span1.context().spanId())
                assertThat(span1GrandChild.parentId()).isEqualTo(span1Child.context().spanId())

                assertThat(span10Child.parentId()).isEqualTo(span10.context().spanId())
                assertThat(span10GrandChild.parentId()).isEqualTo(span10Child.context().spanId())
            }
        }
    }

    @Test
    fun `ThreadContextElementScopeManager creates a new span stack if threadLocalSpanStack is null`() {
        val scopeManager = ThreadContextElementScopeManager()
        threadLocalSpanStack.set(null)

        val span = mockTracer.buildSpan("first-span").start()
        scopeManager.activate(span)

        val spanStack = threadLocalSpanStack.get()
        assertThat(spanStack).isNotNull()
        assertThat(spanStack.size).isEqualTo(1)
        assertThat(spanStack.peek()).isEqualTo(span)
    }
}