package com.blackwellsystems.gcf

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertTrue

class RoundtripV2Test {

    private val iterations = System.getenv("GCF_ITERATIONS")?.toIntOrNull() ?: 100_000

    @Test
    fun `random roundtrip`() {
        val rng = Random(42)
        for (i in 0 until iterations) {
            val v = genValue(rng, 0, 4)
            for (noFlatten in listOf(false, true)) {
                val gcf = encodeGeneric(v, GenericOptions(noFlatten = noFlatten))
                val decoded = decodeGeneric(gcf)
                assertTrue(structuralEqual(v, decoded),
                    "iteration $i noFlatten=$noFlatten: mismatch\n  input: $v\n  decoded: $decoded\n  gcf: $gcf")
            }
        }
    }

    @Test
    fun `adversarial roundtrip`() {
        val rng = Random(99)
        val collisions = listOf("true", "false", "-", "~", "^", "0", "123", "-0", "1e10",
            "", " ", "#", "@0", "+1", ".5", "01", "a|b", "a,b", "\n", "\t")
        for (i in 0 until iterations) {
            val v = if (rng.nextFloat() < 0.3) collisions[rng.nextInt(collisions.size)]
                    else genValue(rng, 0, 3)
            for (noFlatten in listOf(false, true)) {
                val gcf = encodeGeneric(v, GenericOptions(noFlatten = noFlatten))
                val decoded = decodeGeneric(gcf)
                assertTrue(structuralEqual(v, decoded),
                    "iteration $i noFlatten=$noFlatten: mismatch\n  input: $v\n  decoded: $decoded\n  gcf: $gcf")
            }
        }
    }

    private fun genValue(rng: Random, depth: Int, maxDepth: Int): Any? {
        if (depth >= maxDepth) return genScalar(rng)
        return when (rng.nextInt(10)) {
            0 -> null
            1 -> rng.nextBoolean()
            2 -> genNumber(rng)
            3, 4 -> genString(rng)
            5, 6 -> genObject(rng, depth, maxDepth)
            7, 8 -> genArray(rng, depth, maxDepth)
            else -> genScalar(rng)
        }
    }

    private fun genScalar(rng: Random): Any? = when (rng.nextInt(5)) {
        0 -> null
        1 -> rng.nextBoolean()
        2 -> genNumber(rng)
        else -> genString(rng)
    }

    private fun genNumber(rng: Random): Any = when (rng.nextInt(6)) {
        0 -> 0
        1 -> rng.nextInt(1000)
        2 -> -rng.nextInt(1000)
        3 -> rng.nextInt(1000000) + rng.nextDouble()
        4 -> (rng.nextInt(999) + 1).toDouble() * 1e18
        else -> rng.nextDouble() * 2000 - 1000
    }

    private val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val special = " |,=\"\\#@\n\t~^+-.>"

    private fun genString(rng: Random): String {
        val n = rng.nextInt(20)
        return (0 until n).map {
            if (rng.nextFloat() < 0.2) special[rng.nextInt(special.length)]
            else chars[rng.nextInt(chars.length)]
        }.joinToString("")
    }

    private fun genBareKey(rng: Random): String {
        val kchars = "abcdefghijklmnopqrstuvwxyz_"
        val n = 1 + rng.nextInt(8)
        return (0 until n).map { kchars[rng.nextInt(kchars.length)] }.joinToString("")
    }

    private fun genObject(rng: Random, depth: Int, maxDepth: Int): Map<String, Any?> {
        val n = rng.nextInt(6)
        val m = linkedMapOf<String, Any?>()
        repeat(n) {
            val k = genBareKey(rng)
            if (k !in m) m[k] = genValue(rng, depth + 1, maxDepth)
        }
        return m
    }

