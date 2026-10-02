package com.blackwellsystems.gcf

/**
 * v3.6.0 tabular column optimizations for the generic profile: constant-column
 * factoring (SPEC 7.4.7) and value-grouping (SPEC 7.4.8). Constant-column
 * factoring is mandatory canonical and rides the default encoder (encodeTabular,
 * Generic.kt); the decode side and the opt-in grouped encoder live here.
 */

/**
 * One parsed entry of a tabular field declaration. A plain field has only a name.
 * A constant column (SPEC 7.4.7) carries an unparsed value token after an unquoted
 * "=". A key column (SPEC 7.4.8.1, 10a.1) carries a leading "@".
 */
internal data class FieldEntry(
    val name: String,
    val isKey: Boolean = false,
    val isConst: Boolean = false,
    val constTok: String = "",
)

private val BARE_KEY_ENTRY_RE = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

/**
 * Returns the index just past the closing quote of a quoted string that starts at
 * s[0], or -1 if unterminated.
 */
private fun quotedStringEnd(s: String): Int {
    var escaped = false
    var i = 1
    while (i < s.length) {
        if (escaped) { escaped = false; i++; continue }
        if (s[i] == '\\') { escaped = true; i++; continue }
        if (s[i] == '"') return i + 1
        i++
    }
    return -1
}

/**
 * Parses a field entry's name and optional "=value" tail. The name is a Section 2a
 * key (bare or quoted); the "=" that introduces a constant value is the first
 * unquoted "=" after the (possibly quoted) name. A null value means the entry is a
 * plain field (no "=").
 */
private fun splitNameValue(r: String): Pair<String, String?> {
    if (r.isEmpty()) throw IllegalArgumentException("malformed_header_field: empty field entry")
    if (r[0] == '"') {
        val end = quotedStringEnd(r)
        if (end < 0) throw IllegalArgumentException("unterminated_quote: field name")
        val nm = parseQuotedStringValue(r.substring(0, end))
        val after = r.substring(end)
        if (after.isEmpty()) return nm to null
        if (after[0] == '=') return nm to after.substring(1)
        throw IllegalArgumentException("malformed_header_field: unexpected characters after quoted field name")
    }
    val idx = r.indexOf('=')
    if (idx >= 0) {
        val nm = r.substring(0, idx)
        if (nm.isEmpty()) throw IllegalArgumentException("malformed_header_field: empty field name")
        if (!BARE_KEY_ENTRY_RE.matches(nm)) throw IllegalArgumentException("invalid field name: $nm")
        return nm to r.substring(idx + 1)
    }
    if (!BARE_KEY_ENTRY_RE.matches(r)) throw IllegalArgumentException("invalid field name: $r")
    return r to null
}

/**
 * Parses a {...} field declaration supporting "@" key markers and "name=value"
 * constant columns. Commas, and the "=" boundary, are parsed respecting quoted
 * names and quoted values (SPEC 7.4.7.2, mirroring 2a.3).
 */
internal fun parseFieldEntries(declStr: String): List<FieldEntry> {
    if (declStr.length < 2 || declStr[0] != '{' || declStr[declStr.length - 1] != '}') {
        throw IllegalArgumentException("invalid field declaration: $declStr")
    }
    val inner = declStr.substring(1, declStr.length - 1)
    if (inner.isEmpty()) return emptyList()
    val raw = splitRespectingQuotes(inner, ',')
    val entries = mutableListOf<FieldEntry>()
    for (rawEntry in raw) {
        var r = rawEntry.trim()
        var isKey = false
        if (r.startsWith("@")) { isKey = true; r = r.substring(1) }
        val (nm, value) = splitNameValue(r)
        entries.add(
            if (value != null) FieldEntry(name = nm, isKey = isKey, isConst = true, constTok = value)
            else FieldEntry(name = nm, isKey = isKey)
        )
    }
    val seen = mutableSetOf<String>()
    for (e in entries) {
        if (!seen.add(e.name)) throw IllegalArgumentException("duplicate_field_name: ${e.name}")
    }
    return entries
}

/**
 * Parses a constant-column value token into a scalar (SPEC 7.4.7.2). The absent
 * marker and empty/attachment tokens are rejected.
 */
