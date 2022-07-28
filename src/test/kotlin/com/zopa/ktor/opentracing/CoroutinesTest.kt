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
import kotlinx.coroutines.awaitAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoroutinesTest {
    @BeforeEach
    fun setup() {
        mockTracer.reset()
        GlobalTracer.registerIfAbsent(mockTracer)
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
            return getDoubleSecondTime(parentSpanName) * 2
        }

        application.routing {
            get(path) {
                val result: List<Int> = listOf(1, 10).map { id ->
                    tracedAsync {
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
}
