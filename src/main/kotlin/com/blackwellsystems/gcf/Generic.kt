package com.blackwellsystems.gcf

/**
 * Options for controlling generic encoding behavior.
 */
data class GenericOptions(
    /** When true, disables promotion of fixed-shape nested objects to path
     *  columns (e.g. "customer>name"). Nested objects use attachment syntax
     *  instead. Open-weight models currently comprehend the expanded form
     *  better; this gap is expected to close. */
    val noFlatten: Boolean = false
)

/**
 * Encode any value into GCF generic profile.
 * Handles Map, List, String, Number, Boolean, and null.
 */
@Suppress("UNCHECKED_CAST")
fun encodeGeneric(data: Any?, opts: GenericOptions = GenericOptions()): String {
    val out = StringBuilder("GCF profile=generic\n")
    encodeRootValue(data, out, opts)
    return out.toString()
}

private fun encodeRootValue(v: Any?, out: StringBuilder, opts: GenericOptions) {
    when {
        v == null -> out.append("=-\n")
        v is Map<*, *> -> encodeObject(v as Map<String, Any?>, out, 0, opts)
        v is List<*> -> encodeRootArray(v, out, opts)
        else -> { out.append("="); out.append(formatScalarValue(v)); out.append("\n") }
    }
}

@Suppress("UNCHECKED_CAST")
private fun encodeObject(map: Map<String, Any?>, out: StringBuilder, depth: Int, opts: GenericOptions) {
    val prefix = indent(depth)
    for ((key, value) in map) {
        val fk = formatKeyValue(key)
        when {
            value is Map<*, *> -> {
                out.append("${prefix}## $fk\n")
                encodeObject(value as Map<String, Any?>, out, depth + 1, opts)
            }
            value is List<*> -> encodeNamedArray(fk, value, out, depth, opts)
            else -> out.append("$prefix$fk=${formatScalarValue(value)}\n")
        }
    }
}

private fun encodeRootArray(arr: List<*>, out: StringBuilder, opts: GenericOptions) {
    if (arr.isEmpty()) { out.append("## [0]\n"); return }
    if (allPrimitives(arr)) {
        val vals = arr.joinToString(",") { formatScalarValue(it, ',') }
        out.append("## [${arr.size}]: $vals\n"); return
    }
    val fields = tabularFields(arr)
    if (fields != null) { encodeTabular("## ", arr, fields, out, 0, opts); return }
    encodeExpanded("## ", arr, out, 0, opts)
}

