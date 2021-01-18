package com.zopa.ktor.opentracing.utils

import com.zopa.ktor.opentracing.ThreadContextElementScopeManager
import io.opentracing.mock.MockTracer

val mockTracer = MockTracer(ThreadContextElementScopeManager())
