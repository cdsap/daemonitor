package io.github.cdsap.daemonitor.mcp

internal sealed interface JsonValue {
    fun stringify(): String
}

internal data class JsonObject(val values: Map<String, JsonValue>) : JsonValue {
    override fun stringify(): String =
        values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${JsonString(key).stringify()}:${value.stringify()}"
        }

    fun obj(name: String): JsonObject? = values[name] as? JsonObject
    fun array(name: String): JsonArray? = values[name] as? JsonArray
    fun string(name: String): String? = (values[name] as? JsonString)?.value
    fun long(name: String): Long? = when (val value = values[name]) {
        is JsonNumber -> value.value.toLong()
        is JsonString -> value.value.toLongOrNull()
        else -> null
    }
}

internal data class JsonArray(val values: List<JsonValue>) : JsonValue {
    override fun stringify(): String =
        values.joinToString(prefix = "[", postfix = "]") { it.stringify() }
}

internal data class JsonString(val value: String) : JsonValue {
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
                    if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }
        append('"')
    }
}

internal data class JsonNumber(val value: Number) : JsonValue {
    override fun stringify(): String = value.toString()
}

internal data class JsonBoolean(val value: Boolean) : JsonValue {
    override fun stringify(): String = value.toString()
}

internal data object JsonNull : JsonValue {
    override fun stringify(): String = "null"
}

internal fun jsonObject(vararg values: Pair<String, JsonValue?>): JsonObject =
    JsonObject(values.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

internal fun jsonArray(vararg values: JsonValue): JsonArray = JsonArray(values.toList())

internal fun jsonStringOrNull(value: String?): JsonValue = value?.let(::JsonString) ?: JsonNull

internal fun jsonNumberOrNull(value: Number?): JsonValue = value?.let(::JsonNumber) ?: JsonNull

internal object Json {
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
                                    val hex = input.substring(index, index + 4)
                                    index += 4
                                    hex.toInt(16).toChar()
                                }
                                else -> error("Unsupported JSON escape '$escaped' at $index")
                            },
                        )
                    }
                    else -> result.append(ch)
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseNumber(): JsonNumber {
            val start = index
            if (peek('-')) index++
            while (index < input.length && input[index].isDigit()) index++
            if (peek('.')) {
                index++
                while (index < input.length && input[index].isDigit()) index++
            }
            if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
                index++
                if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
                while (index < input.length && input[index].isDigit()) index++
            }
            val raw = input.substring(start, index)
            require(raw.isNotEmpty() && raw != "-") { "Expected JSON number at $start" }
            return if (raw.contains('.') || raw.contains('e', ignoreCase = true)) {
                JsonNumber(raw.toDouble())
            } else {
                JsonNumber(raw.toLong())
            }
        }

        private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
            require(input.startsWith(literal, index)) { "Expected '$literal' at $index" }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < input.length && input[index].isWhitespace()) index++
        }

        private fun expect(ch: Char) {
            require(index < input.length && input[index] == ch) { "Expected '$ch' at $index" }
            index++
        }

        private fun peek(ch: Char): Boolean = index < input.length && input[index] == ch
    }
}
