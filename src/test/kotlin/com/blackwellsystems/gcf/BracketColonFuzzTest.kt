package com.blackwellsystems.gcf

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BracketColonFuzzTest {
    @Test
    fun `adversarial bracket-colon strings round-trip`() {
        val adversarial = listOf(
            "ERR[404]: Not Found",
            "[Speaker 1]: Hello",
            "[0]: looks like array",
            "[100]: big number",
            "value[0] ok",
            "has:colon",
            "ERR[404]: Not Found and [500]: Server Error",
            "[]: empty",
            "[ 0 ]: spaced",
            "[[0]]: nested",
            "at end [0]:",
            "ERROR[ENOENT]: File not found",
            "port[443]: HTTPS",
            "field[name]: John",
        )
        for (s in adversarial) {
            val obj = mapOf("v" to s)
            val encoded = encodeGeneric(obj)
            val decoded = decodeGeneric(encoded)
            assertEquals(obj, decoded, "Failed on: $s")
        }
    }

    @Test
    fun `10M random bracket-colon strings round-trip`() {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789 _-.[]{}():=|,'\"\\/@ #"
        val rng = java.util.Random(42)
        var pass = 0
        var fail = 0
        for (i in 0 until 10_000_000) {
            val len = 1 + rng.nextInt(40)
            val s = (1..len).map { chars[rng.nextInt(chars.length)] }.joinToString("")
            val obj = mapOf("v" to s)
            try {
                val encoded = encodeGeneric(obj)
                val decoded = decodeGeneric(encoded)
                if (decoded == obj) pass++ else fail++
            } catch (e: Exception) {
                fail++
                if (fail <= 5) println("Error on ${s.take(60)}: ${e.message?.take(80)}")
            }
        }
        assertEquals(0, fail, "$fail failures out of 10,000,000")
    }
}
