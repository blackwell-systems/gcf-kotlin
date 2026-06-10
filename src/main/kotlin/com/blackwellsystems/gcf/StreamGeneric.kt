package com.blackwellsystems.gcf

import java.io.Writer

/**
 * GenericStreamEncoder writes GCF tabular output incrementally as rows arrive.
 * Zero buffering: each row is written immediately. A trailer summary is
 * emitted on close() with the final counts. Thread-safe via @Synchronized.
 *
 * Usage:
 * ```
 * val enc = GenericStreamEncoder(writer)
 * enc.beginArray("employees", listOf("id", "name", "department", "salary"))
 * enc.writeRow(listOf(1, "Alice", "Engineering", 95000))
 * enc.writeRow(listOf(2, "Bob", "Sales", 72000))
 * enc.endArray()
 * enc.close()
 * ```
 */
class GenericStreamEncoder(private val writer: Writer) {
    private val sections = mutableListOf<Pair<String, Int>>()
    private var current: ActiveArray? = null

    private data class ActiveArray(
        val name: String,
        val fields: List<String>,
        var count: Int = 0
    )

    /** Start a tabular array section with deferred count [?]. */
    @Synchronized
    fun beginArray(name: String, fields: List<String>) {
        if (current != null) {
            endArrayInternal()
        }
        writer.write("## $name [?]{${fields.joinToString(",")}}\n")
        writer.flush()
        current = ActiveArray(name, fields)
    }

    /** Emit a single pipe-separated row immediately. */
    @Synchronized
    fun writeRow(values: List<Any?>) {
        val cur = current ?: return
        val parts = values.map { formatValue(it) }
        writer.write("${parts.joinToString("|")}\n")
        writer.flush()
        cur.count++
    }

    /** Close the current array section and record its count. */
    @Synchronized
    fun endArray() {
        endArrayInternal()
    }

    /** Emit a key=value line immediately. */
    @Synchronized
    fun writeKV(key: String, value: Any?) {
        writer.write("$key=${formatValue(value)}\n")
        writer.flush()
    }

    /** Start a nested object section (## key). */
    @Synchronized
    fun writeSection(name: String) {
        if (current != null) {
            endArrayInternal()
        }
        writer.write("## $name\n")
        writer.flush()
    }

    /** Emit a primitive array inline: name[N]: val1,val2,val3 */
    @Synchronized
    fun writeInlineArray(name: String, values: List<Any?>) {
        val parts = values.map { formatValue(it) }
        writer.write("$name[${values.size}]: ${parts.joinToString(",")}\n")
        writer.flush()
    }

    /** Emit the ##! summary trailer with final counts. */
    @Synchronized
    fun close() {
        if (current != null) {
            endArrayInternal()
        }
        if (sections.isEmpty()) return

        val counts = sections.map { (_, count) -> count.toString() }
        writer.write("##! summary counts=${counts.joinToString(",")}\n")
        writer.flush()
    }

    private fun endArrayInternal() {
        val cur = current ?: return
        sections.add(cur.name to cur.count)
        current = null
    }

    companion object {
        private fun formatValue(v: Any?): String {
            if (v == null) return "-"
            return when (v) {
                is Boolean -> if (v) "true" else "false"
                is Int -> v.toString()
                is Long -> v.toString()
                is Double -> {
                    if (v == v.toLong().toDouble() && !v.isInfinite()) {
                        v.toLong().toString()
                    } else {
                        v.toString()
                    }
                }
                is Float -> {
                    val d = v.toDouble()
                    if (d == d.toLong().toDouble() && !d.isInfinite()) {
                        d.toLong().toString()
                    } else {
                        v.toString()
                    }
                }
                is String -> {
                    if (v.isEmpty()) return "\"\""
                    if (v.contains("|") || v.contains("\n")) {
                        return "\"${v.replace("\"", "\\\"")}\""
                    }
                    v
                }
                else -> v.toString()
            }
        }
    }
}