private fun parseConstValue(tok: String): Any? {
    if (tok.isEmpty()) {
        throw IllegalArgumentException("invalid_const_value: empty constant value (the empty string is always quoted)")
    }
    if (tok == "~") {
        throw IllegalArgumentException("invalid_const_value: absent marker ~ is not valid in a field declaration")
    }
    // Reject only a complete attachment marker, mirroring the encoder's Section 2.4
    // quoting predicate (bare "^", or "^{...}" ending in "}"). A "^{"-prefixed token
    // without a closing "}" (e.g. "^{abc") is not a marker; it is a literal string,
    // and the encoder leaves it bare, so the decoder must accept it as a scalar.
    if (tok == "^" || (tok.length >= 3 && tok[0] == '^' && tok[1] == '{' && tok[tok.length - 1] == '}')) {
        throw IllegalArgumentException("invalid_const_value: attachment marker is not a scalar")
    }
    return scalarToAnyValue(parseScalarValue(tok, tabularContext = false))
}

/**
 * Formats a scalar as a constant-column header value (SPEC 7.4.7.2): the Section
 * 2.4 obligation plus quoting when the value contains "}" (the "," case is already
 * covered by needsQuote). Null is "-".
 */
internal fun formatConstValue(v: Any?): String {
    if (v == null) return "-"
    if (v is String) {
        return if (needsQuote(v) || v.contains('}')) quoteString(v) else v
    }
    return formatScalarValue(v)
}

// scalarToAnyValue converts a parsed scalar to a host value for constant columns and
// grouped rows. Mirrors DecodeGeneric's scalarToAny but local to this file; a Missing
// or attachment marker reaching here is a parser-contract violation.
private fun scalarToAnyValue(sv: ScalarParsed): Any? = when (sv) {
    is ScalarParsed.Null -> null
    is ScalarParsed.BoolVal -> sv.value
    is ScalarParsed.IntVal -> sv.value
    is ScalarParsed.DoubleVal -> sv.value
    is ScalarParsed.StringVal -> sv.value
    is ScalarParsed.Missing -> throw IllegalArgumentException("invalid_const_value: absent marker is not a scalar")
    is ScalarParsed.Attachment -> throw IllegalArgumentException("invalid_const_value: attachment marker is not a scalar")
    is ScalarParsed.InlineAttachment -> throw IllegalArgumentException("invalid_const_value: attachment marker is not a scalar")
}

/**
 * Returns the top-level group key of a flattened path column (SPEC 7.4.6) and true
 * when the name is a valid path (contains ">" with all segments non-empty),
 * mirroring parseTabularBody's path-column detection.
 */
private fun pathTopLevel(name: String): Pair<String, Boolean> {
    if (">" !in name) return "" to false
    val parts = name.split(">")
    if (parts.any { it.isEmpty() }) return "" to false
    return parts[0] to true
}

/**
 * Parses a tabular array whose field declaration contains one or more constant
 * columns (SPEC 7.4.7). It parses the rows with the bare (per-record) fields only,
 * then rebuilds each record in declaration order, inserting each constant at its
 * position. Returns the records and the number of lines consumed including the header.
 */
internal fun decodeConstantArray(
    lines: List<String>,
    headerLine: Int,
    depth: Int,
    entries: List<FieldEntry>,
    count: Int,
): Pair<List<Any>, Int> {
    val bareFields = mutableListOf<String>()
    val constVals = linkedMapOf<String, Any?>()
    for (e in entries) {
        if (e.isConst) { constVals[e.name] = parseConstValue(e.constTok); continue }
        bareFields.add(e.name)
    }
    if (bareFields.isEmpty()) {
        throw IllegalArgumentException("no_bare_column: every field is constant; a row must carry at least one per-record column")
    }
    val (rows, consumed) = parseTabularBody(lines, headerLine + 1, depth, bareFields, count)
    if (count >= 0 && rows.size != count) {
        throw IllegalArgumentException("count_mismatch: declared $count, got ${rows.size}")
    }

    // Plan the output-key order over all entries, mirroring parseTabularBody: a bare
    // path column (contains ">") collapses to its top-level key at the first
    // occurrence, a plain field keeps its name, and a constant contributes its name at
    // its position. A record from parseTabularBody is keyed by these collapsed bare
    // keys, so inserting the constants by this plan (and appending any flatten-fallback
    // extras) reconstructs each record in declaration order without losing nested or
    // attachment fields.
    data class OutKey(val name: String, val isConst: Boolean)
    val plan = mutableListOf<OutKey>()
    val inPlan = mutableSetOf<String>()
    val seenGroup = mutableSetOf<String>()
    for (e in entries) {
        if (e.isConst) { plan.add(OutKey(e.name, true)); inPlan.add(e.name); continue }
        val (top, isPath) = pathTopLevel(e.name)
        if (isPath) {
            if (seenGroup.add(top)) { plan.add(OutKey(top, false)); inPlan.add(top) }
            continue
        }
        plan.add(OutKey(e.name, false)); inPlan.add(e.name)
    }

    val out = ArrayList<Any>(rows.size)
    for (r in rows) {
        @Suppress("UNCHECKED_CAST")
        val rm = r as? Map<String, Any?>
        val nm = linkedMapOf<String, Any?>()
        for (k in plan) {
            if (k.isConst) { nm[k.name] = constVals[k.name]; continue }
            if (rm != null && k.name in rm) nm[k.name] = rm[k.name]
        }
        // Append any keys the record carries that were not in the plan (flatten-fallback
        // attachments, SPEC 7.4.6.1.4), in the record's own order.
        if (rm != null) {
            for ((k, v) in rm) if (k !in inPlan) nm[k] = v
        }
        out.add(nm)
    }
    return out to (consumed + 1)
}

