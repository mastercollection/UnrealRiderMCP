package com.nx3games.unrealpasser.http

class JsonValueParser(private val raw: String) {
    private var index = 0

    fun parse(): Any? {
        val value = readValue()
        skipWhitespace()
        if (index != raw.length) throw IllegalArgumentException("Unexpected JSON content at offset $index")
        return value
    }

    private fun readValue(): Any? {
        skipWhitespace()
        if (index >= raw.length) throw IllegalArgumentException("Unexpected end of JSON")
        return when (raw[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> readLiteral("true", true)
            'f' -> readLiteral("false", false)
            'n' -> readLiteral("null", null)
            else -> readNumber()
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect('{')
        val result = linkedMapOf<String, Any?>()
        skipWhitespace()
        if (peek('}')) {
            index++
            return result
        }
        while (true) {
            val key = readString()
            skipWhitespace()
            expect(':')
            result[key] = readValue()
            skipWhitespace()
            when {
                peek(',') -> index++
                peek('}') -> {
                    index++
                    return result
                }
                else -> throw IllegalArgumentException("Expected ',' or '}' at offset $index")
            }
        }
    }

    private fun readArray(): List<Any?> {
        expect('[')
        val result = mutableListOf<Any?>()
        skipWhitespace()
        if (peek(']')) {
            index++
            return result
        }
        while (true) {
            result.add(readValue())
            skipWhitespace()
            when {
                peek(',') -> index++
                peek(']') -> {
                    index++
                    return result
                }
                else -> throw IllegalArgumentException("Expected ',' or ']' at offset $index")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < raw.length) {
            val c = raw[index++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> out.append(readEscape())
                else -> out.append(c)
            }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }

    private fun readEscape(): Char {
        if (index >= raw.length) throw IllegalArgumentException("Unterminated JSON escape")
        return when (val c = raw[index++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (index + 4 > raw.length) throw IllegalArgumentException("Invalid unicode escape")
                val code = raw.substring(index, index + 4).toInt(16)
                index += 4
                code.toChar()
            }
            else -> throw IllegalArgumentException("Invalid JSON escape '\\$c'")
        }
    }

    private fun readNumber(): Number {
        val start = index
        if (peek('-')) index++
        while (index < raw.length && raw[index].isDigit()) index++
        if (peek('.')) {
            index++
            while (index < raw.length && raw[index].isDigit()) index++
        }
        if (index < raw.length && (raw[index] == 'e' || raw[index] == 'E')) {
            index++
            if (index < raw.length && (raw[index] == '+' || raw[index] == '-')) index++
            while (index < raw.length && raw[index].isDigit()) index++
        }
        val token = raw.substring(start, index)
        if (token.isEmpty() || token == "-") throw IllegalArgumentException("Invalid JSON number at offset $start")
        return if (token.contains('.') || token.contains('e', ignoreCase = true)) token.toDouble() else token.toLong()
    }

    private fun readLiteral(literal: String, value: Any?): Any? {
        if (!raw.startsWith(literal, index)) throw IllegalArgumentException("Expected '$literal' at offset $index")
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (index < raw.length && raw[index].isWhitespace()) index++
    }

    private fun expect(char: Char) {
        skipWhitespace()
        if (!peek(char)) throw IllegalArgumentException("Expected '$char' at offset $index")
        index++
    }

    private fun peek(char: Char): Boolean = index < raw.length && raw[index] == char

    companion object {
        fun parse(raw: String): Any? = JsonValueParser(raw).parse()
    }
}
