package com.zopa.ktor.opentracing

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.zopa.ktor.opentracing.util.mockTracer
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.withTestApplication
import io.opentracing.util.GlobalTracer
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.sqrt

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpanTest {
    @BeforeEach
    fun setup() {
        mockTracer.reset()
        GlobalTracer.registerIfAbsent(mockTracer)
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
                runBlockingTest {
                    val sqrtSuspend: Double = sqrtOfIntSuspend(10)

                    call.respond("Square root of 2: $sqrt, Square root of 10: $sqrtSuspend")
                }

            }
        }

        handleRequest(HttpMethod.Get, path) {}.let { call ->
            assertThat(call.response.status()).isEqualTo(HttpStatusCode.OK)

            with(mockTracer.finishedSpans()) {
                assertThat(size).isEqualTo(3)

                // server span
                assertThat(last().parentId()).isEqualTo(0L)
                assertThat(last().operationName()).isEqualTo("GET /sqrt")
                assertThat(last().tags()["span.kind"]).isEqualTo("server")

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
}
