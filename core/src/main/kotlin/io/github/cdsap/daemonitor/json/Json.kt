package io.github.cdsap.daemonitor.json

import java.math.BigDecimal
import java.util.Locale

sealed interface JsonValue {
    fun stringify(): String
}

data class JsonObject(val values: Map<String, JsonValue>) : JsonValue {
    override fun stringify(): String =
        values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${JsonString(key).stringify()}:${value.stringify()}"
        }

    fun obj(name: String): JsonObject? = values[name] as? JsonObject
    fun array(name: String): JsonArray? = values[name] as? JsonArray
    fun string(name: String): String? = (values[name] as? JsonString)?.value
    fun number(name: String): Double? = (values[name] as? JsonNumber)?.value?.toDouble()
    fun boolean(name: String): Boolean? = (values[name] as? JsonBoolean)?.value
    /** Returns an exact Long, or null for fractions and values outside the Long range. */
    fun long(name: String): Long? = when (val value = values[name]) {
        is JsonNumber -> value.longOrNull()
        is JsonString -> value.value.toLongOrNull()
        else -> null
    }
}

data class JsonArray(val values: List<JsonValue>) : JsonValue {
    override fun stringify(): String =
        values.joinToString(prefix = "[", postfix = "]") { it.stringify() }
}

data class JsonString(val value: String) : JsonValue {
    override fun stringify(): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) append("\\u%04x".format(Locale.ROOT, ch.code)) else append(ch)
                }
            }
        }
        append('"')
    }
}

data class JsonNumber(val value: Number) : JsonValue {
    override fun stringify(): String {
        require(value !is Double || value.isFinite()) { "Non-finite JSON number" }
        require(value !is Float || value.isFinite()) { "Non-finite JSON number" }
        return value.toString()
    }

    fun longOrNull(): Long? = when (value) {
        is Long -> value
        is Int, is Short, is Byte -> value.toLong()
        else -> try {
            BigDecimal(value.toString()).longValueExact()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }
}

data class JsonBoolean(val value: Boolean) : JsonValue {
    override fun stringify(): String = value.toString()
}

data object JsonNull : JsonValue {
    override fun stringify(): String = "null"
}

fun jsonObject(vararg values: Pair<String, JsonValue?>): JsonObject =
    JsonObject(values.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

fun jsonArray(vararg values: JsonValue): JsonArray = JsonArray(values.toList())

fun jsonStringOrNull(value: String?): JsonValue = value?.let(::JsonString) ?: JsonNull

fun jsonNumberOrNull(value: Number?): JsonValue = value?.let(::JsonNumber) ?: JsonNull

/** Dependency-free recursive-descent JSON parser shared by the application and integrations. */
object Json {
    fun parse(input: String): JsonValue = Parser(input).parse()

    private class Parser(private val input: String) {
        private var index = 0

        fun parse(): JsonValue {
            val value = parseValue()
            skipWhitespace()
            require(index == input.length) { "Unexpected JSON trailing content at $index" }
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            require(index < input.length) { "Unexpected end of JSON" }
            return when (input[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonString(parseString())
                't' -> parseLiteral("true", JsonBoolean(true))
                'f' -> parseLiteral("false", JsonBoolean(false))
                'n' -> parseLiteral("null", JsonNull)
                else -> parseNumber()
            }
        }

        private fun parseObject(): JsonObject {
            expect('{')
            val values = linkedMapOf<String, JsonValue>()
            skipWhitespace()
            if (peek('}')) {
                index++
                return JsonObject(values)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                values[key] = parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        return JsonObject(values)
                    }
                    else -> error("Expected ',' or '}' at $index")
                }
            }
        }

        private fun parseArray(): JsonArray {
            expect('[')
            val values = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek(']')) {
                index++
                return JsonArray(values)
            }
            while (true) {
                values += parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek(']') -> {
                        index++
                        return JsonArray(values)
                    }
                    else -> error("Expected ',' or ']' at $index")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < input.length) {
                val ch = input[index++]
                when (ch) {
                    '"' -> return result.toString()
                    '\\' -> {
                        require(index < input.length) { "Unexpected JSON escape at $index" }
                        result.append(
                            when (val escaped = input[index++]) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> {
                                    require(index + 4 <= input.length) { "Incomplete JSON unicode escape at $index" }
                                    val hex = input.substring(index, index + 4)
                                    require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                        "Invalid JSON unicode escape at $index"
                                    }
                                    index += 4
                                    hex.toInt(16).toChar()
                                }
                                else -> error("Unsupported JSON escape '$escaped' at $index")
                            },
                        )
                    }
                    else -> {
                        require(ch.code >= 0x20) { "Unescaped JSON control character at ${index - 1}" }
                        result.append(ch)
                    }
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseNumber(): JsonNumber {
            val start = index
            if (peek('-')) index++
            if (peek('0')) {
                index++
            } else {
                require(index < input.length && input[index] in '1'..'9') { "Expected JSON number at $index" }
                readDigits()
            }
            if (peek('.')) {
                index++
                requireDigits()
            }
            if (peek('e') || peek('E')) {
                index++
                if (peek('+') || peek('-')) index++
                requireDigits()
            }
            val raw = input.substring(start, index)
            // Keep integral tokens as Longs; decimals and larger integers retain their precision too.
            return JsonNumber(raw.toLongOrNull() ?: BigDecimal(raw))
        }

        private fun readDigits() {
            while (index < input.length && input[index] in '0'..'9') index++
        }

        private fun requireDigits() {
            val start = index
            readDigits()
            require(index > start) { "Expected JSON digit at $index" }
        }

        private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
            require(input.startsWith(literal, index)) { "Expected '$literal' at $index" }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < input.length && input[index] in " \t\r\n") index++
        }

        private fun expect(ch: Char) {
            require(index < input.length && input[index] == ch) { "Expected '$ch' at $index" }
            index++
        }

        private fun peek(ch: Char): Boolean = index < input.length && input[index] == ch
    }
}
