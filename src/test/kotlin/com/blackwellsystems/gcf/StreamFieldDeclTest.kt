package com.blackwellsystems.gcf

import org.junit.jupiter.api.Test
import java.io.StringWriter
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fuzzes the streaming tabular header (GenericStreamEncoder.beginArray) with
 * adversarial field names: comma, pipe, quote, empty, leading @/#/., spaces. The
 * header previously joined field names raw, so a name containing a delimiter or
 * quote produced an invalid or ambiguous field declaration (SPEC 8.3). Field names
 * now format via formatKeyValue (Section 2.4), matching the buffered tabular header.
 * A field name containing '>' is rejected (a flattened path is not representable in a
 * flat streaming row) and is asserted separately below.
 *
 * The streaming encoder does not emit the "GCF profile=generic" prelude, so the test
 * prepends it before decoding.
 */
class StreamFieldDeclTest {

    private val iterations = System.getenv("GCF_ITERATIONS")?.toIntOrNull() ?: 20_000

    @Test
    fun `streaming array field names round-trip`() {
        val rng = Random(0x5738)
        var sawSpecial = false
        for (i in 0 until iterations) {
            val nf = 1 + rng.nextInt(5)
            val fields = mutableListOf<String>()
            while (fields.size < nf) {
                val f = genFieldName(rng)
                if (">" in f) continue // '>' is rejected, tested separately
                if (f in fields) continue
                fields.add(f)
                if (f.isEmpty() || f.any { it == ',' || it == '|' || it == '"' }) sawSpecial = true
            }

            val nr = 1 + rng.nextInt(6)
            val out = StringWriter()
            val enc = GenericStreamEncoder(out)
            enc.beginArray("rows", fields)
            val expectedRows = mutableListOf<Map<String, Any?>>()
            repeat(nr) {
                val row = mutableListOf<Any?>()
                val obj = linkedMapOf<String, Any?>()
                for (f in fields) {
                    val v = genRowScalar(rng)
                    row.add(v)
                    obj[f] = v
                }
                enc.writeRow(row)
                expectedRows.add(obj)
            }
            enc.endArray()
            enc.close()

            val wire = "GCF profile=generic\n" + out.toString()
            val decoded = decodeGeneric(wire)
            val want = linkedMapOf<String, Any?>("rows" to expectedRows)
            assertTrue(structuralEqual(want, decoded),
                "iteration $i: round-trip mismatch\n  fields: $fields\n  want: $want\n  got:  $decoded\n  wire: $wire")
        }
        assertTrue(sawSpecial,
            "generator never produced a field name needing quoting (empty / , | \")")
    }

    // Locks the SPEC 8.3 requirement that a streaming value field name containing '>'
    // is rejected. The encoder records the error on beginArray and throws it at close().
    @Test
    fun `streaming field name with gt is rejected`() {
        val out = StringWriter()
        val enc = GenericStreamEncoder(out)
        enc.beginArray("rows", listOf("id", "a>b"))
        enc.writeRow(listOf(1, 2))
        enc.endArray()
        try {
            enc.close()
            fail("expected an error for a '>' field name, got success\n  wire: ${out}")
        } catch (e: IllegalArgumentException) {
            assertTrue(">" in e.message.orEmpty(), "unexpected error message: ${e.message}")
        }
    }

    private val bareChars = "abcdefghijklmnopqrstuvwxyz_"
    // Special characters that force quoting via Section 2.4. Excludes '>' (rejected).
    private val fieldSpecial = " |,=\"\\#@\n\t~^+-."

    private fun genFieldName(rng: Random): String {
        if (rng.nextInt(4) == 0) {
            // Adversarial name that requires quoting.
            val n = rng.nextInt(6) // may be 0 -> empty string
            return (0 until n).map {
                if (rng.nextFloat() < 0.5) fieldSpecial[rng.nextInt(fieldSpecial.length)]
                else bareChars[rng.nextInt(bareChars.length)]
            }.joinToString("")
        }
        val n = 1 + rng.nextInt(7)
        return (0 until n).map { bareChars[rng.nextInt(bareChars.length)] }.joinToString("")
    }

    // Row scalars that round-trip cleanly through the streaming pipe-row value
    // formatter: null, boolean, int, and a plain alphanumeric string (never a
    // collision token or numeric-looking string, which are a separate value-encoding
    // concern from the field-name declaration under test here).
    private fun genRowScalar(rng: Random): Any? = when (rng.nextInt(5)) {
        0 -> null
        1 -> rng.nextBoolean()
        2 -> rng.nextInt(1000)
        3 -> -rng.nextInt(1000)
        else -> {
            // Leading letter guarantees it is not parsed as a number or collision token.
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