/**
 * Parses a value-grouped tabular array (SPEC 7.4.8). groupClause is the trimmed text
 * after the field declaration's "}" (beginning with "group=").
 */
internal fun decodeGroupedArray(
    lines: List<String>,
    headerLine: Int,
    depth: Int,
    entries: List<FieldEntry>,
    groupClause: String,
    count: Int,
): Pair<List<Any>, Int> {
    if (!groupClause.startsWith("group=")) {
        throw IllegalArgumentException("invalid_group_header: malformed group clause")
    }
    val groupCol = try {
        parseHeaderKey(groupClause.substring("group=".length).trim())
    } catch (e: Exception) {
        throw IllegalArgumentException("invalid_group_header: ${e.message}")
    }

    var keyCount = 0
    var keyName = ""
    for (e in entries) {
        if (e.isKey) { keyCount++; keyName = e.name }
    }
    if (keyCount != 1) {
        throw IllegalArgumentException("invalid_group_header: a grouped section requires exactly one @ key column")
    }

    // Validate the grouping column: present, not the key, not a constant column.
    val groupEntry = entries.firstOrNull { it.name == groupCol }
        ?: throw IllegalArgumentException("invalid_group_header: group column \"$groupCol\" is not a declared field")
    if (groupEntry.isKey) {
        throw IllegalArgumentException("invalid_group_header: group column \"$groupCol\" is the key column")
    }
    if (groupEntry.isConst) {
        throw IllegalArgumentException("invalid_group_header: group column \"$groupCol\" is a constant column")
    }

    // Per-record (bare) fields are the non-constant fields other than the grouping
    // column; the key column is included.
    val bareFields = mutableListOf<String>()
    val constVals = linkedMapOf<String, Any?>()
    for (e in entries) {
        if (e.isConst) { constVals[e.name] = parseConstValue(e.constTok); continue }
        if (e.name == groupCol) continue
        bareFields.add(e.name)
    }

    val indent = "  ".repeat(depth)
    val records = mutableListOf<Any>()
    val seenGroups = mutableSetOf<String>()
    val seenKeys = mutableSetOf<String>()
    var total = 0
    var i = headerLine + 1
    while (i < lines.size) {
        var content = lines[i]
        if (depth > 0) {
            if (!content.startsWith(indent)) break
            content = content.substring(indent.length)
        }
        if (content.startsWith("## ") || content.startsWith("##!")) break

        // Subheader: {col}={value} [{count}]
        val (col, groupVal, gcount) = parseGroupSubheader(content)
        if (col != groupCol) {
            throw IllegalArgumentException("invalid_group_header: subheader column \"$col\" does not match group column \"$groupCol\"")
        }
        val gkey = formatScalarValue(groupVal)
        if (!seenGroups.add(gkey)) throw IllegalArgumentException("duplicate_group: $gkey")
        i++

        var n = 0
        while (n < gcount) {
            if (i >= lines.size) {
                throw IllegalArgumentException("count_mismatch: group \"$gkey\" declared $gcount rows, found fewer")
            }
            var rowContent = lines[i]
            if (depth > 0) {
                if (!rowContent.startsWith(indent)) {
                    throw IllegalArgumentException("count_mismatch: group \"$gkey\" declared $gcount rows, found fewer")
                }
                rowContent = rowContent.substring(indent.length)
            }
            if (rowContent.startsWith("## ") || rowContent.startsWith("##!")) {
                throw IllegalArgumentException("count_mismatch: group \"$gkey\" declared $gcount rows, found fewer")
            }
            val cells = splitRespectingQuotes(rowContent, '|')
            if (cells.size != bareFields.size) {
                throw IllegalArgumentException("row_width_mismatch: expected ${bareFields.size} fields, got ${cells.size}")
            }
            val bareVals = linkedMapOf<String, Any?>()
            for ((j, f) in bareFields.withIndex()) {
                val cell = cells[j]
                // Only a complete attachment marker (bare "^" or "^{...}" ending in "}")
                // is forbidden here; a "^{"-prefixed cell without a closing "}" is a
                // literal scalar (SPEC 7.4 row cell), not an attachment.
                if (cell == "^" || (cell.length >= 3 && cell[0] == '^' && cell[1] == '{' && cell[cell.length - 1] == '}')) {
                    throw IllegalArgumentException("invalid_group_header: grouped records must not carry attachments")
                }
                when (val pv = parseScalarValue(cell, tabularContext = true)) {
                    is ScalarParsed.Missing -> {}
                    else -> bareVals[f] = scalarToAnyValue(pv)
                }
            }
            val nm = linkedMapOf<String, Any?>()
            for (e in entries) {
                when {
                    e.name == groupCol -> nm[e.name] = groupVal
                    e.isConst -> nm[e.name] = constVals[e.name]
                    else -> if (e.name in bareVals) nm[e.name] = bareVals[e.name]
                }
            }
            if (keyName !in nm) {
                throw IllegalArgumentException("invalid_group_header: record missing key column \"$keyName\"")
            }
            val ks = formatScalarValue(nm[keyName])
            if (!seenKeys.add(ks)) throw IllegalArgumentException("duplicate_key: $ks")
            records.add(nm)
            i++
            n++
        }
        total += gcount
    }

    if (count >= 0 && total != count) {
        throw IllegalArgumentException("count_mismatch: declared $count, got $total")
    }
    return records to (i - headerLine)
}

