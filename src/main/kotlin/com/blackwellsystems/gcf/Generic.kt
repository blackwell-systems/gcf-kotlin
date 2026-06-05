package com.blackwellsystems.gcf

/**
 * Encode any value into GCF tabular format.
 * Handles Map, List, String, Number, Boolean, and null.
 * Arrays of uniform maps get tabular rows. Nested maps use `## key` section headers.
 */
@Suppress("UNCHECKED_CAST")
fun encodeGeneric(data: Any?): String {
    if (data == null) return ""

    return when (data) {
        is Map<*, *> -> {
            val map = data as Map<String, Any?>
            val lines = encodeObjectEntries(map, 0)
            if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        }
        is List<*> -> {
            if (data.isEmpty()) return ""
            val lines = encodeArray(data, "root", 0)
            if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        }
        is String -> data
        is Number -> formatNumber(data)
        is Boolean -> data.toString()
        else -> data.toString()
    }
}

private fun formatNumber(n: Number): String {
    return when (n) {
        is Int, is Long, is Short, is Byte -> n.toLong().toString()
        is Float -> {
            val d = n.toDouble()
            if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
        }
        is Double -> {
            if (n == n.toLong().toDouble() && !n.isInfinite() && !n.isNaN()) {
                n.toLong().toString()
            } else {
                n.toString()
            }
        }
        else -> n.toString()
    }
}

private fun formatValue(v: Any?): String {
    return when (v) {
        null -> "-"
        is Boolean -> v.toString()
        is Number -> formatNumber(v)
        is String -> {
            if (v.isEmpty()) return "\"\""
            if (v.contains('|') || v.contains('\n')) {
                val escaped = v.replace("\"", "\\\"")
                return "\"$escaped\""
            }
            v
        }
        else -> "-"
    }
}

private fun indent(depth: Int): String = "  ".repeat(depth)

private fun isObject(v: Any?): Boolean = v is Map<*, *>

private fun isArray(v: Any?): Boolean = v is List<*>

@Suppress("UNCHECKED_CAST")
private fun isUniformObjectArray(arr: List<*>): Boolean {
    if (arr.isEmpty()) return false
    val first = arr[0]
    if (first !is Map<*, *>) return false
    val firstMap = first as Map<String, Any?>
    if (firstMap.isEmpty()) return false
    val firstKeys = firstMap.keys.toSet()

    val checkCount = minOf(arr.size, 5)
    for (i in 1 until checkCount) {
        val item = arr[i]
        if (item !is Map<*, *>) return false
        val itemMap = item as Map<String, Any?>
        val itemKeys = itemMap.keys.toSet()
        val overlap = firstKeys.intersect(itemKeys).size
        if (overlap.toDouble() < firstKeys.size.toDouble() * 0.7) return false
    }
    return true
}

@Suppress("UNCHECKED_CAST")
private fun encodeArray(arr: List<*>, name: String, depth: Int): List<String> {
    val prefix = indent(depth)

    if (arr.isEmpty()) {
        return if (name.isNotEmpty()) listOf("$prefix## $name [0]") else emptyList()
    }

    if (isUniformObjectArray(arr)) {
        return encodeTabular(arr, name, depth)
    }

    // Non-uniform array.
    val lines = mutableListOf<String>()
    if (name.isNotEmpty()) {
        lines.add("$prefix## $name [${arr.size}]")
    }
    arr.forEachIndexed { i, item ->
        when {
            isObject(item) -> {
                lines.add("$prefix@$i")
                lines.addAll(encodeObjectEntries(item as Map<String, Any?>, depth + 1))
            }
            isArray(item) -> {
                lines.addAll(encodeArray(item as List<*>, i.toString(), depth + 1))
            }
            else -> {
                lines.add("$prefix@$i ${formatValue(item)}")
            }
        }
    }
    return lines
}

@Suppress("UNCHECKED_CAST")
private fun encodeTabular(arr: List<*>, name: String, depth: Int): List<String> {
    val prefix = indent(depth)
    val first = arr[0] as Map<String, Any?>

    // Collect all keys from all items (preserving insertion order from first, then extras).
    val allKeys = linkedSetOf<String>()
    for (item in arr) {
        if (item is Map<*, *>) {
            for (k in (item as Map<String, Any?>).keys) {
                allKeys.add(k)
            }
        }
    }

    // Separate primitive from nested fields (sampled from first element).
    val primitiveFields = mutableListOf<String>()
    val nestedFields = mutableListOf<String>()
    for (key in allKeys) {
        val sample = first[key]
        if (isObject(sample) || isArray(sample)) {
            nestedFields.add(key)
        } else {
            primitiveFields.add(key)
        }
    }

    // Header.
    val lines = mutableListOf<String>()
    val fieldList = primitiveFields.joinToString(",")
    val header = if (name.isNotEmpty()) {
        "## $name [${arr.size}]{$fieldList}"
    } else {
        "## [${arr.size}]{$fieldList}"
    }
    lines.add("$prefix$header")

    val hasNested = nestedFields.isNotEmpty()

    for ((i, item) in arr.withIndex()) {
        if (item !is Map<*, *>) continue
        val obj = item as Map<String, Any?>

        val vals = primitiveFields.map { f ->
            val v = obj[f]
            if (v == null) "-" else formatValue(v)
        }

        val rowStr = vals.joinToString("|")

        if (hasNested) {
            lines.add("$prefix@$i $rowStr")
            // Inline nested fields after the row.
            for (nf in nestedFields) {
                val nv = obj[nf] ?: continue
                when {
                    isArray(nv) -> lines.addAll(encodeArray(nv as List<*>, nf, depth + 1))
                    isObject(nv) -> {
                        lines.add("${indent(depth + 1)}.$nf")
                        lines.addAll(encodeObjectEntries(nv as Map<String, Any?>, depth + 2))
                    }
                }
            }
        } else {
            lines.add("$prefix$rowStr")
        }
    }
    return lines
}

@Suppress("UNCHECKED_CAST")
private fun encodeObjectEntries(map: Map<String, Any?>, depth: Int): List<String> {
    val prefix = indent(depth)
    val lines = mutableListOf<String>()

    for ((key, value) in map.toSortedMap()) {
        if (value == null) continue
        when {
            isArray(value) -> lines.addAll(encodeArray(value as List<*>, key, depth))
            isObject(value) -> {
                lines.add("${indent(depth + 1)}## $key")
                lines.addAll(encodeObjectEntries(value as Map<String, Any?>, depth + 2))
            }
            else -> lines.add("$prefix$key=${formatValue(value)}")
        }
    }
    return lines
}
