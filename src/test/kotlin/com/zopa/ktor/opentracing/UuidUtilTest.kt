package com.zopa.ktor.opentracing

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UuidUtilTest {
    @Test
    fun `UuidFromPath returns unchanged path and no uuid if no UUID in path`() {
        val path = "/evidence"

        val pathUuid = path.UuidFromPath()

        assertThat(pathUuid.path).isEqualTo(path)
        assertThat(pathUuid.uuid).isEqualTo(null)
    }

    @Test
    fun `UuidFromPath returns path with UUID and uuid if UUID in path`() {
        val path = "/evidence/AB7AD59A-A0FF-4EB1-90CF-BC6D5C24095F"

        val pathUuid = path.UuidFromPath()

        assertThat(pathUuid.path).isEqualTo("/evidence/<UUID>")
        assertThat(pathUuid.uuid).isEqualTo("AB7AD59A-A0FF-4EB1-90CF-BC6D5C24095F")
    }
}