/** Parses a Section 2a key (bare or quoted) that occupies the whole of s. */
private fun parseHeaderKey(s: String): String {
    if (s.isEmpty()) throw IllegalArgumentException("empty key")
    if (s[0] == '"') {
        val end = quotedStringEnd(s)
        if (end != s.length) throw IllegalArgumentException("malformed quoted key: $s")
        return parseQuotedStringValue(s)
    }
    if (!BARE_KEY_ENTRY_RE.matches(s)) throw IllegalArgumentException("invalid key: $s")
    return s
}

/**
 * Parses a line of the form `{col}={value} [{count}]` (SPEC 7.4.8.3). The value runs
 * from the first unquoted "=" to the final " [" that begins the count.
 */
private fun parseGroupSubheader(content: String): Triple<String, Any?, Int> {
    if (!content.endsWith("]")) {
        throw IllegalArgumentException("invalid_group_header: subheader missing count bracket")
    }
    val cntOpen = content.lastIndexOf(" [")
    if (cntOpen < 0) {
        throw IllegalArgumentException("invalid_group_header: subheader missing count bracket")
    }
    val countStr = content.substring(cntOpen + 2, content.length - 1)
    val n = try {
        parseCountVal(countStr)
    } catch (e: Exception) {
        throw IllegalArgumentException("invalid_count: $countStr")
    }
    if (n == 0) {
        throw IllegalArgumentException("invalid_count: a group names at least one record")
    }
    val colEqVal = content.substring(0, cntOpen)
    val eq = indexUnquotedEq(colEqVal)
    if (eq < 0) {
        throw IllegalArgumentException("invalid_group_header: subheader missing '='")
    }
    val colName = try {
        parseHeaderKey(colEqVal.substring(0, eq))
    } catch (e: Exception) {
        throw IllegalArgumentException("invalid_group_header: ${e.message}")
    }
    val valTok = colEqVal.substring(eq + 1)
    val v = parseConstValue(valTok)
    return Triple(colName, v, n)
}

/** Returns the index of the first "=" outside a quoted string, or -1. */
private fun indexUnquotedEq(s: String): Int {
    var inQuote = false
    var escaped = false
    for (i in s.indices) {
        val c = s[i]
        if (escaped) { escaped = false; continue }
        if (c == '\\' && inQuote) { escaped = true; continue }
        if (c == '"') { inQuote = !inQuote; continue }
        if (c == '=' && !inQuote) return i
    }
    return -1
}

/**
 * Encodes an array of uniform records as a value-grouped keyed set (SPEC 7.4.8):
 * opt-in, never the canonical default. keyField is the unique identity column
 * (emitted @-marked); groupField is the low-cardinality column the records are
 * clustered by. Other constant columns are factored (SPEC 7.4.7). Throws when the
 * array is not a keyed set the grammar can represent: a missing key/group field, a
 * non-unique key, key == group, or any record needing an attachment (nested value),
 * which grouped rows do not carry in this version.
 */