private fun encodeNamedArray(name: String, arr: List<*>, out: StringBuilder, depth: Int, opts: GenericOptions) {
    val prefix = indent(depth)
    if (arr.isEmpty()) { out.append("${prefix}## $name [0]\n"); return }
    if (allPrimitives(arr)) {
        val vals = arr.joinToString(",") { formatScalarValue(it, ',') }
        out.append("$prefix${name}[${arr.size}]: $vals\n"); return
    }
    val fields = tabularFields(arr)
    if (fields != null) { encodeTabular("${prefix}## $name ", arr, fields, out, depth, opts); return }
    encodeExpanded("${prefix}## $name ", arr, out, depth, opts)
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
private fun inlineSchemaFields(arr: List<*>, fieldName: String): List<String>? {
    if (arr.isEmpty()) return null
    val first = arr[0] as? Map<*, *> ?: return null
    if (fieldName !in first) return null
    val firstVal = first[fieldName]
    if (firstVal !is Map<*, *>) return null

    var canonicalKeys: List<String>? = null
    for (item in arr) {
        val map = item as? Map<String, Any?> ?: return null
        if (fieldName !in map || map[fieldName] == null) continue
        val v = map[fieldName]
        if (v !is Map<*, *>) return null
        val keys = (v as Map<String, Any?>).keys.toList()
        for (value in v.values) {
            if (value is Map<*, *> || value is List<*>) return null
        }
        if (canonicalKeys == null) {
            canonicalKeys = keys
        } else {
            if (keys != canonicalKeys) return null
        }
    }
    return if (canonicalKeys != null && canonicalKeys.size >= 3) canonicalKeys else null
}

@Suppress("UNCHECKED_CAST")
private fun sharedArraySchema(arr: List<*>, fieldName: String): List<String>? {
    if (arr.isEmpty()) return null
    val first = arr[0] as? Map<*, *> ?: return null
    if (fieldName !in first) return null
    val firstVal = first[fieldName]
    if (firstVal !is List<*>) return null

    var canonicalFields: List<String>? = null
    for (item in arr) {
        val map = item as? Map<String, Any?> ?: return null
        if (fieldName !in map || map[fieldName] == null) continue
        val v = map[fieldName]
        if (v !is List<*>) return null
        val fields = tabularFields(v) ?: return null
        // All values must be scalars.
        for (arrItem in v) {
            val arrMap = arrItem as? Map<*, *> ?: return null
            for (value in arrMap.values) {
                if (value is Map<*, *> || value is List<*>) return null
            }
        }
        if (canonicalFields == null) {
            canonicalFields = fields
        } else {
            if (fields != canonicalFields) return null
        }
    }
    return canonicalFields
}

// -- Nested object flattening (v3.2) --

private data class FlatLeaf(val path: String, val keys: List<String>)

@Suppress("UNCHECKED_CAST")
private fun analyzeFlattenable(arr: List<*>, fieldName: String, parentPath: String): List<FlatLeaf>? {
    // Field names containing ">" cannot be flattened (would create ambiguous paths).
    if (">" in fieldName) return null
    var canonicalShape: MutableMap<String, String>? = null // key -> "scalar" | "nested"
    var canonicalKeys: List<String>? = null

    for (item in arr) {
        val map = item as? Map<String, Any?> ?: return null
        if (fieldName !in map || map[fieldName] == null) continue
        val v = map[fieldName]
        if (v !is Map<*, *>) return null
        if (v is List<*>) return null
        val obj = v as Map<String, Any?>
        val keys = obj.keys.toList()

        if (canonicalShape == null) {
            val shape = mutableMapOf<String, String>()
            for (k in keys) {
                if (">" in k) return null
                val value = obj[k]
                when {
                    value is List<*> -> return null
                    value is Map<*, *> -> shape[k] = "nested"
                    else -> shape[k] = "scalar"
                }
            }
            canonicalShape = shape
            canonicalKeys = keys
        } else {
            if (keys != canonicalKeys) return null
            for (k in keys) {
                val expected = canonicalShape[k] ?: return null
                val value = obj[k]
                if (expected == "scalar" && (value is Map<*, *> || value is List<*>)) return null
                if (expected == "nested" && value is List<*>) return null
                if (expected == "nested" && value != null && value !is Map<*, *>) return null
            }
        }
    }

    val shape = canonicalShape ?: return null
    val ck = canonicalKeys ?: return null
    val currentPath = if (parentPath.isEmpty()) fieldName else "$parentPath>$fieldName"
    val parentKeys = if (parentPath.isEmpty()) listOf(fieldName) else parentPath.split(">") + fieldName

    val leaves = mutableListOf<FlatLeaf>()
    for (k in ck) {
        if (shape[k] == "scalar") {
            leaves.add(FlatLeaf("$currentPath>$k", parentKeys + k))
        } else {
            val subArr = arr.map { item ->
                val map = item as? Map<String, Any?>
                if (map == null || fieldName !in map || map[fieldName] == null) emptyMap<String, Any?>()
                else map[fieldName] as Any
            }
            val subLeaves = analyzeFlattenable(subArr, k, currentPath) ?: return null
            if (subLeaves.isEmpty()) return null
            leaves.addAll(subLeaves)
        }
    }

    // Guard: reject if any row has non-null object with all-null leaves.
    if (leaves.isNotEmpty()) {
        for (item in arr) {
            val map = item as? Map<String, Any?> ?: continue
            if (fieldName !in map || map[fieldName] == null) continue
            val allNull = leaves.all { leaf ->
                val (value, exists) = resolveKeyChainKt(item, leaf.keys)
                exists && value == null
            }
            if (allNull) return null
        }
    }

    return leaves
}

@Suppress("UNCHECKED_CAST")
private fun resolveKeyChainKt(item: Any?, keys: List<String>): Pair<Any?, Boolean> {
    if (keys.isEmpty() || item !is Map<*, *>) return Pair(null, false)
    val map = item as Map<String, Any?>
    if (keys[0] !in map) return Pair(null, false)
    var current: Any? = map[keys[0]]
    if (current == null) return Pair(null, true)
    for (k in keys.drop(1)) {
        if (current !is Map<*, *>) return Pair(null, false)
        val m = current as Map<String, Any?>
        if (k !in m) return Pair(null, false)
        current = m[k]
    }
    return Pair(current, true)
}

private data class FlatCol(val header: String, val type: String, val field: String, val keys: List<String>)

@Suppress("UNCHECKED_CAST")
private fun encodeTabular(headerPrefix: String, arr: List<*>, fields: List<String>, out: StringBuilder, depth: Int, opts: GenericOptions = GenericOptions()) {
    val prefix = indent(depth)

    // Phase 0: Analyze fields for flattening.
    val flattenMap = mutableMapOf<String, List<FlatLeaf>>()
    if (!opts.noFlatten) {
        for (f in fields) {
            analyzeFlattenable(arr, f, "")?.takeIf { it.isNotEmpty() }?.let { flattenMap[f] = it }
        }
    }

    // Fields whose names contain ">" must not appear as tabular columns
    // because the decoder would interpret them as flattened path columns.
    val gtFields = fields.filter { it !in flattenMap && ">" in it }.toSet()

    // Build expanded column list.
    val columns = mutableListOf<FlatCol>()
    for (f in fields) {
        if (f in gtFields) continue
        val leaves = flattenMap[f]
        if (leaves != null) {
            for (leaf in leaves) columns.add(FlatCol(formatKeyValue(leaf.path), "flat", f, leaf.keys))
        } else {
            columns.add(FlatCol(formatKeyValue(f), "original", f, emptyList()))
        }
    }

    // If all fields were excluded (all contain ">"), fall back to expanded.
    if (columns.isEmpty()) {
        encodeExpanded(headerPrefix, arr, out, depth, opts)
        return
    }

    // Pre-compute inline schemas and shared array schemas (skip flattened).
    val inlineSchemas = mutableMapOf<String, List<String>>()
    val sharedArrSchemas = mutableMapOf<String, List<String>>()
    for (f in fields) {
        if (f in flattenMap) continue
        inlineSchemaFields(arr, f)?.let { inlineSchemas[f] = it }
        sharedArraySchema(arr, f)?.let { sharedArrSchemas[f] = it }
    }

    val headerFields = columns.joinToString(",") { it.header }
    out.append("$headerPrefix[${arr.size}]{$headerFields}\n")

    for ((i, item) in arr.withIndex()) {
        val map = item as? Map<String, Any?> ?: continue
        val cells = mutableListOf<String>()
        data class Att(val name: String, val value: Any?, val inline: Boolean, val inlineFields: List<String>?)
        val attachments = mutableListOf<Att>()
        var rowHasAttachment = false

        for (col in columns) {
            if (col.type == "flat") {
                if (col.keys[0] !in map) { cells.add("~"); continue }
                val topVal = map[col.keys[0]]
                if (topVal == null) { cells.add("-"); continue }
                val (value, exists) = resolveKeyChainKt(item, col.keys)
                when {
                    !exists -> cells.add("~")
                    value == null -> cells.add("-")
                    else -> cells.add(formatScalarValue(value, '|'))
                }
                continue
            }

            val f = col.field
            if (f !in map) { cells.add("~"); continue }
            val v = map[f]
            if (v == null) { cells.add("-"); continue }
            if (v is Map<*, *> || v is List<*>) {
                val ifs = inlineSchemas[f]
                if (ifs != null && v is Map<*, *>) {
                    if (i == 0) {
                        val fmtIF = ifs.joinToString(",") { formatKeyValue(it) }
                        cells.add("^{$fmtIF}")
                    } else {
                        cells.add("^")
                    }
                    attachments.add(Att(f, v, true, ifs))
                } else {
                    cells.add("^")
                    attachments.add(Att(f, v, false, null))
                }
                rowHasAttachment = true
            } else {
                cells.add(formatScalarValue(v, '|'))
            }
        }

        // Emit fields with ">" in their names as per-row attachments.
        for (f in fields) {
            if (f !in gtFields) continue
            if (f !in map) continue
            rowHasAttachment = true
            attachments.add(Att(f, map[f], false, null))
        }

        val row = cells.joinToString("|")
        if (rowHasAttachment) out.append("${prefix}@$i $row\n") else out.append("$prefix$row\n")

        for (att in attachments) {
            val fk = formatKeyValue(att.name)
            if (att.inline && att.inlineFields != null) {
                // Inline: single pipe-delimited row, no prefix.
                val vals = att.inlineFields.joinToString("|") { inf ->
                    val obj = att.value as Map<String, Any?>
                    if (inf !in obj) "~" else formatScalarValue(obj[inf], '|')
                }
                out.append("$prefix$vals\n")
            } else when (att.value) {
                is Map<*, *> -> {
                    out.append("$prefix.${fk} {}\n")
                    encodeObject(att.value as Map<String, Any?>, out, depth + 2, opts)
                }
                is List<*> -> {
                    val sas = sharedArrSchemas[att.name]
                    if (sas != null && i > 0) {
                        encodeAttachmentArrayShared(prefix, fk, att.value, out, depth + 2, sas, opts)
                    } else {
                        encodeAttachmentArray(prefix, fk, att.value, out, depth + 2, opts)
                    }
                }
                else -> {
                    // Scalar attachment (e.g. field names containing ">").
                    out.append("$prefix.$fk =${formatScalarValue(att.value)}\n")
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun encodeAttachmentArrayShared(attPrefix: String, fk: String, arr: List<*>, out: StringBuilder, depth: Int, sharedFields: List<String>, opts: GenericOptions = GenericOptions()) {
    if (arr.isEmpty()) { out.append("$attPrefix.$fk [0]\n"); return }
    if (allPrimitives(arr)) {
        val vals = arr.joinToString(",") { formatScalarValue(it, ',') }
        out.append("$attPrefix.$fk [${arr.size}]: $vals\n"); return
    }
    val fields = tabularFields(arr)
    if (fields != null && fields == sharedFields) {
        val prefix = indent(depth)
        out.append("$attPrefix.$fk [${arr.size}]\n")
        for (item in arr) {
            val map = item as? Map<String, Any?> ?: continue
            val cells = sharedFields.map { f ->
                if (f !in map) "~"
                else if (map[f] == null) "-"
                else formatScalarValue(map[f], '|')
            }
            out.append("$prefix${cells.joinToString("|")}\n")
        }
    } else {
        encodeAttachmentArray(attPrefix, fk, arr, out, depth, opts)
    }
}

private fun encodeAttachmentArray(attPrefix: String, fk: String, arr: List<*>, out: StringBuilder, depth: Int, opts: GenericOptions = GenericOptions()) {
    if (arr.isEmpty()) { out.append("$attPrefix.$fk [0]\n"); return }
    if (allPrimitives(arr)) {
        val vals = arr.joinToString(",") { formatScalarValue(it, ',') }
        out.append("$attPrefix.$fk [${arr.size}]: $vals\n"); return
    }
    val fields = tabularFields(arr)
    if (fields != null) { encodeTabular("$attPrefix.$fk ", arr, fields, out, depth, opts); return }
    encodeExpanded("$attPrefix.$fk ", arr, out, depth, opts)
}

@Suppress("UNCHECKED_CAST")
private fun encodeExpanded(headerPrefix: String, arr: List<*>, out: StringBuilder, depth: Int, opts: GenericOptions = GenericOptions()) {
    val prefix = indent(depth)
    out.append("$headerPrefix[${arr.size}]\n")
    for ((i, item) in arr.withIndex()) {
        when {
            item is Map<*, *> -> {
                out.append("${prefix}@$i {}\n")
                encodeObject(item as Map<String, Any?>, out, depth + 1, opts)
            }
            item is List<*> -> encodeExpandedArrayItem(prefix, i, item, out, depth, opts)
            else -> out.append("${prefix}@$i =${formatScalarValue(item)}\n")
        }
    }
}

private fun encodeExpandedArrayItem(prefix: String, idx: Int, arr: List<*>, out: StringBuilder, depth: Int, opts: GenericOptions = GenericOptions()) {
    if (arr.isEmpty()) { out.append("${prefix}@$idx [0]\n"); return }
    if (allPrimitives(arr)) {
        val vals = arr.joinToString(",") { formatScalarValue(it, ',') }
        out.append("${prefix}@$idx [${arr.size}]: $vals\n"); return
    }
    val fields = tabularFields(arr)
    if (fields != null) { encodeTabular("${prefix}@$idx ", arr, fields, out, depth + 1, opts); return }
    encodeExpanded("${prefix}@$idx ", arr, out, depth + 1, opts)
}

private fun allPrimitives(arr: List<*>): Boolean = arr.all { it !is Map<*, *> && it !is List<*> }

private fun indent(depth: Int): String = "  ".repeat(depth)
