package com.blackwellsystems.gcf

import java.security.MessageDigest

// Generic-profile delta encoding (SPEC Section 10a).
//
// Full producer + consumer for keyed-row deltas over the generic profile,
// byte-for-byte interoperable with gcf-go, gcf-python, gcf-typescript, gcf-rust,
// and gcf-swift. Delta is opt-in and bilateral; the existing encodeGeneric path
// is unchanged.

/**
 * A keyed record set: the unit generic-profile delta operates on (Section 10a).
 * Rows are order-agnostic (set semantics); [fields] carries the declared column
 * order for the wire form; [key] names the identity column (the `@id` / `key=`);
 * [name] is the tabular section name for a full payload.
 */
data class GenericSet(
    val key: String,
    val fields: List<String>,
    val rows: List<Map<String, Any?>>,
    val name: String = "",
)

/**
 * A diff between two [GenericSet]s (computed by [diffGenericSets] or supplied
 * directly and serialized by [encodeGenericDelta]).
 */
data class GenericDeltaPayload(
    val key: String,
    val fields: List<String>,
    val baseRoot: String,
    val newRoot: String = "",
    val added: List<Map<String, Any?>> = emptyList(),
    val changed: List<Map<String, Any?>> = emptyList(),
    val removed: List<Any?> = emptyList(),
    val tool: String = "",
    val deltaTokens: Int = 0,
    val fullTokens: Int = 0,
)

/** Sort strings by UTF-8 byte order (matching Go's sort.Strings / Rust str order). */
private val byteOrder = Comparator<String> { a, b ->
    val ab = a.toByteArray(Charsets.UTF_8)
    val bb = b.toByteArray(Charsets.UTF_8)
    val n = minOf(ab.size, bb.size)
    for (i in 0 until n) {
        val x = ab[i].toInt() and 0xFF
        val y = bb[i].toInt() and 0xFF
        if (x != y) return@Comparator x - y
    }
    ab.size - bb.size
}

private fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Canonicalize one value for the pack-root record (Section 10a.3). Purpose-built
 * and deliberately decoupled from the wire cell encoder (formatScalarValue): it
 * must be collision-free and record-safe, not round-trippable.
 *   - Typed literals stay bare so they never collide with the strings that spell
 *     them: null is `-`, booleans are true/false, numbers are canonical.
 *   - Strings are ALWAYS quoted, so they can't collide with a typed literal and a
 *     tab or newline inside a value is escaped and cannot break the record.
 */
fun canonicalCell(v: Any?): String = when (v) {
    null -> "-"
    is Boolean -> if (v) "true" else "false"
    is Int -> v.toString()
    is Long -> v.toString()
    is Double -> formatNumberValue(v)
    is Float -> formatNumberValue(v.toDouble())
    is Number -> v.toString()
    is String -> quoteString(v)
    else -> quoteString(v.toString())
}

/**
 * Compute the canonical pack root for a keyed set using the gcf-pack-root-v1
 * algorithm, generic profile (Section 10a.3). Two implementations given the same
 * logical set MUST produce the same result.
 */
fun genericPackRoot(s: GenericSet): String {
    val sortedFields = s.fields.sortedWith(byteOrder)
    val records = s.rows.map { row ->
        val r = StringBuilder("R")
        for (f in sortedFields) {
            r.append('\t').append(f).append('\t').append(canonicalCell(row[f]))
        }
        r.append('\n').toString()
    }.sortedWith(byteOrder)
    return "sha256:" + sha256Hex(records.joinToString(""))
}

/** Build an identity -> row map, rejecting duplicate identities (Section 10a.1). */
private fun indexByKey(s: GenericSet): LinkedHashMap<String, Map<String, Any?>> {
    val m = LinkedHashMap<String, Map<String, Any?>>(s.rows.size)
    for (row in s.rows) {
        val id = canonicalCell(row[s.key])
        if (m.containsKey(id)) {
            throw IllegalArgumentException("delta_invalid: duplicate identity $id for key \"${s.key}\"")
        }
        m[id] = row
    }
    return m
}

private fun keyOf(row: Map<String, Any?>, key: String): String = canonicalCell(row[key])

private fun rowsEqual(a: Map<String, Any?>, b: Map<String, Any?>, fields: List<String>): Boolean =
    fields.all { canonicalCell(a[it]) == canonicalCell(b[it]) }

/**
 * Compute the delta from [base] to [next]. This is the blessed producer path: it
 * is the single place that enforces the keyed-diff invariants (identity
 * uniqueness, added-not-in-base, changed-must-exist, whole-row replacement,
 * unchanged rows omitted). Added/changed/removed are sorted by identity for
 * reproducible output (Section 10a.6). Schema change or a missing key throws: the
 * caller must then send a full payload (Section 10a.7).
 */
