package com.zopa.ktor.opentracing

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class UtilsTest {
    @Test
    fun `classAndMethodName returns class and method name for method of a class`() = runBlocking<Unit> {
        var name: String? = null

        class Dog {
            suspend fun bark() {
                name = classAndMethodName(this, object {})
            }
        }

        Dog().bark()

        assertThat(name).isEqualTo("Dog.bark()")
    }

    @Test
    fun `classAndMethodName in init captures class name and does not throw NullPointerException`() {
        var name: String? = null

        class Dog {
            init {
                name = classAndMethodName(this, object {})
            }
        }

        Dog()

        assertThat(name).isEqualTo("Dog.()")
    }

    @Test
    fun `classAndMethodName does not return function name`() = runBlocking<Unit> {
        var name: String? = null

        suspend fun bark() {
            name = classAndMethodName(this, object {})
        }

        bark()

        assertThat(name).isEqualTo("BlockingCoroutine.invokeSuspend()")
    }

    @Test
    fun `classAndMethodName in extension function does not return function name`() = runBlocking<Unit> {
        var name: String? = null

        fun String.toSomethingElse() {
            name = classAndMethodName(this, object {})
        }

        "Hello world".toSomethingElse()

        assertThat(name).isEqualTo("String.invoke()")
    }

    @Test
    fun `toPathAndTags with uuid regex replaces and tags uuids`() {
        val path = "/path/EDF591BA-D318-4F42-8749-4E06F01772BA/view"
        val tagsToMatch = mapOf(uuidTagAndReplace)

        val pathAndTags = path.toPathAndTags(tagsToMatch)

        assertThat(pathAndTags.path).isEqualTo("/path/<UUID>/view")
        assertThat(pathAndTags.tags).isEqualTo(mapOf(Pair("UUID", "EDF591BA-D318-4F42-8749-4E06F01772BA")))
    }

    @Test
    fun `toPathAndTags with uuid regex returns path unchanged and no tags if no uuids present`() {
        val path = "/path/view"
        val tagsToMatch = mapOf(uuidTagAndReplace)

        val pathAndTags = path.toPathAndTags(tagsToMatch)

        assertThat(pathAndTags.path).isEqualTo(path)
        assertThat(pathAndTags.tags).isEqualTo(emptyMap())
    }

    @Test
    fun `toPathAndTags can tag and replace custom strings`() {
        val path = "/path/12345678-1234/"
        val tagsToMatch = mapOf(Pair("customId", """[0-9]{8}-[0-9]{4}""".toRegex()))

        val pathAndTags = path.toPathAndTags(tagsToMatch)

        assertThat(pathAndTags.path).isEqualTo("/path/<customId>/")
        assertThat(pathAndTags.tags).isEqualTo(mapOf(Pair("customId", "12345678-1234")))
    }

    @Test
    fun `toPathAndTags can tag and replace multiple strings`() {
        val path = "/path/12345678-1234/abc123XYZ/hello"
        val tagsToMatch = mapOf(
                Pair("customId", """[0-9]{8}-[0-9]{4}""".toRegex()),
                Pair("otherField", """[a-z]{3}[0-9]{3}[A-Z]{3}""".toRegex())
        )

        val pathAndTags = path.toPathAndTags(tagsToMatch)

        assertThat(pathAndTags.path).isEqualTo("/path/<customId>/<otherField>/hello")
        assertThat(pathAndTags.tags).isEqualTo(mapOf(
                Pair("customId", "12345678-1234"),
                Pair("otherField", "abc123XYZ")
        ))
    }

    @Test
    fun `toPathAndTags can tag and replace multiple occurrences of the same pattern`() {
        val path = "/path/af826428-4fef-4ea1-aa7f-79faba01006d/and/9ec3922f-86bc-44a3-8a0a-b6aa4d13ee0a/4185c0da-6043-46ac-8432-555066f221c6"
        val tagsToMatch = OpenTracingServer.Configuration().toBeTaggedAndReplaced

        val pathAndTags = path.toPathAndTags(tagsToMatch)

        assertThat(pathAndTags.path).isEqualTo("/path/<UUID>/and/<UUID_0>/<UUID_1>")
        assertThat(pathAndTags.tags).isEqualTo(mapOf(
                Pair("UUID", "af826428-4fef-4ea1-aa7f-79faba01006d"),
                Pair("UUID_0", "9ec3922f-86bc-44a3-8a0a-b6aa4d13ee0a"),
                Pair("UUID_1", "4185c0da-6043-46ac-8432-555066f221c6")
        ))
    }

}