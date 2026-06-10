package com.blackwellsystems.gcf

/**
 * Encode any value into GCF v2.0 generic profile.
 * Handles Map, List, String, Number, Boolean, and null.
 */
@Suppress("UNCHECKED_CAST")
fun encodeGeneric(data: Any?): String {
    val out = StringBuilder("GCF profile=generic\n")
    encodeRootValue(data, out)
    return out.toString()
}

private fun encodeRootValue(v: Any?, out: StringBuilder) {
    when {
        v == null -> out.append("=-\n")
        v is Map<*, *> -> encodeObject(v as Map<String, Any?>, out, 0)
        v is List<*> -> encodeRootArray(v, out)
        else -> { out.append("="); out.append(formatScalarValue(v)); out.append("\n") }
    }
}

@Suppress("UNCHECKED_CAST")
private fun encodeObject(map: Map<String, Any?>, out: StringBuilder, depth: Int) {
    val prefix = indent(depth)
    for ((key, value) in map) {
        val fk = formatKeyValue(key)
        when {
            value is Map<*, *> -> {
                out.append("${prefix}## $fk\n")
                encodeObject(value as Map<String, Any?>, out, depth + 1)
            }
            value is List<*> -> encodeNamedArray(fk, value, out, depth)
            else -> out.append("$prefix$fk=${formatScalarValue(value)}\n")
        }
    }
}

private fun encodeRootArray(arr: List<*>, out: StringBuilder) {
    if (arr.isEmpty()) { out.append("## [0]\n"); return }
    if (allPrimitives(arr)) {
        val vals = arr.joinToString(",") { formatScalarValue(it, ',') }
        out.append("## [${arr.size}]: $vals\n"); return
    }
    val fields = tabularFields(arr)
    if (fields != null) { encodeTabular("## ", arr, fields, out, 0); return }
    encodeExpanded("## ", arr, out, 0)
}

private fun encodeNamedArray(name: String, arr: List<*>, out: StringBuilder, depth: Int) {
    val prefix = indent(depth)
    if (arr.isEmpty()) { out.append("${prefix}## $name [0]\n"); return }
    if (allPrimitives(arr)) {
        val vals = arr.joinToString(",") { formatScalarValue(it, ',') }
        out.append("$prefix${name}[${arr.size}]: $vals\n"); return
    }
    val fields = tabularFields(arr)
    if (fields != null) { encodeTabular("${prefix}## $name ", arr, fields, out, depth); return }
    encodeExpanded("${prefix}## $name ", arr, out, depth)
}

@Suppress("UNCHECKED_CAST")
private fun tabularFields(arr: List<*>): List<String>? {
    if (arr.isEmpty()) return null
    val fieldOrder = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (item in arr) {
        val map = item as? Map<*, *> ?: return null
        for (k in map.keys) {
            val key = k as? String ?: return null
            if (key !in seen) { fieldOrder.add(key); seen.add(key) }
        }
    }
    return if (fieldOrder.isEmpty()) null else fieldOrder
}

@Suppress("UNCHECKED_CAST")
private fun encodeTabular(headerPrefix: String, arr: List<*>, fields: List<String>, out: StringBuilder, depth: Int) {
    val prefix = indent(depth)
    val fmtFields = fields.joinToString(",") { formatKeyValue(it) }
    out.append("$headerPrefix[${arr.size}]{$fmtFields}\n")

    for ((i, item) in arr.withIndex()) {
        val map = item as? Map<String, Any?> ?: continue
        val cells = mutableListOf<String>()
        val attachments = mutableListOf<Pair<String, Any>>()
        var rowHasAttachment = false

        for (f in fields) {
            if (f !in map) { cells.add("~"); continue }
            val v = map[f]
            if (v == null) { cells.add("-"); continue }
            if (v is Map<*, *> || v is List<*>) {
                cells.add("^")
                attachments.add(f to v)
                rowHasAttachment = true
            } else {
                cells.add(formatScalarValue(v, '|'))
            }
        }

        val row = cells.joinToString("|")
        if (rowHasAttachment) out.append("${prefix}@$i $row\n") else out.append("$prefix$row\n")

        for ((attName, attVal) in attachments) {
            val attPrefix = "$prefix  "
            val fk = formatKeyValue(attName)
            when (attVal) {
                is Map<*, *> -> {
                    out.append("$attPrefix.$fk {}\n")
                    encodeObject(attVal as Map<String, Any?>, out, depth + 2)
                }
                is List<*> -> encodeAttachmentArray(attPrefix, fk, attVal, out, depth + 2)
            }
        }
    }
}

private fun encodeAttachmentArray(attPrefix: String, fk: String, arr: List<*>, out: StringBuilder, depth: Int) {
    if (arr.isEmpty()) { out.append("$attPrefix.$fk [0]\n"); return }
    if (allPrimitives(arr)) {
        val vals = arr.joinToString(",") { formatScalarValue(it, ',') }
        out.append("$attPrefix.$fk [${arr.size}]: $vals\n"); return
    }
    val fields = tabularFields(arr)
    if (fields != null) { encodeTabular("$attPrefix.$fk ", arr, fields, out, depth); return }
    encodeExpanded("$attPrefix.$fk ", arr, out, depth)
}

@Suppress("UNCHECKED_CAST")
private fun encodeExpanded(headerPrefix: String, arr: List<*>, out: StringBuilder, depth: Int) {
    val prefix = indent(depth)
    out.append("$headerPrefix[${arr.size}]\n")
    for ((i, item) in arr.withIndex()) {
        when {
            item is Map<*, *> -> {
                out.append("${prefix}@$i {}\n")
                encodeObject(item as Map<String, Any?>, out, depth + 1)
            }
            item is List<*> -> encodeExpandedArrayItem(prefix, i, item, out, depth)
            else -> out.append("${prefix}@$i =${formatScalarValue(item)}\n")
        }
    }
}

private fun encodeExpandedArrayItem(prefix: String, idx: Int, arr: List<*>, out: StringBuilder, depth: Int) {
    if (arr.isEmpty()) { out.append("${prefix}@$idx [0]\n"); return }
    if (allPrimitives(arr)) {
        val vals = arr.joinToString(",") { formatScalarValue(it, ',') }
        out.append("${prefix}@$idx [${arr.size}]: $vals\n"); return
    }
    val fields = tabularFields(arr)
    if (fields != null) { encodeTabular("${prefix}@$idx ", arr, fields, out, depth + 1); return }
    encodeExpanded("${prefix}@$idx ", arr, out, depth + 1)
}

private fun allPrimitives(arr: List<*>): Boolean = arr.all { it !is Map<*, *> && it !is List<*> }

private fun indent(depth: Int): String = "  ".repeat(depth)