fun diffGenericSets(base: GenericSet, next: GenericSet): GenericDeltaPayload {
    if (next.key.isEmpty()) throw IllegalArgumentException("delta_invalid: no identity key")
    if (next.key != base.key || base.fields != next.fields) {
        throw IllegalArgumentException("delta_invalid: schema change (send full)")
    }
    val baseIdx = indexByKey(base)
    val nextIdx = indexByKey(next)

    val added = mutableListOf<Map<String, Any?>>()
    val changed = mutableListOf<Map<String, Any?>>()
    val removed = mutableListOf<Any?>()

    for ((id, row) in nextIdx) {
        val brow = baseIdx[id]
        when {
            brow == null -> added.add(row)
            !rowsEqual(brow, row, next.fields) -> changed.add(row)
            // equal rows are omitted (silence = "keep it", Section 10a.5)
        }
    }
    for ((id, brow) in baseIdx) {
        if (!nextIdx.containsKey(id)) removed.add(brow[next.key])
    }

    return GenericDeltaPayload(
        key = next.key,
        fields = next.fields,
        baseRoot = genericPackRoot(base),
        newRoot = genericPackRoot(next),
        added = added.sortedWith(compareBy(byteOrder) { keyOf(it, next.key) }),
        changed = changed.sortedWith(compareBy(byteOrder) { keyOf(it, next.key) }),
        removed = removed.sortedWith(compareBy(byteOrder) { canonicalCell(it) }),
    )
}

// --- producer-side wire encoding ---

private fun fieldDecl(fields: List<String>, key: String): String =
    fields.joinToString(",") { if (it == key) "@" + formatKeyValue(it) else formatKeyValue(it) }

private fun encodeRow(row: Map<String, Any?>, fields: List<String>): String =
    fields.joinToString("|") { formatScalarValue(row[it], '|') }

/**
 * Emit a delta-participating full base payload: `key=` in the header, an
 * `@`-prefixed identity field in the declaration, pipe-separated rows.
 */
fun encodeGenericFull(s: GenericSet, tool: String): String {
    val name = s.name.ifEmpty { "rows" }
    val b = StringBuilder("GCF profile=generic")
    if (tool.isNotEmpty()) b.append(" tool=").append(tool)
    b.append(" pack_root=").append(genericPackRoot(s)).append(" key=").append(s.key).append('\n')
    b.append("## ").append(name).append(" [").append(s.rows.size).append("]{")
        .append(fieldDecl(s.fields, s.key)).append("}\n")
    for (row in s.rows) b.append(encodeRow(row, s.fields)).append('\n')
    return b.toString()
}

/**
 * Serialize a delta payload (Section 10a.2). Sections are emitted in the
 * deterministic order added / changed / removed (Section 10a.6).
 */
fun encodeGenericDelta(d: GenericDeltaPayload): String {
    val b = StringBuilder("GCF profile=generic")
    if (d.tool.isNotEmpty()) b.append(" tool=").append(d.tool)
    b.append(" delta=true base_root=").append(d.baseRoot)
        .append(" new_root=").append(d.newRoot).append(" key=").append(d.key)
    if (d.fullTokens > 0) {
        val savings = 100.0 * (1.0 - d.deltaTokens.toDouble() / d.fullTokens.toDouble())
        b.append(" savings=").append("%.0f".format(savings)).append('%')
    }
    b.append('\n')

    if (d.added.isNotEmpty()) {
        b.append("## added [").append(d.added.size).append("]{").append(fieldDecl(d.fields, d.key)).append("}\n")
        for (row in d.added) b.append(encodeRow(row, d.fields)).append('\n')
    }
    if (d.changed.isNotEmpty()) {
        b.append("## changed [").append(d.changed.size).append("]{").append(fieldDecl(d.fields, d.key)).append("}\n")
        for (row in d.changed) b.append(encodeRow(row, d.fields)).append('\n')
    }
    if (d.removed.isNotEmpty()) {
        b.append("## removed [").append(d.removed.size).append("]{@").append(d.key).append("}\n")
        for (idv in d.removed) b.append(formatScalarValue(idv, '|')).append('\n')
    }
    return b.toString()
}

/**
 * Apply a delta to a base set and verify the result hashes to [expectedNewRoot]
 * (Section 10a.5). Atomic: the whole payload is validated before any state
 * changes, and a mismatch leaves the base untouched.
 */
