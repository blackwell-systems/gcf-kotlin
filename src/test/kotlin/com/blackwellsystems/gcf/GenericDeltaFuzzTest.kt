package com.blackwellsystems.gcf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Fuzz/property tests for generic-profile delta (mirrors gcf-go FuzzGeneric*):
//  A. decodeGenericDelta / decodeGenericFull never crash on arbitrary / mutated input.
//  B. arbitrary string cells survive the full-wire round-trip with the pack root preserved.
class GenericDeltaFuzzTest {
    // Code points (not Chars) so the astral emoji stays whole under Kotlin's UTF-16.
    private val alphabet: IntArray = "abcXYZ0129 .,-~^@#=|\t\n\r\"\\/éñ中🦞".codePoints().toArray()

    private fun randStr(rng: Random, maxlen: Int = 20): String =
        buildString { repeat(rng.nextInt(maxlen + 1)) { appendCodePoint(alphabet[rng.nextInt(alphabet.size)]) } }

    @Test
    fun fuzzStringCellRoundtrip() {
        val rng = Random(1234)
        repeat(20000) {
            val a = randStr(rng)
            val b = randStr(rng)
            val s = GenericSet(
                key = "id", name = "t", fields = listOf("id", "a", "b"),
                rows = listOf(
                    mapOf("id" to 1, "a" to a, "b" to b),
                    mapOf("id" to 2, "a" to b, "b" to a),
                ),
            )
            val (got, _) = decodeGenericFull(encodeGenericFull(s, ""))
            assertEquals(genericPackRoot(s), genericPackRoot(got), "a=$a b=$b")
        }
    }

    @Test
    fun fuzzDecodeNeverCrashes() {
        val rng = Random(99)
        val seeds = listOf(
            "GCF profile=generic delta=true base_root=a new_root=b key=id\n## added [1]{@id,x}\n1|2\n",
            "GCF profile=generic pack_root=r key=id\n## t [2]{@id,x}\n1|2\n3|4\n",
            "## removed [1]{@id}\n99\n",
            "",
        )
        repeat(20000) {
            val data = if (rng.nextDouble() < 0.5) {
                randStr(rng, 80)
            } else {
                val cps = seeds[rng.nextInt(seeds.size)].codePoints().toArray().toMutableList()
                repeat(rng.nextInt(6)) {
                    if (cps.isNotEmpty()) cps[rng.nextInt(cps.size)] = alphabet[rng.nextInt(alphabet.size)]
                }
                buildString { cps.forEach { appendCodePoint(it) } }
            }
            runCatching { decodeGenericDelta(data) }
            runCatching { decodeGenericFull(data) }
        }
    }
}
