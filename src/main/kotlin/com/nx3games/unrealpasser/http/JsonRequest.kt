package com.nx3games.unrealpasser.http

class JsonRequest(private val raw: String) {
    fun string(key: String, required: Boolean = false): String? {
        val match = Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""").find(raw)
        val value = match?.groupValues?.get(1)?.let(::unescape)
        if (required && value == null) throw IllegalArgumentException("Missing string field '$key'")
        return value
    }

    fun int(key: String): Int? {
        return Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun boolean(key: String): Boolean? {
        return Regex(""""${Regex.escape(key)}"\s*:\s*(true|false)""").find(raw)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val next = value[++i]
                out.append(
                    when (next) {
                        '"' -> '"'
                        '\\' -> '\\'
                        '/' -> '/'
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> next
                    }
                )
            } else {
                out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
