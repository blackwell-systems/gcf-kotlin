package com.blackwellsystems.gcf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// Targeted fuzz/property coverage for spec v3.6.0 (constant-column factoring SPEC 7.4.7
// and value-grouping SPEC 7.4.8), mirroring gcf-go constant_grouping_fuzz_test.go. The
// default-path round-trips already exercise constant-factoring because it rides the
// default encoder, but value-grouping is opt-in (encodeGenericGrouped) and is touched by
// no other property test, and neither new grammar had a decoder-robustness (mutation)
// fuzz. These close both gaps. Per-SDK fuzz is required.
class ConstantGroupedFuzzTest {
    private val iterations = System.getenv("GCF_ITERATIONS")?.toIntOrNull() ?: 100_000

    // hazardStrings mimic v3.6.0 syntax tokens (clauses, subheaders, structural markers)
    // so the generators can plant them as field names and values. The point is
    // discrimination: a value or name that LOOKS like a grouping clause, a constant entry,
    // a subheader, or another shape's marker must still decode as plain data, never
    // reclassify the payload. Includes the ^{ family (SPEC 7.4.7.2 attachment-marker
    // predicate): bare ^, complete ^{...}, and the incomplete ^{-prefix literals.
    private val hazardStrings = listOf(
        "group=dept", "group=", "region=us-east", "= [1]", "k=v [1]",
        "dept=Sales [2]", "}", "{a}", "[2]", "[2:]", "[0]", "[?]",
        "## section", ".field", "@id", "@0", "a|b", "-", "~",
        "^", "^{abc", "^{a}", "^{", "^x", "^{a,b}",
    )

    private fun genAdversarialScalar(rng: Random): Any? = when (rng.nextInt(8)) {
        0 -> null
        1 -> rng.nextBoolean()
        2 -> (rng.nextInt(4000) - 2000).toLong()
        3 -> rng.nextDouble() * 2000 - 2000
        4 -> ""
        5 -> listOf(",", "|", "\"", "}", "{", " x", "x ", "-", "~").let { it[rng.nextInt(it.size)] }
        6 -> (0 until rng.nextInt(10)).joinToString("") { genBareKey(rng) }
        else -> listOf("é", "中", "🦞", "a\tb", "a\nb").let { it[rng.nextInt(it.size)] }
    }

    private fun genScalar(rng: Random): Any? = when (rng.nextInt(6)) {
        0 -> null
        1 -> rng.nextBoolean()
        2 -> (rng.nextInt(2000) - 1000).toLong()
        3 -> rng.nextDouble() * 2000 - 1000
        4 -> genBareKey(rng)
        else -> (0 until rng.nextInt(10)).joinToString("") { genBareKey(rng) }
    }

    private val bareAlphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_"

    private fun genBareKey(rng: Random): String {
        val n = 1 + rng.nextInt(6)
        val sb = StringBuilder()
        sb.append(bareAlphabet[rng.nextInt(26 * 2 + 1)])
        repeat(n - 1) { sb.append("abcdefghijklmnopqrstuvwxyz0123456789_"[rng.nextInt(37)]) }
        return sb.toString()
    }

    // genKey returns bare keys plus adversarial keys that require quoting (incl names
    // containing "=", which must NOT be read as a constant-column separator, "", "|").
    private fun genKey(rng: Random): String = when (rng.nextInt(6)) {
        0 -> ""
        1 -> "a=b"
        2 -> "a|b"
        3 -> "x>y"
        4 -> "\"q\""
        else -> genBareKey(rng)
    }

    private fun hazardValue(rng: Random): Any? =
        if (rng.nextInt(3) == 0) hazardStrings[rng.nextInt(hazardStrings.size)] else genAdversarialScalar(rng)

    // genFieldName returns mostly bare keys, sometimes a quoting-required key or a hazard
    // name. Never returns a name already used.
    private fun genFieldName(rng: Random, used: MutableSet<String>): String {
        while (true) {
            val f = when (rng.nextInt(4)) {
                0 -> genKey(rng)
                1 -> hazardStrings[rng.nextInt(hazardStrings.size)]
                else -> genBareKey(rng)
            }
            if (used.add(f)) return f
        }
    }

    // --- constant-column factoring: constant-biased generator ---

    // genConstBiasedArray builds a tabular array (>=2 records, scalar leaves only) in which
    // a random subset of fields is held constant across every record, drawing constant
    // values from the adversarial pool so the factored value hits the quoting path. Sometimes
    // every field is constant, exercising the all-constant / last-column-retained edge.
    private fun genConstBiasedArray(rng: Random): List<Any?> {
        val n = 2 + rng.nextInt(6) // 2..7 records
        val k = 1 + rng.nextInt(5) // 1..5 fields
        val fields = mutableListOf<String>()
        val used = mutableSetOf<String>()
        while (fields.size < k) fields.add(genFieldName(rng, used))
        val constVal = linkedMapOf<String, Any?>()
        val forceAll = rng.nextInt(8) == 0 // ~12% all-constant
        for (f in fields) if (forceAll || rng.nextInt(2) == 0) constVal[f] = hazardValue(rng)
        return (0 until n).map {
            val rec = linkedMapOf<String, Any?>()
            for (f in fields) {
                rec[f] = when {
                    f in constVal -> constVal[f]
                    rng.nextInt(4) == 0 -> hazardValue(rng)
                    else -> genScalar(rng)
                }
            }
            rec
        }
    }

