package com.jokerhub.orzmc.world

/**
 * Minimal strict JSON parser used only by report round-trip tests (T5).
 *
 * The project has no JSON library on the test classpath, so serialized reports were
 * previously only verified with `string.contains(...)`. This parser proves the
 * serializer emits **valid** JSON by round-tripping it back into a value model.
 */
sealed class JValue {
    data class JObject(
        val fields: MutableMap<String, JValue>,
    ) : JValue()

    data class JArray(
        val items: MutableList<JValue>,
    ) : JValue()

    data class JString(
        val value: String,
    ) : JValue()

    data class JNumber(
        val raw: String,
    ) : JValue()

    data class JBool(
        val value: Boolean,
    ) : JValue()

    data object JNull : JValue()
}

object JsonTestParser {
    fun parse(text: String): JValue {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWs()
        check(p.atEnd()) { "trailing characters after value at offset ${p.pos}" }
        return v
    }

    private class Parser(
        private val s: String,
    ) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (!atEnd() && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): JValue {
            skipWs()
            if (atEnd()) error("unexpected end of input")
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JValue.JString(parseString())
                't', 'f' -> {
                    val lit = if (s[pos] == 't') "true" else "false"
                    check(s.startsWith(lit, pos)) { "invalid literal at $pos" }
                    pos += lit.length
                    JValue.JBool(lit == "true")
                }

                'n' -> {
                    check(s.startsWith("null", pos)) { "invalid literal at $pos" }
                    pos += 4
                    JValue.JNull
                }

                else -> JValue.JNumber(parseNumber())
            }
        }

        fun parseObject(): JValue.JObject {
            expect('{')
            val fields = mutableMapOf<String, JValue>()
            skipWs()
            if (peek() == '}') {
                pos++
                return JValue.JObject(fields)
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                fields[key] = value
                skipWs()
                when (peek()) {
                    ',' -> {
                        pos++
                    }
                    '}' -> {
                        pos++
                        return JValue.JObject(fields)
                    }
                    else -> error("expected ',' or '}' at $pos")
                }
            }
        }

        fun parseArray(): JValue.JArray {
            expect('[')
            val items = mutableListOf<JValue>()
            skipWs()
            if (peek() == ']') {
                pos++
                return JValue.JArray(items)
            }
            while (true) {
                items.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> {
                        pos++
                    }
                    ']' -> {
                        pos++
                        return JValue.JArray(items)
                    }
                    else -> error("expected ',' or ']' at $pos")
                }
            }
        }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) error("unterminated string")
                val c = s[pos]
                when {
                    c == '"' -> {
                        pos++
                        return sb.toString()
                    }
                    c == '\\' -> {
                        pos++
                        if (atEnd()) error("unterminated escape")
                        when (val e = s[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 < s.length) { "bad \\u escape" }
                                val hex = s.substring(pos + 1, pos + 5)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("unknown escape \\$e")
                        }
                        pos++
                    }
                    c.code < 0x20 -> error("raw control char in string at $pos")
                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
            }
        }

        fun parseNumber(): String {
            val start = pos
            while (!atEnd() && (s[pos].isDigit() || s[pos] in "-+.eE")) pos++
            return s.substring(start, pos)
        }

        fun expect(c: Char) {
            skipWs()
            check(!atEnd() && s[pos] == c) { "expected '$c' at $pos" }
            pos++
        }

        fun peek(): Char = s[pos]
    }
}
