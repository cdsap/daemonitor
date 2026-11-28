package io.github.cdsap.daemonitor.json

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull

class JsonTest {
    @Test
    fun `reads exact integers decimals and scientific notation`() {
        val obj = assertIs<JsonObject>(Json.parse("""{
            "large":9007199254740993,"max":9223372036854775807,"min":-9223372036854775808,
            "overflow":9223372036854775808,"fraction":1.5,"scientific":9.007199254740993e15,
            "small":1.25e-3,"quoted":"42","null":null
        }"""))
        assertEquals(9007199254740993L, obj.long("large"))
        assertEquals(Long.MAX_VALUE, obj.long("max"))
        assertEquals(Long.MIN_VALUE, obj.long("min"))
        assertEquals(9007199254740993L, obj.long("scientific"))
        assertEquals(0.00125, obj.number("small"))
        assertEquals(42L, obj.long("quoted"))
        assertNull(obj.long("fraction"))
        assertNull(obj.long("overflow"))
        assertNull(obj.long("null"))
        val roundTrip = assertIs<JsonObject>(Json.parse(obj.stringify()))
        assertEquals(9007199254740993L, roundTrip.long("scientific"))
        assertEquals(obj.stringify(), roundTrip.stringify())
    }

    @Test
    fun `round trips nested containers and every control character under a non default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val text = (0..31).map(Int::toChar).joinToString("") + "\"\\/☺😀"
            val value = jsonObject(text to JsonString(text), "array" to jsonArray(JsonNull, JsonBoolean(true)))
            val encoded = value.stringify()
            assertEquals(false, encoded.any { it.code < 0x20 })
            assertEquals(value, Json.parse(encoded))
            assertEquals("\"\\u0000\\u001f\"", JsonString("\u0000\u001f").stringify())
            assertEquals("☺😀/", assertIs<JsonString>(Json.parse("\"\\u263a\\ud83d\\ude00\\/\"")).value)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `rejects malformed JSON and non finite serialized numbers`() {
        listOf("", "-", "+1", "01", "1.", ".1", "1e", "1e+", "[1,]", "{\"a\":1,}",
            "true false", "\"\\u12\"", "\"\\uZZZZ\"", "\"\\x\"", "\"unterminated", "\"line\nnext\"", "\u00a01",
        ).forEach { input -> assertFails(input) { Json.parse(input) } }
        assertFails { JsonNumber(Double.NaN).stringify() }
        assertFails { JsonNumber(Double.POSITIVE_INFINITY).stringify() }
    }

    @Test
    fun `looks up direct fields without borrowing nested values`() {
        val root = assertIs<JsonObject>(Json.parse("""{"nested":{"pid":99},"pid":42,"lines":["a",null,"b"]}"""))
        assertEquals(42L, root.long("pid"))
        assertEquals(99L, root.obj("nested")?.long("pid"))
        assertEquals(listOf(JsonString("a"), JsonNull, JsonString("b")), root.array("lines")?.values)
    }
}