fun verifyGenericDelta(base: GenericSet, d: GenericDeltaPayload, expectedNewRoot: String): GenericSet {
    if (genericPackRoot(base) != d.baseRoot) {
        throw IllegalArgumentException("base_mismatch: base root does not equal delta base_root")
    }
    val baseIdx = indexByKey(base)

    // Validate the entire payload against the original base before mutating.
    for (idv in d.removed) {
        if (!baseIdx.containsKey(canonicalCell(idv))) {
            throw IllegalArgumentException("delta_invalid: removing identity ${canonicalCell(idv)} not in base")
        }
    }
    for (row in d.added) {
        if (baseIdx.containsKey(keyOf(row, d.key))) {
            throw IllegalArgumentException("delta_invalid: adding identity ${keyOf(row, d.key)} that already exists")
        }
    }
    for (row in d.changed) {
        if (!baseIdx.containsKey(keyOf(row, d.key))) {
            throw IllegalArgumentException("delta_invalid: changing identity ${keyOf(row, d.key)} not in base")
        }
    }

    // Apply to a working copy.
    val work = LinkedHashMap(baseIdx)
    for (idv in d.removed) work.remove(canonicalCell(idv))
    for (row in d.added) work[keyOf(row, d.key)] = row
    for (row in d.changed) work[keyOf(row, d.key)] = row

    val result = GenericSet(key = base.key, fields = base.fields, rows = work.values.toList(), name = base.name)
    val got = genericPackRoot(result)
    if (got != expectedNewRoot) {
        throw IllegalArgumentException("root_mismatch: computed $got, expected $expectedNewRoot")
    }
    return result
}

// --- consumer-side wire parsing (Section 10a) ---

private fun scalarToAny(r: ScalarParsed): Any? = when (r) {
    is ScalarParsed.Null -> null
    is ScalarParsed.BoolVal -> r.value
    is ScalarParsed.IntVal -> r.value
    is ScalarParsed.DoubleVal -> r.value
    is ScalarParsed.StringVal -> r.value
    else -> throw IllegalArgumentException("delta_invalid: non-scalar cell not allowed in delta row")
}

private fun parseHeaderFields(header: String): Map<String, String> {
    val m = HashMap<String, String>()
    for (tok in header.split(Regex("\\s+"))) {
        val i = tok.indexOf('=')
        if (i > 0) m[tok.substring(0, i)] = tok.substring(i + 1)
    }
    return m
}

private fun parseCount(s: String): Int {
    if (s == "0") return 0
    if (s.isEmpty() || s[0] == '0') throw IllegalArgumentException("delta_invalid: invalid count $s")
    val n = s.toIntOrNull() ?: throw IllegalArgumentException("delta_invalid: invalid count $s")
    if (n.toString() != s) throw IllegalArgumentException("delta_invalid: invalid count $s")
    return n
}

/** Find the index of the first `[` not inside a quoted string. */
private fun findBracketStart(s: String): Int {
    var inQuote = false
    var escaped = false
    for ((i, c) in s.withIndex()) {
        when {
            escaped -> escaped = false
            c == '\\' && inQuote -> escaped = true
            c == '"' -> inQuote = !inQuote
            c == '[' && !inQuote -> return i
        }
    }
    return -1
}

/**
 * Parse a delta/full field declaration `{@id,total,...}`, returning the ordered
 * fields and the key field (the one that was `@`-marked) (Section 10a.1).
 */
private fun splitDeltaFieldDecl(decl: String): Pair<List<String>, String> {
    if (decl.length < 2 || decl[0] != '{' || decl.last() != '}') {
        throw IllegalArgumentException("delta_invalid: invalid field declaration: $decl")
    }
    val inner = decl.substring(1, decl.length - 1)
    if (inner.isEmpty()) return Pair(emptyList(), "")
    val fields = mutableListOf<String>()
    var keyField = ""
    for (raw in splitRespectingQuotes(inner, ',')) {
        var f = raw.trim()
        var isKey = false
        if (f.startsWith("@")) { f = f.substring(1); isKey = true }
        if (f.length >= 2 && f.startsWith("\"") && f.endsWith("\"")) f = parseQuotedStringValue(f)
        if (isKey) keyField = f
        fields.add(f)
    }
    return Pair(fields, keyField)
}

private data class SectionHeader(val name: String, val count: Int, val fields: List<String>, val keyField: String)

/**
 * Parse the content after `## ` of a delta/full section, e.g.
 * `added [1]{@id,total,status,customer}` or `orders [3]{@id,...}` or `removed [1]{@id}`.
 */
