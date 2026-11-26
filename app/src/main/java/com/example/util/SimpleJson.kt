package com.example.util

/**
 * Pure-Kotlin JSON parser so JSON deserialization works identically on Android runtime
 * and plain JVM unit tests without depending on Android's `org.json` stubs.
 */
object SimpleJson {

    fun parseObject(raw: String): Map<String, Any?> {
        val parser = Parser(raw)
        parser.skipWhitespace()
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.isAtEnd()) {
            throw IllegalArgumentException("Unexpected trailing JSON content at index ${parser.index}")
        }
        @Suppress("UNCHECKED_CAST")
        return (value as? Map<String, Any?>)
            ?: throw IllegalArgumentException("Expected top-level JSON object")
    }

    fun stringValue(map: Map<String, Any?>, key: String): String {
        val v = map[key] ?: return ""
        return v as? String ?: v.toString()
    }

    fun objectListValue(map: Map<String, Any?>, key: String): List<Map<String, Any?>> {
        val rawList = map[key] as? List<*> ?: return emptyList()
        return rawList.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            item as? Map<String, Any?>
        }
    }

    private class Parser(private val input: String) {
        var index: Int = 0
            private set

        fun isAtEnd(): Boolean = index >= input.length

        fun skipWhitespace() {
            while (index < input.length && input[index].isWhitespace()) {
                index++
            }
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (isAtEnd()) throw IllegalArgumentException("Unexpected end of JSON input")
            return when (val ch = input[index]) {
                '{' -> parseObjectInternal()
                '[' -> parseArrayInternal()
                '"' -> parseStringInternal()
                't', 'f' -> parseBooleanInternal()
                'n' -> parseNullInternal()
                '-', in '0'..'9' -> parseNumberInternal()
                else -> throw IllegalArgumentException("Unexpected character '$ch' at index $index")
            }
        }

        private fun parseObjectInternal(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') {
                    throw IllegalArgumentException("Expected string key at index $index")
                }
                val key = parseStringInternal()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                result[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at index $index")
                }
            }
        }

        private fun parseArrayInternal(): List<Any?> {
            expect('[')
            skipWhitespace()
            val result = mutableListOf<Any?>()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at index $index")
                }
            }
        }

        private fun parseStringInternal(): String {
            expect('"')
            val sb = StringBuilder()
            while (index < input.length) {
                val ch = input[index++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (index >= input.length) {
                            throw IllegalArgumentException("Unterminated escape sequence")
                        }
                        when (val esc = input[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > input.length) {
                                    throw IllegalArgumentException("Invalid unicode escape at index $index")
                                }
                                val hex = input.substring(index, index + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid hex unicode '$hex'")
                                sb.append(code.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape '\\$esc' at index ${index - 1}")
                        }
                    }
                    else -> {
                        if (ch.code < 0x20) {
                            throw IllegalArgumentException("Unescaped control character in JSON string")
                        }
                        sb.append(ch)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun parseBooleanInternal(): Boolean {
            return if (input.startsWith("true", index)) {
                index += 4
                true
            } else if (input.startsWith("false", index)) {
                index += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean literal at index $index")
            }
        }

        private fun parseNullInternal(): Any? {
            if (input.startsWith("null", index)) {
                index += 4
                return null
            }
            throw IllegalArgumentException("Invalid null literal at index $index")
        }

        private fun parseNumberInternal(): Number {
            val start = index
            if (peek() == '-') index++
            if (isAtEnd() || input[index] !in '0'..'9') {
                throw IllegalArgumentException("Invalid JSON number at index $start")
            }
            while (index < input.length && input[index] in '0'..'9') index++
            var isDecimal = false
            if (index < input.length && input[index] == '.') {
                isDecimal = true
                index++
                if (index >= input.length || input[index] !in '0'..'9') {
                    throw IllegalArgumentException("Invalid fractional number at index $start")
                }
                while (index < input.length && input[index] in '0'..'9') index++
            }
            if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
                isDecimal = true
                index++
                if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
                if (index >= input.length || input[index] !in '0'..'9') {
                    throw IllegalArgumentException("Invalid exponent at index $start")
                }
                while (index < input.length && input[index] in '0'..'9') index++
            }
            val rawNum = input.substring(start, index)
            return if (isDecimal) {
                rawNum.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number '$rawNum'")
            } else {
                rawNum.toLongOrNull() ?: rawNum.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid number '$rawNum'")
            }
        }

        private fun peek(): Char = if (isAtEnd()) '\u0000' else input[index]

        private fun expect(expected: Char) {
            if (isAtEnd() || input[index] != expected) {
                throw IllegalArgumentException("Expected '$expected' at index $index")
            }
            index++
        }
    }
}
