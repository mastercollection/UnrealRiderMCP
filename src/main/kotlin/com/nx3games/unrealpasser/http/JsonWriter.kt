package com.nx3games.unrealpasser.http

object JsonWriter {
    fun write(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "${quote(k.toString())}:${write(v)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { write(it) }
        else -> quote(value.toString())
    }

    private fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
                }
            }
        }
        out.append('"')
        return out.toString()
    }
}
