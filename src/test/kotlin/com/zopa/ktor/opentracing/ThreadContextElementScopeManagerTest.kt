package com.zopa.ktor.opentracing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.zopa.ktor.opentracing.util.mockTracer
import io.opentracing.util.GlobalTracer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ThreadContextElementScopeManagerTest {
    @BeforeEach
    fun setup() {
        mockTracer.reset()
        GlobalTracer.registerIfAbsent(mockTracer)
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
