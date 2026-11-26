package com.lmalakar.onecr.update

/**
 * Minimal recursive-descent JSON parser (v0.3.3 update system).
 *
 * The update check reads exactly one document — GitHub's `releases/latest`
 * response — so instead of pulling a JSON dependency (or touching Android's
 * org.json, which is stubbed in local unit tests), this ~150-line parser
 * covers the whole grammar: objects, arrays, strings with the full escape
 * set incl. \uXXXX, numbers, booleans and null.
 *
 * Parsed shape: Map<String, Any?> / List<Any?> / String / Double /
 * Boolean / null. Strict: anything malformed throws [JsonException], so a
 * truncated or HTML error page is detected instead of half-parsed.
 */
internal object MiniJson {

    class JsonException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val value = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("trailing characters at ${p.pos}")
        return value
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            if (atEnd()) throw JsonException("unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonException("unexpected '$c' at $pos")
            }
        }

        private fun expect(word: String) {
            if (pos + word.length > s.length || s.substring(pos, pos + word.length) != word) {
                throw JsonException("invalid literal at $pos")
            }
            pos += word.length
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw JsonException("expected key at $pos")
                val key = readString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw JsonException("expected ':' at $pos")
                pos++
                skipWs()
                map[key] = readValue()
                skipWs()
                if (atEnd()) throw JsonException("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw JsonException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return list }
            while (true) {
                skipWs()
                list.add(readValue())
                skipWs()
                if (atEnd()) throw JsonException("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw JsonException("expected ',' or ']' at $pos")
                }
            }
        }

        fun readString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("unterminated string")
                when (val c = s[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> readEscape(sb)
                    else -> {
                        if (c.code < 0x20) throw JsonException("raw control char in string")
                        sb.append(c); pos++
                    }
                }
            }
        }

        private fun readEscape(sb: StringBuilder) {
            pos++ // '\'
            if (atEnd()) throw JsonException("dangling escape")
            when (val c = s[pos]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (pos + 4 >= s.length) throw JsonException("short \\u escape")
                    val hex = s.substring(pos + 1, pos + 5)
                    val code = hex.toIntOrNull(16) ?: throw JsonException("bad \\u escape: $hex")
                    sb.append(code.toChar())
                    pos += 4
                }
                else -> throw JsonException("bad escape '\\$c'")
            }
            pos++
        }

        private fun readNumber(): Double {
            val start = pos
            if (!atEnd() && s[pos] == '-') pos++
            while (!atEnd() && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDoubleOrNull()
                ?: throw JsonException("bad number '${s.substring(start, pos)}'")
        }
    }
}