    private fun genArray(rng: Random, depth: Int, maxDepth: Int): List<Any?> {
        val n = rng.nextInt(6)
        return when (rng.nextInt(4)) {
            0 -> (0 until n).map { genScalar(rng) }
            1 -> {
                val fields = (0 until 1 + rng.nextInt(4)).map { genBareKey(rng) }
                (0 until n).map { linkedMapOf<String, Any?>().also { obj ->
                    for (f in fields) if (rng.nextFloat() > 0.2) obj[f] = genScalar(rng)
                }}
            }
            2 -> (0 until n).map { linkedMapOf<String, Any?>(genBareKey(rng) to genScalar(rng)).also { obj ->
                if (rng.nextFloat() < 0.3 && depth + 1 < maxDepth) obj[genBareKey(rng)] = genValue(rng, depth + 2, maxDepth)
            }}
            else -> (0 until n).map { genValue(rng, depth + 1, maxDepth) }
        }
    }

    // Aligned arrays whose shared fields are fixed-shape nested objects, with a field
    // or an intermediate nested level sometimes null/absent — the v3.2 flatten path the
    // scalar-only generator above never produces.
    @Test
    fun `flatten roundtrip`() {
        val rng = Random(7)
        for (i in 0 until iterations) {
            val v = genFlattenableArray(rng)
            for (noFlatten in listOf(false, true)) {
                val gcf = encodeGeneric(v, GenericOptions(noFlatten = noFlatten))
                val decoded = decodeGeneric(gcf)
                assertTrue(structuralEqual(v, decoded),
                    "iteration $i noFlatten=$noFlatten: mismatch\n  input: $v\n  decoded: $decoded\n  gcf: $gcf")
            }
        }
    }

    private sealed class FlatShape {
        object Scalar : FlatShape()
        class Nested(val sub: List<Pair<String, FlatShape>>) : FlatShape()
    }

    private fun genFlatShape(rng: Random, depth: Int, maxDepth: Int): FlatShape {
        if (depth >= maxDepth || rng.nextFloat() < 0.45) return FlatShape.Scalar
        val sub = mutableListOf<Pair<String, FlatShape>>()
        val seen = mutableSetOf<String>()
        repeat(1 + rng.nextInt(3)) {
            val k = genBareKey(rng)
            if (seen.add(k)) sub.add(k to genFlatShape(rng, depth + 1, maxDepth))
        }
        return if (sub.isEmpty()) FlatShape.Scalar else FlatShape.Nested(sub)
    }

    private fun materializeFlatShape(rng: Random, shape: FlatShape): Any? = when (shape) {
        is FlatShape.Scalar -> genScalar(rng)
        is FlatShape.Nested -> linkedMapOf<String, Any?>().also { m ->
            for ((k, s) in shape.sub) {
                // A nested sub-object is sometimes null (intermediate null — the case the
                // pre-fix encoder dropped) instead of a full object.
                m[k] = if (s !is FlatShape.Scalar && rng.nextFloat() < 0.3) null
                       else materializeFlatShape(rng, s)
            }
        }
    }

    private fun genFlattenableArray(rng: Random): List<Any?> {
        val schema = mutableListOf<Pair<String, FlatShape>>("id" to FlatShape.Scalar)
        val seen = mutableSetOf("id")
        var hasNested = false
        repeat(1 + rng.nextInt(3)) {
            val k = genBareKey(rng)
            if (seen.add(k)) {
                val s = genFlatShape(rng, 1, 3)
                if (s is FlatShape.Nested) hasNested = true
                schema.add(k to s)
            }
        }
        if (!hasNested) {
            val k = genBareKey(rng)
            schema.add(k to FlatShape.Nested(listOf(
                genBareKey(rng) to FlatShape.Nested(listOf(genBareKey(rng) to FlatShape.Scalar)))))
        }
        val rows = 2 + rng.nextInt(6)
        return (0 until rows).map {
            linkedMapOf<String, Any?>().also { m ->
                for ((f, s) in schema) {
                    val x = rng.nextFloat()
                    when {
                        x < 0.12 -> {} // field absent this row
                        x < 0.24 -> m[f] = null // field present-null (top-level null)
                        else -> m[f] = materializeFlatShape(rng, s)
                    }
                }
            }
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
            return am.all { structuralEqual((a as Map<String, Any?>)[it], (b as Map<String, Any?>)[it]) }
        }
        if (a is List<*> && b is List<*>) {
            if (a.size != b.size) return false
            return a.zip(b).all { (x, y) -> structuralEqual(x, y) }
        }
        return a == b
    }
}
