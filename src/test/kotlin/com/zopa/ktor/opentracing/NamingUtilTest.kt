package com.zopa.ktor.opentracing


import kotlinx.coroutines.runBlocking
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test

class TracingUtilTest {
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
}
