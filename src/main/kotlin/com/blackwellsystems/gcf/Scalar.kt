package com.blackwellsystems.gcf

private val JSON_NUMBER_RE = Regex("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$")
private val NUMERIC_LIKE_RE = Regex("^[+-]?\\.?\\d")
private val BARE_KEY_RE = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

fun needsQuote(s: String): Boolean {
    if (s.isEmpty()) return true
    if (s in setOf("-", "~", "^", "true", "false")) return true
    if (JSON_NUMBER_RE.matches(s)) return true
    if (NUMERIC_LIKE_RE.containsMatchIn(s)) return true
    if (s.first() == ' ' || s.last() == ' ') return true
    if (s.first() == '#' || s.first() == '@') return true
    for (c in s) {
        if (c == '"' || c == '\\' || c == '|' || c == ',' || c.code < 0x20 || c == '\n' || c == '\r') return true
    }
    return false
}

fun quoteString(s: String): String {
    val out = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c.code < 0x20) out.append("\\u${String.format("%04x", c.code)}") else out.append(c)
        }
    }
    out.append('"')
    return out.toString()
}

fun formatScalarValue(v: Any?, delimiter: Char = '\u0000'): String {
    if (v == null) return "-"
    return when (v) {
        is Boolean -> if (v) "true" else "false"
        is Int -> v.toString()
        is Long -> v.toString()
        is Double -> formatNumberValue(v)
        is Float -> formatNumberValue(v.toDouble())
        is Number -> v.toString()
        is String -> {
            if (needsQuote(v) || (delimiter != '\u0000' && delimiter in v)) quoteString(v) else v
        }
        else -> {
            val s = v.toString()
            if (needsQuote(s) || (delimiter != '\u0000' && delimiter in s)) quoteString(s) else s
        }
    }
}

fun formatNumberValue(f: Double): String {
    if (f.isNaN() || f.isInfinite()) return "0"
    if (f == 0.0) return if (1.0 / f < 0) "-0" else "0"
    val a = kotlin.math.abs(f)
    if (a >= 1e-6 && a < 1e21) {
        var s = f.toBigDecimal().stripTrailingZeros().toPlainString()
        // Strip trailing .0 for integer-valued floats
        if (s.endsWith(".0") && f == truncateToZero(f)) {
            s = s.dropLast(2)
        }
        return s
    }
    // Exponent notation.
    val s = "%.17e".format(f)
    val parts = s.split("e", ignoreCase = true)
    val mantissa = parts[0].trimEnd('0').trimEnd('.')
    val expPart = parts[1]
    val sign = if (expPart.startsWith('-')) "-" else "+"
    val digits = expPart.trimStart('+', '-', '0').ifEmpty { "0" }
    return "${mantissa}e${sign}${digits}"
}

fun formatKeyValue(s: String): String = if (BARE_KEY_RE.matches(s)) s else quoteString(s)

// --- Parsing ---

sealed class ScalarParsed {
    data object Null : ScalarParsed()
    data class BoolVal(val value: Boolean) : ScalarParsed()
    data class IntVal(val value: Long) : ScalarParsed()
    data class DoubleVal(val value: Double) : ScalarParsed()
    data class StringVal(val value: String) : ScalarParsed()
    data object Missing : ScalarParsed()
    data object Attachment : ScalarParsed()
}

fun parseScalarValue(s: String, tabularContext: Boolean = false): ScalarParsed {
    if (s.isEmpty()) return ScalarParsed.StringVal("")
    if (s[0] == '"') return ScalarParsed.StringVal(parseQuotedStringValue(s))
    if (s == "-") return ScalarParsed.Null
    if (s == "~") {
        if (!tabularContext) throw IllegalArgumentException("invalid_missing: ~ outside tabular row cell")
        return ScalarParsed.Missing
    }
    if (s == "^") {
        if (!tabularContext) throw IllegalArgumentException("invalid_attachment_marker: ^ outside tabular row cell")
        return ScalarParsed.Attachment
    }
    if (s == "true") return ScalarParsed.BoolVal(true)
    if (s == "false") return ScalarParsed.BoolVal(false)
    if (JSON_NUMBER_RE.matches(s)) {
        val d = s.toDoubleOrNull()
        if (d != null) {
            if ('.' !in s && 'e' !in s && 'E' !in s && kotlin.math.abs(d) <= (1L shl 53).toDouble()) {
                return ScalarParsed.IntVal(d.toLong())
            }
            return ScalarParsed.DoubleVal(d)
        }
    }
    return ScalarParsed.StringVal(s)
}

