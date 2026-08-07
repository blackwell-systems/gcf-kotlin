package com.blackwellsystems.gcf

import java.io.StringWriter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

// Property/fuzz tests for keyed-tabular map encoding (SPEC 7.2a), mirroring the
// gcf-go TestPropertyRoundTripKeyedMapBiased / TestPropertyRoundTripKeyedMapStreaming
// suite. Keyed maps are canonical (default-on, no option), so the buffered path is
// exercised simply by encoding maps whose values are all objects. Value fields,
// member keys, and cell values are all drawn from an adversarial alphabet (empty,
// pipe, quote, numeric-like, marker chars) so header field-name quoting and cell
// quoting are exercised on both the buffered and streaming paths.
class KeyedMapFuzzTest {
    private val iterations = System.getenv("GCF_ITERATIONS")?.toIntOrNull() ?: 100_000

    private val keyAlphabet = listOf(
        "id", "name", "a", "b", "key", "_key", "value",
        "", " ", "|", "a|b", "\"q\"", "true", "false", "-", "~", "^",
        "0", "123", "-0", "#lead", "@lead", ".lead", "a,b", "a=b", "x>y",
        "\t", "\n", "é", "中", "🦞",
    )

    private fun genKey(rng: Random): String = keyAlphabet[rng.nextInt(keyAlphabet.size)]

    private fun genScalar(rng: Random): Any? = when (rng.nextInt(6)) {
        0 -> null
        1 -> rng.nextBoolean()
        2 -> rng.nextInt(2000) - 1000
        3 -> rng.nextDouble() * 2000 - 1000
        4 -> genKey(rng)
        else -> (0 until rng.nextInt(12)).joinToString("") { genKey(rng) }
    }

    // A random object whose values are scalars, drawn from adversarial keys. Kept
    // shallow so the buffered path exercises the keyed table itself rather than the
    // orthogonal flatten/attachment paths (those have their own fuzz coverage).
    private fun genValueObject(rng: Random): Map<String, Any?> {
        val n = rng.nextInt(5) // 0..4 fields (0 exercises the empty-value fallback)
        val m = linkedMapOf<String, Any?>()
        repeat(n) {
            val k = genKey(rng)
            if (k !in m) m[k] = genScalar(rng)
        }
        return m
    }

    // A. Buffered round-trip: maps of objects encode canonically as keyed tables
    //    (or fall back to Section 7.2 per the eligibility rule) and round-trip exactly.
    @Test
    fun `keyed map buffered roundtrip`() {
        val rng = Random(0x7EA5)
        for (i in 0 until iterations) {
            val n = rng.nextInt(7) // 0..6 members: 0/1 exercise the non-eligible paths
            val m = linkedMapOf<String, Any?>()
            repeat(n) {
                val k = genKey(rng)
                if (k !in m) m[k] = genValueObject(rng)
            }
            // Occasionally poison a value with a non-object so the whole map must
            // fall back to section encoding (eligibility clause 2).
            if (rng.nextFloat() < 0.15 && m.isNotEmpty()) {
                m[m.keys.first()] = genScalar(rng)
            }
            val gcf = encodeGeneric(m)
            val decoded = decodeGeneric(gcf)
            assertTrue(structuralEqual(m, decoded),
                "iter $i: buffered mismatch\n  input: $m\n  decoded: $decoded\n  gcf: $gcf")
        }
    }

    // B. Streaming round-trip: the streaming keyed-map path (beginKeyedMap + writeRow)
    //    is separate code from the buffered encoder. Streaming has a fixed value schema
    //    (Section 8.3), so every member carries every value field.
    @Test
    fun `keyed map streaming roundtrip`() {
        val rng = Random(0x5417)
        for (i in 0 until iterations) {
            // Distinct value-field schema, adversarial names. A '>' field name is
            // outside the streaming schema contract (a flat column cannot carry a
            // flattened path, Section 8.3/7.4.6), so it is excluded here.
            val nf = 1 + rng.nextInt(5)
            val fields = mutableListOf<String>()
            while (fields.size < nf) {
                val f = genKey(rng)
                if (">" !in f && f !in fields) fields.add(f)
            }
            var keyLabel = "key"
            while (keyLabel in fields) keyLabel = "_$keyLabel"

            val nr = 2 + rng.nextInt(7)
            val keys = mutableListOf<String>()
            while (keys.size < nr) {
                val k = genKey(rng)
                if (k !in keys) keys.add(k)
            }

            val expected = linkedMapOf<String, Any?>()
            val sw = StringWriter()
            val enc = GenericStreamEncoder(sw)
            enc.beginKeyedMap("m", keyLabel, fields)
            for (k in keys) {
                val row = mutableListOf<Any?>(k)
                val valObj = linkedMapOf<String, Any?>()
                for (f in fields) {
                    val v = genScalar(rng)
                    row.add(v)
                    valObj[f] = v
                }
                enc.writeRow(row)
                expected[k] = valObj
            }
            enc.endArray()
            enc.close()

            val wire = sw.toString()
            val decoded = decodeGeneric(wire)
            val want = mapOf("m" to expected)
            assertTrue(structuralEqual(want, decoded),
                "iter $i: streaming mismatch\n  want: $want\n  got: $decoded\n  wire: $wire")
        }
    }

    // C. Eligibility invariants (SPEC 7.2a.1), asserted directly on the wire so a
    //    silent selection change is caught, not just a round-trip regression.
    @Test
    fun `keyed map eligibility invariants`() {
        // Single-member map of an object must NOT key (two-member minimum): the
        // wrapper uses ordinary section encoding.
        val single = encodeGeneric(mapOf("only" to mapOf("a" to 1, "b" to 2)))
        assertTrue(":]" !in single, "single-member map was keyed: $single")

        // Two-member map of objects MUST key.
        val two = encodeGeneric(mapOf("x" to mapOf("a" to 1), "y" to mapOf("a" to 2)))
        assertTrue("[2:]{key,a}" in two, "two-member map was not keyed: $two")

        // A map with any non-object value falls back to sections.
        val mixed = encodeGeneric(mapOf("x" to mapOf("a" to 1), "y" to 5))
        assertTrue(":]" !in mixed, "map with a scalar value was keyed: $mixed")

        // A map whose value fields all contain '>' cannot key (no tabular column).
        val allGt = encodeGeneric(mapOf("a" to mapOf("x>y" to 1), "b" to mapOf("x>y" to 2)))
        assertTrue(":]" !in allGt, "all-'>'-field map was keyed: $allGt")

        // A map whose values are all empty objects has an empty field union.
        val allEmpty = encodeGeneric(mapOf("a" to emptyMap<String, Any?>(), "b" to emptyMap<String, Any?>()))
        assertTrue(":]" !in allEmpty, "all-empty-value map was keyed: $allEmpty")
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
