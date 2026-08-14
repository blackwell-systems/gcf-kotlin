package com.blackwellsystems.gcf

/**
 * Build the canonical out-of-range error message (SPEC 2.3.2). The message
 * contains the substring `out_of_range`, names the offending value, states the
 * int64 interval, and gives the remediation (model larger values as strings).
 */
internal fun outOfRangeMessage(value: String): String =
    "out_of_range: integer $value is outside the canonical int64 domain " +
        "[-9223372036854775808, 9223372036854775807]; model larger values as strings (SPEC 2.3.2)"

private val JSON_NUMBER_RE = Regex("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$")
private val NUMERIC_LIKE_RE = Regex("^[+-]\\.?\\d|^\\.\\d|^0\\d")
private val BARE_KEY_RE = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
private val INLINE_ARRAY_RE = Regex("""\[[^\]]*\]\s*:""")

fun needsQuote(s: String): Boolean {
    if (s.isEmpty()) return true
    if (s in setOf("-", "~", "^", "true", "false")) return true
    // A value shaped like an inline-schema attachment marker (^{...}) would decode
    // as an attachment and lose the string, so it must be quoted (SPEC 2.4).
    if (s.length >= 3 && s.startsWith("^{") && s.endsWith("}")) return true
    if (JSON_NUMBER_RE.matches(s)) return true
    if (NUMERIC_LIKE_RE.containsMatchIn(s)) return true
    if (s.first() == ' ' || s.last() == ' ') return true
    if (s.first() == '#' || s.first() == '@' || s.first() == '.') return true
    if (INLINE_ARRAY_RE.containsMatchIn(s)) return true
    for (c in s) {
        val code = c.code
        if (c == '"' || c == '\\' || c == '|' || c == ',' || code < 0x20 || c == '\n' || c == '\r') return true
        if (code in 0x80..0x9F) return true // C1 controls
        if (code > 0x7F && code in setOf(0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF)) return true
        if (code in 0x2000..0x200A) return true // Unicode spaces
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
        // Signed integers fit the int64 domain exactly and render as plain decimal
        // digits across the whole closed interval, including Long.MIN_VALUE
        // (-2^63); an integer is never rendered in exponent form and its rendering
        // is not gated on a magnitude test (SPEC 2.3.1, 2.3.2).
        is Byte -> v.toString()
        is Short -> v.toString()
        is Int -> v.toString()
        is Long -> v.toString()
        is Double -> formatNumberValue(v)
        is Float -> formatNumberValue(v.toDouble())
        // A native integer that cannot be represented in int64 (a BigInteger or an
        // unsigned 64-bit value above Long.MAX_VALUE) is outside the numeric domain
        // and MUST be rejected rather than rendered lossily (SPEC 2.3.2). A value
        // that fits int64 is encoded as its exact digits.
        is java.math.BigInteger -> {
            if (v.bitLength() >= 64) throw IllegalArgumentException(outOfRangeMessage(v.toString()))
            v.toString()
        }
        is ULong -> {
            if (v > Long.MAX_VALUE.toULong()) throw IllegalArgumentException(outOfRangeMessage(v.toString()))
            v.toString()
        }
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
    // Negative zero canonicalizes to 0 (SPEC 2.3.1): -0.0 equals 0.0 by value.
    if (f == 0.0) return "0"
    val a = kotlin.math.abs(f)
    // Plain decimal only below 2^53. Every double at or above 2^53 is integer-valued,
    // so a plain rendering would emit a bare-integer token: indistinguishable from an
    // int64 on the wire and beyond the binary64 safe-integer range (2^53-1), so a
    // JavaScript decoder rejects it under its default policy. Exponent shape keeps bare
    // tokens int64 and decimal/exponent tokens doubles (SPEC 2.3.1). 9007199254740992.0
    // is 2^53.
    if (a >= 1e-6 && a < 9007199254740992.0) {
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

/**
 * Format a graph score to exactly two decimals with round-half-to-even applied to
 * the exact IEEE-754 double (SPEC 5), matching the C/Go printf family, Python,
 * Rust, and .NET. This deliberately does NOT use `"%.2f".format(...)`, which goes
 * through java.util.Formatter (round-half-up) and diverges at exact binary
 * midpoints (0.125 -> 0.12 not 0.13, 0.625 -> 0.62 not 0.63), producing a
 * non-interoperable wire.
 */
fun formatScore(score: Double): String =
    java.math.BigDecimal(score).setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString()

// --- Parsing ---

sealed class ScalarParsed {
    data object Null : ScalarParsed()
    data class BoolVal(val value: Boolean) : ScalarParsed()
    data class IntVal(val value: Long) : ScalarParsed()
    data class DoubleVal(val value: Double) : ScalarParsed()
    data class StringVal(val value: String) : ScalarParsed()
    data object Missing : ScalarParsed()
    data object Attachment : ScalarParsed()
    data class InlineAttachment(val schema: String) : ScalarParsed()
}

fun parseScalarValue(s: String, tabularContext: Boolean = false): ScalarParsed {
    if (s.isEmpty()) return ScalarParsed.StringVal("")
    if (s[0] == '"') return ScalarParsed.StringVal(parseQuotedStringValue(s))
    if (s == "-") return ScalarParsed.Null
    if (s == "~") {
        if (!tabularContext) throw IllegalArgumentException("invalid_missing: ~ outside tabular row cell")
        return ScalarParsed.Missing
    }
    if (s == "^" || (s.startsWith("^{") && s.endsWith("}"))) {
        if (!tabularContext) throw IllegalArgumentException("invalid_attachment_marker: ^ outside tabular row cell")
        if (s == "^") return ScalarParsed.Attachment
        return ScalarParsed.InlineAttachment(s.drop(1)) // e.g. "{name,email,tier}"
    }
    if (s == "true") return ScalarParsed.BoolVal(true)
    if (s == "false") return ScalarParsed.BoolVal(false)
    if (JSON_NUMBER_RE.matches(s)) {
        // A bare token (no fraction, no exponent) is an integer parsed directly to
        // Long so the full signed 64-bit domain round-trips exactly. Routing it
        // through a Double would silently approximate any magnitude beyond 2^53
        // (SPEC 2.3.2). `-0` is integer syntax and decodes to the value zero (Long
        // 0), not a Double -0.0. Fraction/exponent forms remain IEEE-754 doubles.
        if ('.' !in s && 'e' !in s && 'E' !in s) {
            val n = s.toLongOrNull()
            if (n != null) return ScalarParsed.IntVal(n)
            // An all-digits token (JSON_NUMBER_RE matched, no '.'/'e'/'E') that
            // toLongOrNull rejects is an integer literal overflowing int64: that is
            // an out-of-range value, not a bare string. A genuinely non-numeric
            // token cannot reach here because JSON_NUMBER_RE already matched.
            throw IllegalArgumentException(outOfRangeMessage(s))
        }
        val d = s.toDoubleOrNull()
        if (d != null) return ScalarParsed.DoubleVal(d)
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