    // headerHasFactoredColumn reports whether the first tabular header has a name=value entry.
    private fun headerHasFactoredColumn(gcf: String): Boolean {
        for (line in gcf.split("\n")) {
            if (line.startsWith("## ")) {
                val open = line.indexOf('{')
                val close = line.lastIndexOf('}')
                if (open >= 0 && close > open && line.substring(open, close).indexOf('=') >= 0) return true
            }
        }
        return false
    }

    @Test
    fun `constant-biased roundtrip`() {
        val rng = Random(0xC0)
        var factored = 0
        for (i in 0 until iterations) {
            val value = genConstBiasedArray(rng)
            val gcf = encodeGeneric(value)
            if (headerHasFactoredColumn(gcf)) factored++
            val decoded = decodeGeneric(gcf)
            assertTrue(structuralEqual(value, decoded),
                "iter $i: constant-biased round-trip mismatch\n  input: $value\n  gcf: $gcf\n  decoded: $decoded")
        }
        assertTrue(factored > 0, "coverage gap: no factored headers produced in $iterations iterations")
    }

    // --- value-grouping: keyed-set generator ---

    // genGroupedSet builds a keyed set suitable for encodeGenericGrouped: a unique key field,
    // a low-cardinality group field (values drawn adversarially, incl null, brackets, commas,
    // "="), and 0..3 extra scalar fields some of which may be constant. Key uniqueness is by
    // construction, so the encoder never errors on this input.
    private fun genGroupedSet(rng: Random): Triple<List<Any?>, String, String> {
        val keyField = "k"
        val groupField = "g"
        val n = 2 + rng.nextInt(8) // 2..9 records
        val poolSize = 1 + rng.nextInt(4)
        val pool = mutableListOf<Any?>()
        val seen = mutableSetOf<String>()
        while (pool.size < poolSize) {
            val v = hazardValue(rng)
            val kkey = "${v?.javaClass?.name}/$v"
            if (seen.add(kkey)) pool.add(v)
        }
        val extraN = rng.nextInt(4)
        val extras = mutableListOf<String>()
        val used = mutableSetOf("k", "g")
        while (extras.size < extraN) extras.add(genFieldName(rng, used))
        val extraConst = linkedMapOf<String, Any?>()
        for (f in extras) if (rng.nextInt(2) == 0) extraConst[f] = hazardValue(rng)
        val arr = (0 until n).map { i ->
            val rec = linkedMapOf<String, Any?>()
            rec[keyField] = "k%04d".format(i)
            rec[groupField] = pool[rng.nextInt(pool.size)]
            for (f in extras) {
                rec[f] = when {
                    f in extraConst -> extraConst[f]
                    rng.nextInt(4) == 0 -> hazardValue(rng)
                    else -> genScalar(rng)
                }
            }
            rec
        }
        return Triple(arr, keyField, groupField)
    }

    @Suppress("UNCHECKED_CAST")
    private fun recField(rec: Any?, name: String): Any? = (rec as? Map<String, Any?>)?.get(name)

    private fun sortByKey(arr: List<Any?>, keyField: String): List<Any?> =
        arr.sortedBy { recField(it, keyField).toString() }

    @Test
    fun `grouped keyed-set roundtrip`() {
        val rng = Random(0x6C)
        for (i in 0 until iterations) {
            val (value, kf, gf) = genGroupedSet(rng)
            val gcf = try {
                encodeGenericGrouped(value, kf, gf)
            } catch (e: Exception) {
                fail("iter $i: encodeGenericGrouped failed on valid keyed set: ${e.message}\n  input: $value")
            }
            val decodedAny = decodeGeneric(gcf)
            val decoded = decodedAny as? List<Any?>
                ?: fail("iter $i: grouped decode did not yield a list: $decodedAny")
            assertTrue(decoded.size == value.size,
                "iter $i: record count ${decoded.size} != ${value.size}\n  gcf: $gcf")
            // Compare as a set keyed by kf: sort both by key, then order-insensitive deep-equal.
            val inSorted = sortByKey(value, kf)
            val outSorted = sortByKey(decoded, kf)
            assertTrue(structuralEqual(inSorted, outSorted),
                "iter $i: grouped round-trip mismatch\n  input: $inSorted\n  gcf: $gcf\n  decoded: $outSorted")
        }
    }

    // --- decoder robustness (mutation) ---

    private val structuralBytes = "|{}[]=@#.-~\"\n ".toByteArray(Charsets.ISO_8859_1)

