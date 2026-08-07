package com.blackwellsystems.gcf

import org.junit.jupiter.api.Test
import java.io.StringWriter
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fuzzes the streaming tabular ROW-VALUE formatter (GenericStreamEncoder.writeRow)
 * with adversarial string cell values that collide with non-string tokens: "true"/
 * "false", numeric-looking strings, the null/missing/attachment markers -/~/^, leading
 * @/#/. names, and delimiter/quote/backslash payloads. The row-value formatter now
 * delegates to the canonical formatScalarValue(v, '|') the buffered tabular encoder
 * uses (Generic.kt), so a string that looks like a Boolean/Number/marker is quoted and
 * round-trips back as a String rather than decoding to the wrong type (SPEC 2.4).
 *
 * The streaming encoder emits the "GCF profile=generic" prelude itself, so the test
 * decodes its output as-is.
 */
class StreamValueQuoteTest {

    private val iterations = System.getenv("GCF_ITERATIONS")?.toIntOrNull() ?: 20_000

    // Collision strings: each MUST come back as a String, not the token it mimics.
    private val collisionStrings = listOf(
        "true", "false", "123", "4.5", "-", "~", "^", "@x", "#x", ".x",
        "", "a|b", "a,b", "he said \"hi\"", "back\\slash", "0", "-7", "1e10",
        "^{name,email}", "  padded  ", "tab\ttab", "line\nline"
    )

    @Test
    fun `streaming row values round-trip losslessly`() {
        val rng = Random(0x5641_4c55)
        var sawCollision = false
        for (i in 0 until iterations) {
            // Fixed flat schema; only the cell VALUES vary.
            val fields = listOf("a", "b", "c", "d")
            val nr = 1 + rng.nextInt(6)
            val out = StringWriter()
            val enc = GenericStreamEncoder(out)
            enc.beginArray("rows", fields)
            val expectedRows = mutableListOf<Map<String, Any?>>()
            repeat(nr) {
                val row = mutableListOf<Any?>()
                val obj = linkedMapOf<String, Any?>()
                for (f in fields) {
                    val v = genCellValue(rng)
                    if (v is String && v in collisionStrings) sawCollision = true
                    row.add(v)
                    obj[f] = v
                }
                enc.writeRow(row)
                expectedRows.add(obj)
            }
            enc.endArray()
            enc.close()

            val wire = out.toString()
            val decoded = decodeGeneric(wire)
            val want = linkedMapOf<String, Any?>("rows" to expectedRows)
            assertTrue(structuralEqual(want, decoded),
                "iteration $i: round-trip mismatch\n  want: $want\n  got:  $decoded\n  wire: $wire")
        }
        assertTrue(sawCollision,
            "generator never produced a collision string value")
    }

    // Pins the core losslessness guarantee: a string that spells "true" decodes back as
    // the String "true", not a Boolean. This is the exact case the bespoke row formatter
    // got wrong (it emitted "true" bare, so the decoder produced Boolean true).
    @Test
    fun `string true stays a string`() {
        val out = StringWriter()
        val enc = GenericStreamEncoder(out)
        enc.beginArray("rows", listOf("flag", "count", "marker"))
        enc.writeRow(listOf("true", "123", "-"))
        enc.endArray()
        enc.close()

        @Suppress("UNCHECKED_CAST")
        val decoded = decodeGeneric(out.toString()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val rows = decoded["rows"] as List<Map<String, Any?>>
        val cell = rows[0]["flag"]
        assertTrue(cell is String, "expected String, got ${cell?.javaClass}")
        assertEquals("true", cell)
        assertEquals("123", rows[0]["count"])
        assertEquals("-", rows[0]["marker"])
    }

    private val bareChars = "abcdefghijklmnopqrstuvwxyz_"

    private fun genCellValue(rng: Random): Any? = when (rng.nextInt(8)) {
        0 -> null
        1 -> rng.nextBoolean()
        2 -> rng.nextInt(1000)
        3 -> (rng.nextInt(1000) - 500).toLong()
        4 -> rng.nextDouble() * 100.0
        5 -> rng.nextFloat() * 50.0f
        6 -> collisionStrings[rng.nextInt(collisionStrings.size)]
        else -> {
            // Plain alphanumeric string that needs no quoting.
            val n = 1 + rng.nextInt(6)
            "s" + (0 until n).map { bareChars[rng.nextInt(bareChars.length)] }.joinToString("")
        }
    }

    private fun structuralEqual(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        if (a is Map<*, *> && b is Map<*, *>) {
            val am = a.keys.map { it.toString() }.toSortedSet()
            val bm = b.keys.map { it.toString() }.toSortedSet()
            if (am != bm) return false
            @Suppress("UNCHECKED_CAST")
            return am.all { structuralEqual((a as Map<String, Any?>)[it], (b as Map<String, Any?>)[it]) }
        }
        if (a is List<*> && b is List<*>) {
            if (a.size != b.size) return false
            return a.zip(b).all { (x, y) -> structuralEqual(x, y) }
        }
        return a == b
    }
}
