package io.github.cdsap.daemonitor.coreipc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoCoreJsonTest {
    @Test
    fun `extracts arrays containers and objects`() {
        val json = """
            {
              "items": [
                {"name":"first","nested":{"value":1}},
                {"name":"second","message":"escaped \" brace }"}
              ],
              "metadata":{"count":2}
            }
        """.trimIndent()

        val array = GoCoreJson.extractArray(json, "items")
        val objects = GoCoreJson.splitObjects(array.orEmpty())

        assertEquals(2, objects.size)
        assertEquals("first", GoCoreJson.stringField(objects[0], "name"))
        assertEquals("second", GoCoreJson.stringField(objects[1], "name"))
        assertEquals("\"count\":2", GoCoreJson.extractContainer(json, "metadata", '{', '}'))
        assertNull(GoCoreJson.extractArray(json, "missing"))
    }

    @Test
    fun `reads string arrays and decodes supported escapes`() {
        val json = """{"lines":["quote: \"","slash: \\ path","line\nnext","tab\tvalue"]}"""

        assertEquals(
            listOf("quote: \"", "slash: \\ path", "line\nnext", "tab\tvalue"),
            GoCoreJson.extractStringArray(json, "lines"),
        )
        assertNull(GoCoreJson.extractStringArray(json, "missing"))
    }

    @Test
    fun `reads scalar fields without matching key fragments`() {
        val json = """
            {
              "display_pid":999,
              "pid":42,
              "ratio":12.5,
              "negative":-3,
              "enabled":true,
              "disabled":false,
              "nullable":null,
              "message":"line\nquote: \" slash: \\ unicode: \u263a"
            }
        """.trimIndent()

        assertEquals(42L, GoCoreJson.longField(json, "pid"))
        assertEquals(12.5, GoCoreJson.numberField(json, "ratio"))
        assertEquals(-3.0, GoCoreJson.numberField(json, "negative"))
        assertEquals(true, GoCoreJson.booleanField(json, "enabled"))
        assertEquals(false, GoCoreJson.booleanField(json, "disabled"))
        assertNull(GoCoreJson.stringField(json, "nullable"))
        assertNull(GoCoreJson.numberField(json, "nullable"))
        assertEquals("line\nquote: \" slash: \\ unicode: ☺", GoCoreJson.stringField(json, "message"))
    }
}