    // mutate mirrors gcf-go mutate: flip, delete, insert-structural, truncate, duplicate a
    // fragment, or random byte. Implemented with ByteArray splices (not list copies) to keep
    // per-iteration allocation low across the full fuzz run.
    private fun mutate(rng: Random, b: ByteArray): ByteArray {
        if (b.isEmpty()) return byteArrayOf(rng.nextInt(128).toByte())
        return when (rng.nextInt(6)) {
            0 -> { val i = rng.nextInt(b.size); b[i] = (b[i].toInt() xor (1 shl rng.nextInt(8))).toByte(); b }
            1 -> { // delete a byte
                val i = rng.nextInt(b.size)
                val out = ByteArray(b.size - 1)
                System.arraycopy(b, 0, out, 0, i)
                System.arraycopy(b, i + 1, out, i, b.size - i - 1)
                out
            }
            2 -> { // insert a structural byte
                val i = rng.nextInt(b.size + 1)
                val ch = structuralBytes[rng.nextInt(structuralBytes.size)]
                val out = ByteArray(b.size + 1)
                System.arraycopy(b, 0, out, 0, i)
                out[i] = ch
                System.arraycopy(b, i, out, i + 1, b.size - i)
                out
            }
            3 -> b.copyOfRange(0, rng.nextInt(b.size)) // truncate
            4 -> { // duplicate a tail fragment
                val i = rng.nextInt(b.size)
                val tail = b.size - i
                val out = ByteArray(b.size + tail)
                System.arraycopy(b, 0, out, 0, b.size)
                System.arraycopy(b, i, out, b.size, tail)
                out
            }
            else -> { val i = rng.nextInt(b.size); b[i] = rng.nextInt(128).toByte(); b }
        }
    }

    // Mutates valid factored/grouped wire and requires the decoder to error cleanly, never
    // crash, on the result. An uncaught Error (e.g. StackOverflow/OOM) is a parser bug; a
    // thrown IllegalArgumentException/other Exception is acceptable.
    @Test
    fun `mutated wire never crashes the decoder`() {
        val rng = Random(0xF0)
        for (i in 0 until iterations) {
            val wire: String = if (rng.nextInt(2) == 0) {
                encodeGeneric(genConstBiasedArray(rng))
            } else {
                val (arr, kf, gf) = genGroupedSet(rng)
                try { encodeGenericGrouped(arr, kf, gf) } catch (e: Exception) { continue }
            }
            var b = wire.toByteArray(Charsets.UTF_8)
            var m = 1 + rng.nextInt(4)
            while (m > 0) { b = mutate(rng, b); m-- }
            val input = String(b, Charsets.UTF_8)
            try {
                decodeGeneric(input) // an exception is fine; a crash is not
            } catch (e: Exception) {
                // expected: malformed input rejected
            } catch (e: StackOverflowError) {
                fail("iter $i: StackOverflowError on mutated input: ${input.take(500)}")
            }
        }
    }

    // Pins that v3.6.0 markers do not reclassify a payload of another shape: an @-marked
    // field without a group= clause stays invalid (not silently grouped), a keyed map stays
    // a map (not read as grouped), and a flat tabular array stays flat.
    @Test
    fun `shape discrimination`() {
        // @-key field without group= must be rejected, not treated as a grouped section.
        try {
            decodeGeneric("GCF profile=generic\n## [2]{@id,x}\nu1|1\nu2|2\n")
            fail("@-marked field without group= should be rejected")
        } catch (e: Exception) {
            assertTrue("invalid field name" in e.message.orEmpty(),
                "unexpected error category for @-without-group: ${e.message}")
        }
        // Keyed map [N:] decodes to an object, not a list; the group= path must not intercept.
        val got = decodeGeneric("GCF profile=generic\n## [2:]{key,x}\na|1\nb|2\n")
        assertTrue(got is Map<*, *>, "keyed map [N:] decoded as ${got?.javaClass}, want Map")

        // A flat tabular array with no constant column stays flat and round-trips.
        val flat = listOf(
            linkedMapOf<String, Any?>("id" to "u1", "r" to "a"),
            linkedMapOf<String, Any?>("id" to "u2", "r" to "b"),
        )
        val wire = encodeGeneric(flat)
        assertTrue(!headerHasFactoredColumn(wire), "flat array with varying columns should not factor: $wire")
        assertTrue(structuralEqual(flat, decodeGeneric(wire)), "flat round-trip failed: wire=$wire")
    }

    @Suppress("UNCHECKED_CAST")
    private fun structuralEqual(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        if (a is Map<*, *> && b is Map<*, *>) {
            val am = a.keys.map { it.toString() }.toSortedSet()
            val bm = b.keys.map { it.toString() }.toSortedSet()
            if (am != bm) return false
            return am.all { structuralEqual((a as Map<String, Any?>)[it], (b as Map<String, Any?>)[it]) }
        }
        if (a is List<*> && b is List<*>) {
            if (a.size != b.size) return false
            return a.zip(b).all { (x, y) -> structuralEqual(x, y) }
        }
        return a == b
    }
}