@Suppress("UNCHECKED_CAST")
fun encodeGenericGrouped(data: Any?, keyField: String, groupField: String): String {
    val arr = data as? List<Any?>
        ?: throw IllegalArgumentException("value-grouping requires a JSON array")
    if (arr.isEmpty()) throw IllegalArgumentException("value-grouping requires a non-empty array")
    if (keyField == groupField) {
        throw IllegalArgumentException("value-grouping: key field and group field must differ")
    }
    for (item in arr) {
        if (item !is Map<*, *>) throw IllegalArgumentException("value-grouping requires an array of objects")
    }
    val fields = groupedTabularFields(arr)
        ?: throw IllegalArgumentException("value-grouping requires an array of objects with fields")
    if (keyField !in fields) {
        throw IllegalArgumentException("value-grouping: key field \"$keyField\" not present in the records")
    }
    if (groupField !in fields) {
        throw IllegalArgumentException("value-grouping: group field \"$groupField\" not present in the records")
    }

    val keySeen = mutableSetOf<String>()
    for (item in arr) {
        val m = item as Map<String, Any?>
        for (f in fields) {
            if (f !in m) continue
            val v = m[f]
            if (v is Map<*, *> || v is List<*>) {
                throw IllegalArgumentException("value-grouping does not support nested values in this version: field \"$f\"")
            }
        }
        if (keyField !in m || m[keyField] == null) {
            throw IllegalArgumentException("value-grouping: key field \"$keyField\" missing in a record")
        }
        val ks = formatScalarValue(m[keyField])
        if (!keySeen.add(ks)) {
            throw IllegalArgumentException("value-grouping: key field \"$keyField\" is not unique ($ks)")
        }
    }

    // Constant columns (excluding key and group), factored per SPEC 7.4.7.
    val constVal = linkedMapOf<String, String>()
    if (arr.size >= 2) {
        for (f in fields) {
            if (f == keyField || f == groupField) continue
            var first = ""
            var firstSet = false
            var isc = true
            for (item in arr) {
                val m = item as Map<String, Any?>
                if (f !in m) { isc = false; break }
                val cv = formatConstValue(m[f])
                if (!firstSet) { first = cv; firstSet = true }
                else if (cv != first) { isc = false; break }
            }
            if (isc) constVal[f] = first
        }
    }

    val headerFields = fields.map { f ->
        when {
            f == keyField -> "@" + formatKeyValue(f)
            f == groupField -> formatKeyValue(f)
            constVal.containsKey(f) -> formatKeyValue(f) + "=" + constVal[f]
            else -> formatKeyValue(f)
        }
    }

    val bareFields = mutableListOf<String>()
    for (f in fields) {
        if (f == groupField) continue
        if (constVal.containsKey(f)) continue
        bareFields.add(f)
    }

    val groupOrder = mutableListOf<String>()
    val groupMembers = linkedMapOf<String, MutableList<Map<String, Any?>>>()
    val groupValRaw = linkedMapOf<String, Any?>()
    for (item in arr) {
        val m = item as Map<String, Any?>
        val gv = m[groupField]
        val gk = formatScalarValue(gv)
        if (gk !in groupMembers) {
            groupOrder.add(gk)
            groupValRaw[gk] = gv
            groupMembers[gk] = mutableListOf()
        }
        groupMembers[gk]!!.add(m)
    }

    val b = StringBuilder()
    b.append("GCF profile=generic\n")
    b.append("## [${arr.size}]{${headerFields.joinToString(",")}} group=${formatKeyValue(groupField)}\n")
    for (gk in groupOrder) {
        val members = groupMembers[gk]!!
        val gvStr = formatScalarValue(groupValRaw[gk])
        b.append("${formatKeyValue(groupField)}=$gvStr [${members.size}]\n")
        for (item in members) {
            val cells = bareFields.map { f ->
                when {
                    f !in item -> "~"
                    item[f] == null -> "-"
                    else -> formatScalarValue(item[f], '|')
                }
            }
            b.append(cells.joinToString("|"))
            b.append('\n')
        }
    }
    return b.toString()
}

/**
 * Computes the ordered field union of an array of objects, mirroring Generic.kt's
 * tabularFields but local to this file. Returns null when the array is empty or any
 * item is not a string-keyed object, or the union is empty.
 */
@Suppress("UNCHECKED_CAST")
private fun groupedTabularFields(arr: List<*>): List<String>? {
    if (arr.isEmpty()) return null
    val fieldOrder = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (item in arr) {
        val map = item as? Map<*, *> ?: return null
        for (k in map.keys) {
            val key = k as? String ?: return null
            if (seen.add(key)) fieldOrder.add(key)
        }
    }
    return if (fieldOrder.isEmpty()) null else fieldOrder
}
