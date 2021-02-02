package com.zopa.ktor.opentracing

import io.ktor.http.HeadersBuilder
import io.opentracing.propagation.TextMap


internal class RequestBuilderCarrier(private val headerBuilder: HeadersBuilder): TextMap {
    override fun put(key: String?, value: String?) {
        if (key != null && value != null) {
            headerBuilder.append(key, value)
        }
    }

    override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, String>> {
        throw UnsupportedOperationException("carrier is write-only")
    }
}