private fun parseSectionHeader(content: String): SectionHeader {
    val bi = findBracketStart(content)
    if (bi < 0) throw IllegalArgumentException("delta_invalid: section header without count: $content")
    val name = content.substring(0, bi).trim()
    val rest = content.substring(bi) // "[N]{...}"
    if (rest.isEmpty() || rest[0] != '[') {
        throw IllegalArgumentException("delta_invalid: malformed section header: $content")
    }
    val close = rest.indexOf(']')
    if (close < 0) throw IllegalArgumentException("delta_invalid: unterminated count: $content")
    val count = parseCount(rest.substring(1, close))
    val (fields, keyField) = splitDeltaFieldDecl(rest.substring(close + 1))
    return SectionHeader(name, count, fields, keyField)
}

private fun parseRow(line: String, fields: List<String>): Map<String, Any?> {
    val cells = splitRespectingQuotes(line, '|')
    if (cells.size != fields.size) {
        throw IllegalArgumentException("delta_invalid: row has ${cells.size} cells, expected ${fields.size}: $line")
    }
    val row = LinkedHashMap<String, Any?>(fields.size)
    for ((i, f) in fields.withIndex()) row[f] = scalarToAny(parseScalarValue(cells[i], true))
    return row
}

/**
 * Parse a delta-participating full base payload into a [GenericSet], returning the
 * set and the declared `pack_root` (Section 10a).
 */
fun decodeGenericFull(text: String): Pair<GenericSet, String> {
    val lines = text.trimEnd('\n').split("\n")
    val hdr = parseHeaderFields(lines[0])
    if (hdr["profile"] != "generic") throw IllegalArgumentException("not a generic payload")

    var name = ""
    var key = hdr["key"] ?: ""
    var fields = emptyList<String>()
    val rows = mutableListOf<Map<String, Any?>>()
    var i = 1
    while (i < lines.size) {
        val line = lines[i]
        if (!line.startsWith("## ")) { i++; continue }
        val sh = parseSectionHeader(line.substring(3))
        name = sh.name
        fields = sh.fields
        if (key.isEmpty()) key = sh.keyField
        i++
        repeat(sh.count) {
            if (i >= lines.size) throw IllegalArgumentException("delta_invalid: fewer rows than declared count")
            rows.add(parseRow(lines[i], fields))
            i++
        }
    }
    return Pair(GenericSet(key = key, fields = fields, rows = rows, name = name), hdr["pack_root"] ?: "")
}

/**
 * Parse a delta payload into a [GenericDeltaPayload] (Section 10a.2). The result
 * can be applied with [verifyGenericDelta].
 */
fun decodeGenericDelta(text: String): GenericDeltaPayload {
    val lines = text.trimEnd('\n').split("\n")
    val hdr = parseHeaderFields(lines[0])
    if (hdr["profile"] != "generic") throw IllegalArgumentException("not a generic payload")
    if (hdr["delta"] != "true") throw IllegalArgumentException("not a delta payload")

    var key = hdr["key"] ?: ""
    var fields = emptyList<String>()
    var fieldsSet = false
    var added = emptyList<Map<String, Any?>>()
    var changed = emptyList<Map<String, Any?>>()
    val removed = mutableListOf<Any?>()

    var i = 1
    while (i < lines.size) {
        val line = lines[i]
        if (!line.startsWith("## ")) { i++; continue }
        val sh = parseSectionHeader(line.substring(3))
        if (key.isEmpty() && sh.keyField.isNotEmpty()) key = sh.keyField
        if (!fieldsSet && (sh.name == "added" || sh.name == "changed")) {
            fields = sh.fields
            fieldsSet = true
        }
        i++
        when (sh.name) {
            "added", "changed" -> {
                val rows = mutableListOf<Map<String, Any?>>()
                repeat(sh.count) {
                    if (i >= lines.size) {
                        throw IllegalArgumentException("delta_invalid: fewer rows than declared count in ## ${sh.name}")
                    }
                    rows.add(parseRow(lines[i], sh.fields))
                    i++
                }
                if (sh.name == "added") added = rows else changed = rows
            }
            "removed" -> {
                repeat(sh.count) {
                    if (i >= lines.size) {
                        throw IllegalArgumentException("delta_invalid: fewer identities than declared count in ## removed")
                    }
                    removed.add(scalarToAny(parseScalarValue(lines[i], true)))
                    i++
                }
            }
            else -> throw IllegalArgumentException("delta_invalid: unknown delta section ${sh.name}")
        }
    }
    return GenericDeltaPayload(
        key = key, fields = fields,
        baseRoot = hdr["base_root"] ?: "", newRoot = hdr["new_root"] ?: "",
        added = added, changed = changed, removed = removed, tool = hdr["tool"] ?: "",
    )
}
