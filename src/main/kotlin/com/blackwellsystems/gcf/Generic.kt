package com.blackwellsystems.gcf

/**
 * Encode any value into GCF  generic profile.
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

@Suppress("UNCHECKED_CAST")
private fun encodeTabular(headerPrefix: String, arr: List<*>, fields: List<String>, out: StringBuilder, depth: Int) {
    val prefix = indent(depth)

    // Pre-compute inline schemas and shared array schemas.
    val inlineSchemas = mutableMapOf<String, List<String>>()
    val sharedArrSchemas = mutableMapOf<String, List<String>>()
    for (f in fields) {
        inlineSchemaFields(arr, f)?.let { inlineSchemas[f] = it }
        sharedArraySchema(arr, f)?.let { sharedArrSchemas[f] = it }
    }

    val fmtFields = fields.joinToString(",") { formatKeyValue(it) }
    out.append("$headerPrefix[${arr.size}]{$fmtFields}\n")

    for ((i, item) in arr.withIndex()) {
        val map = item as? Map<String, Any?> ?: continue
        val cells = mutableListOf<String>()
        data class Att(val name: String, val value: Any, val inline: Boolean, val inlineFields: List<String>?)
        val attachments = mutableListOf<Att>()
        var rowHasAttachment = false

        for (f in fields) {
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
                    encodeObject(att.value as Map<String, Any?>, out, depth + 2)
                }
                is List<*> -> {
                    val sas = sharedArrSchemas[att.name]
                    if (sas != null && i > 0) {
                        encodeAttachmentArrayShared(prefix, fk, att.value, out, depth + 2, sas)
                    } else {
                        encodeAttachmentArray(prefix, fk, att.value, out, depth + 2)
                    }
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun encodeAttachmentArrayShared(attPrefix: String, fk: String, arr: List<*>, out: StringBuilder, depth: Int, sharedFields: List<String>) {
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
        encodeAttachmentArray(attPrefix, fk, arr, out, depth)
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
