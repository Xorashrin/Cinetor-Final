package com.cinator.app.providers

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException

/** Strict, single-pass JSON for untrusted Blender documents and native results.
 * Never feed wire numbers through Android JSONTokener (which rounds via Double).
 * Containers only store our already-parsed values; BigDecimal remains exact.
 */
internal class BoundedLosslessJson private constructor(private val text: String) {
    private var offset = 0
    private var nodes = 0

    private fun node() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Blender JSON interrupted")
        require(++nodes <= MAX_NODES) { "Blender JSON node budget exceeded" }
    }

    private fun whitespace() {
        while (offset < text.length && text[offset] in " \t\r\n") offset++
    }

    private fun take(c: Char): Boolean {
        if (offset == text.length || text[offset] != c) return false
        offset++
        return true
    }

    private fun expect(c: Char) {
        require(take(c)) { "Invalid Blender JSON separator" }
    }

    private fun value(depth: Int): Any {
        whitespace()
        node()
        require(offset < text.length) { "Missing Blender JSON value" }
        return when (text[offset]) {
            '{' -> {
                require(depth < MAX_DEPTH) { "Blender JSON depth budget exceeded" }
                offset++
                val result = JSONObject()
                whitespace()
                if (!take('}')) {
                    do {
                        whitespace()
                        node() // Count keys as well as values.
                        val key = string()
                        require(!result.has(key)) { "Duplicate Blender JSON key" }
                        whitespace()
                        expect(':')
                        result.put(key, value(depth + 1))
                        whitespace()
                    } while (take(','))
                    expect('}')
                }
                result
            }
            '[' -> {
                require(depth < MAX_DEPTH) { "Blender JSON depth budget exceeded" }
                offset++
                val result = JSONArray()
                whitespace()
                if (!take(']')) {
                    do {
                        require(result.length() < MAX_ARRAY) { "Blender JSON array budget exceeded" }
                        result.put(value(depth + 1))
                        whitespace()
                    } while (take(','))
                    expect(']')
                }
                result
            }
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", JSONObject.NULL)
            else -> number()
        }
    }

    private fun literal(token: String, result: Any): Any {
        require(text.startsWith(token, offset)) { "Invalid Blender JSON literal" }
        offset += token.length
        return result
    }

    private fun string(): String {
        expect('"')
        val start = offset
        val result = StringBuilder()
        while (offset < text.length) {
            require(offset - start <= MAX_STRING) { "Blender JSON string budget exceeded" }
            val c = text[offset++]
            if (c == '"') {
                // Raw UTF-8 was checked before parsing; escaped UTF-16 must also pair.
                var i = 0
                while (i < result.length) {
                    val ch = result[i++]
                    if (Character.isHighSurrogate(ch)) {
                        require(i < result.length && Character.isLowSurrogate(result[i++])) { "Invalid JSON surrogate" }
                    } else require(!Character.isLowSurrogate(ch)) { "Invalid JSON surrogate" }
                }
                return result.toString()
            }
            require(c >= ' ') { "Unescaped JSON control character" }
            if (c != '\\') result.append(c) else {
                require(offset < text.length) { "Incomplete JSON escape" }
                result.append(when (val escape = text[offset++]) {
                    '"', '\\', '/' -> escape
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        require(offset + 4 <= text.length) { "Incomplete JSON unicode escape" }
                        var code = 0
                        repeat(4) {
                            val digit = text[offset++]
                            require(digit in '0'..'9' || digit in 'a'..'f' || digit in 'A'..'F') { "Invalid JSON unicode escape" }
                            code = code * 16 + Character.digit(digit, 16)
                        }
                        code.toChar()
                    }
                    else -> throw IllegalArgumentException("Invalid JSON escape")
                })
            }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }

    private fun number(): BigDecimal {
        val start = offset
        fun digits() {
            val first = offset
            while (offset < text.length && text[offset] in '0'..'9') {
                require(offset - start < MAX_NUMBER) { "Blender JSON number budget exceeded" }
                offset++
            }
            require(offset > first) { "Missing JSON digits" }
        }
        take('-')
        if (!take('0')) digits()
        if (take('.')) digits()
        if (take('e') || take('E')) {
            if (!take('+')) take('-')
            val exponentStart = offset
            digits()
            // Bound before constructing BigDecimal or attempting exact integer conversion.
            val exponent = text.substring(exponentStart, offset).toIntOrNull()
            require(exponent != null && exponent <= MAX_EXPONENT) { "Blender JSON exponent budget exceeded" }
        }
        require(offset - start <= MAX_NUMBER) { "Blender JSON number budget exceeded" }
        return BigDecimal(text.substring(start, offset))
    }

    companion object {
        private const val MAX_DEPTH = 16
        private const val MAX_NODES = 8192
        private const val MAX_ARRAY = 256
        private const val MAX_STRING = 16 * 1024
        private const val MAX_NUMBER = 128
        private const val MAX_EXPONENT = 128

        fun parse(raw: String, byteLimit: Int): Any {
            require(raw.length <= byteLimit) { "Blender JSON exceeds size budget" }
            val bytes = try {
                Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(raw)).remaining()
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("Invalid Blender JSON encoding")
            }
            require(bytes <= byteLimit) { "Blender JSON exceeds size budget" }
            val parser = BoundedLosslessJson(raw)
            val result = parser.value(0)
            parser.whitespace()
            require(parser.offset == raw.length) { "Trailing Blender JSON data" }
            return result
        }
    }
}
