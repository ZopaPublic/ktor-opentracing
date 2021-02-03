package com.zopa.ktor.opentracing

import io.ktor.http.HttpMethod
import io.ktor.routing.RoutingPath
import io.ktor.routing.RoutingPathSegment
import io.ktor.routing.RoutingPathSegmentKind

class Route(
        val method: HttpMethod,
        val path: String
) {
    val segments: List<RoutingPathSegment> = RoutingPath.parse(path).parts

    fun getParamTagsIfMatch(call: Route): List<Tag> {
        if (this.method != call.method) return emptyList()
        if (call.segments.map { it.kind }.contains(RoutingPathSegmentKind.Parameter)) return emptyList()
        if (call.segments.size != segments.size) return emptyList()

        val iterator = segments.zip(call.segments).iterator()
        val tags = mutableListOf<Tag>()

        while (iterator.hasNext()) {
            val tmp = iterator.next()
            val routeSegment = tmp.first
            val callSegmentValue = tmp.second.value

            if (routeSegment.kind == RoutingPathSegmentKind.Constant && routeSegment.value != callSegmentValue) return emptyList()
            if (routeSegment.kind == RoutingPathSegmentKind.Parameter)
                tags.add(Tag(routeSegment.value.drop(1).dropLast(1), callSegmentValue))

        }
        return tags
    }

//    class RouteMatchResult(val result: Boolean, val tags: List<Tag> = emptyList())
}