fun parseQuotedStringValue(s: String): String {
    if (s.length < 2 || s[0] != '"') throw IllegalArgumentException("unterminated_quote")
    val out = StringBuilder()
    var i = 1
    while (i < s.length) {
        if (s[i] == '"') {
            if (i + 1 != s.length) throw IllegalArgumentException("trailing_characters: after closing quote")
            return out.toString()
        }
        if (s[i] == '\\') {
            if (i + 1 >= s.length) throw IllegalArgumentException("unterminated_quote")
            i++
            when (s[i]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 4 >= s.length) throw IllegalArgumentException("invalid_escape: incomplete unicode")
                    val hex = s.substring(i + 1, i + 5)
                    val code = hex.toIntOrNull(16) ?: throw IllegalArgumentException("invalid_escape: invalid unicode \\u$hex")
                    if (code in 0xD800..0xDBFF) {
                        if (i + 10 >= s.length || s[i + 5] != '\\' || s[i + 6] != 'u')
                            throw IllegalArgumentException("invalid_surrogate: isolated high surrogate")
                        val hex2 = s.substring(i + 7, i + 11)
                        val low = hex2.toIntOrNull(16) ?: throw IllegalArgumentException("invalid_surrogate: invalid low surrogate")
                        if (low !in 0xDC00..0xDFFF) throw IllegalArgumentException("invalid_surrogate: expected low surrogate")
                        val combined = 0x10000 + (code - 0xD800) * 0x400 + (low - 0xDC00)
                        out.appendCodePoint(combined)
                        i += 11; continue
                    }
                    if (code in 0xDC00..0xDFFF) throw IllegalArgumentException("invalid_surrogate: isolated low surrogate")
                    out.append(code.toChar())
                    i += 5; continue
                }
                else -> throw IllegalArgumentException("invalid_escape: unknown \\${s[i]}")
            }
            i++; continue
        }
        if (s[i].code < 0x20) throw IllegalArgumentException("invalid_escape: unescaped control U+${String.format("%04x", s[i].code)}")
        out.append(s[i])
        i++
    }
    throw IllegalArgumentException("unterminated_quote")
}

fun splitRespectingQuotes(s: String, delim: Char): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var inQuote = false
    var escaped = false
    for (c in s) {
        if (escaped) { current.append(c); escaped = false; continue }
        if (c == '\\' && inQuote) { current.append(c); escaped = true; continue }
        if (c == '"') { inQuote = !inQuote; current.append(c); continue }
        if (c == delim && !inQuote) { parts.add(current.toString()); current.clear(); continue }
        current.append(c)
    }
    parts.add(current.toString())
    return parts
}

fun splitFieldDeclValue(s: String): List<String> {
    if (s.length < 2 || s[0] != '{') throw IllegalArgumentException("invalid field declaration: $s")
    val close = findClosingBraceIdx(s) ?: throw IllegalArgumentException("invalid field declaration: $s")
    val inner = s.substring(1, close)
    if (inner.isEmpty()) return emptyList()
    val raw = splitRespectingQuotes(inner, ',')
    val fields = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (f in raw) {
        val trimmed = f.trim()
        val name = if (trimmed.length >= 2 && trimmed[0] == '"' && trimmed.last() == '"') {
            parseQuotedStringValue(trimmed)
        } else {
            if (!BARE_KEY_RE.matches(trimmed)) throw IllegalArgumentException("invalid field name: $trimmed")
            trimmed
        }
        if (name in seen) throw IllegalArgumentException("duplicate_field_name: $name")
        seen.add(name)
        fields.add(name)
    }
    return fields
}

fun findClosingBraceIdx(s: String): Int? {
    var inQuote = false
    var escaped = false
    for ((i, c) in s.withIndex()) {
        if (escaped) { escaped = false; continue }
        if (c == '\\' && inQuote) { escaped = true; continue }
        if (c == '"') { inQuote = !inQuote; continue }
        if (c == '}' && !inQuote) return i
    }
    return null
}

private fun truncateToZero(d: Double): Double = if (d >= 0) kotlin.math.floor(d) else kotlin.math.ceil(d)
