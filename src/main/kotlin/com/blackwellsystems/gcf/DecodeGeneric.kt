package com.blackwellsystems.gcf

/**
 * Decode GCF tabular text into a generic value tree.
 *
 * Returns maps, lists, and primitives matching the original structure.
 * Handles tabular arrays, key-value pairs, nested sections, inline primitive
 * arrays, nested fields in tabular rows, empty arrays, and value parsing
 * (`-` = null, `true`/`false` = bool, numbers, quoted strings).
 *
 * If the input starts with `GCF ` (graph profile), falls back to [decode]
 * and returns the Payload as a map.
 */
fun decodeGeneric(input: String): Any? {
    val trimmed = input.trimEnd('\n', '\r')
    if (trimmed.isEmpty()) return null

    val lines = trimmed.split("\n")

    // Graph profile fallback.
    if (lines.isNotEmpty() && lines[0].startsWith("GCF ")) {
        val p = decode(input)
        return payloadToMap(p)
    }

    val result = mutableMapOf<String, Any?>()
    parseObject(lines, 0, 0, result)
    return result
}

/**
 * Parse key=value, ## section, tabular array, and inline array lines at the
 * given indentation depth. Returns the number of lines consumed.
 */
private fun parseObject(lines: List<String>, start: Int, depth: Int, out: MutableMap<String, Any?>): Int {
    val indent = "  ".repeat(depth)
    var i = start

    while (i < lines.size) {
        val trimmed = lines[i].trimEnd('\r')

        if (trimmed.isEmpty() || trimmed.startsWith("# ")) {
            i++
            continue
        }

        // Check indentation.
        if (depth > 0 && !trimmed.startsWith(indent)) {
            break
        }

        val content = if (depth > 0) trimmed.substring(indent.length) else trimmed

        // Skip _summary lines.
        if (content.startsWith("## _summary")) {
            i++
            continue
        }

        // Tabular array: ## name [count]{fields}
        if (content.startsWith("## ")) {
            val header = content.substring(3)

            val bracketIdx = header.indexOf(" [")
            if (bracketIdx >= 0) {
                val name = header.substring(0, bracketIdx)
                val rest = header.substring(bracketIdx + 2)
                val closeBracket = rest.indexOf(']')
                if (closeBracket >= 0) {
                    val afterBracket = rest.substring(closeBracket + 1)
                    if (afterBracket.startsWith("{")) {
                        // Tabular with field declaration.
                        val fieldEnd = afterBracket.indexOf('}')
                        if (fieldEnd >= 0) {
                            val fields = afterBracket.substring(1, fieldEnd).split(",")
                            i++
                            val (rows, consumed) = parseTabularRows(lines, i, depth, fields)
                            out[name] = rows
                            i += consumed
                            continue
                        }
                    } else {
                        // Count-only header.
                        val countStr = rest.substring(0, closeBracket)
                        if (countStr == "0") {
                            out[name] = mutableListOf<Any?>()
                            i++
                            continue
                        }
                        // Non-uniform array with @N items.
                        i++
                        val (items, consumed) = parseNonUniformArray(lines, i, depth)
                        out[name] = items
                        i += consumed
                        continue
                    }
                }
            }

            // Plain section header: ## key (nested object).
            var name = header
            val idx = name.indexOf(" [")
            if (idx >= 0) {
                name = name.substring(0, idx)
            }
            i++
            val nested = mutableMapOf<String, Any?>()
            val consumed = parseObject(lines, i, depth + 1, nested)
            out[name] = nested
            i += consumed
            continue
        }

        // Inline primitive array: name[N]: val1,val2,...
        val bracketPos = content.indexOf('[')
        if (bracketPos > 0) {
            val colonPos = content.indexOf("]: ")
            if (colonPos > bracketPos) {
                val name = content.substring(0, bracketPos)
                val valsStr = content.substring(colonPos + 3)
                val vals = valsStr.split(",").map { parseValue(it.trim()) }
                out[name] = vals
                i++
                continue
            }
        }

        // Key=value pair.
        val eqIdx = content.indexOf('=')
        if (eqIdx > 0) {
            val key = content.substring(0, eqIdx)
            val value = content.substring(eqIdx + 1)
            out[key] = parseValue(value)
            i++
            continue
        }

        // Unrecognized line, skip.
        i++
    }

    return i - start
}

/**
 * Parse pipe-separated rows following a tabular header.
 */
private fun parseTabularRows(
    lines: List<String>,
    start: Int,
    depth: Int,
    fields: List<String>
): Pair<List<Any?>, Int> {
    val indent = "  ".repeat(depth)
    val rows = mutableListOf<Any?>()
    var i = start

    while (i < lines.size) {
        val line = lines[i].trimEnd('\r')
        if (line.isEmpty()) {
            i++
            continue
        }

        val content: String
        if (depth > 0) {
            if (!line.startsWith(indent)) break
            content = line.substring(indent.length)
        } else {
            content = line
        }

        // Stop at next section header or _summary.
        if (content.startsWith("## ")) break

        // Skip comments.
        if (content.startsWith("# ")) {
            i++
            continue
        }

        // Strip @N prefix if present.
        var rowData = content
        var hasNested = false
        if (rowData.startsWith("@")) {
            val spaceIdx = rowData.indexOf(' ')
            if (spaceIdx > 0) {
                rowData = rowData.substring(spaceIdx + 1)
                hasNested = true
            }
        }

        // Parse pipe-separated values.
        val vals = rowData.split("|")
        val row = mutableMapOf<String, Any?>()
        for ((j, f) in fields.withIndex()) {
            row[f] = if (j < vals.size) parseValue(vals[j]) else null
        }

        i++

        // Parse nested fields (.fieldname).
        if (hasNested) {
            val nestedIndent = indent + "  "
            while (i < lines.size) {
                val nestedLine = lines[i].trimEnd('\r')
                if (!nestedLine.startsWith(nestedIndent)) break
                val nestedContent = nestedLine.substring(nestedIndent.length)

                if (nestedContent.startsWith(".")) {
                    val fieldName = nestedContent.substring(1)
                    i++
                    val nested = mutableMapOf<String, Any?>()
                    val consumed = parseObject(lines, i, depth + 2, nested)
                    row[fieldName] = nested
                    i += consumed
                } else {
                    break
                }
            }
        }

        rows.add(row)
    }

    return Pair(rows, i - start)
}

/**
 * Parse @N items in a non-uniform array section.
 */
private fun parseNonUniformArray(lines: List<String>, start: Int, depth: Int): Pair<List<Any?>, Int> {
    val indent = "  ".repeat(depth)
    val items = mutableListOf<Any?>()
    var i = start

    while (i < lines.size) {
        val line = lines[i].trimEnd('\r')
        if (line.isEmpty()) {
            i++
            continue
        }

        val content: String
        if (depth > 0) {
            if (!line.startsWith(indent)) break
            content = line.substring(indent.length)
        } else {
            content = line
        }

        if (content.startsWith("## ")) break

        if (content.startsWith("@")) {
            val spaceIdx = content.indexOf(' ')
            if (spaceIdx > 0) {
                val value = content.substring(spaceIdx + 1)
                items.add(parseValue(value))
            }
            i++
        } else {
            break
        }
    }

    return Pair(items, i - start)
}

/**
 * Convert a single GCF value string to a typed Kotlin value.
 */
private fun parseValue(s: String): Any? {
    if (s == "-") return null
    if (s == "true") return true
    if (s == "false") return false
    if (s == "\"\"") return ""

    // Quoted string.
    if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
        var inner = s.substring(1, s.length - 1)
        inner = inner.replace("\\\"", "\"")
        inner = inner.replace("\\\\", "\\")
        return inner
    }

    // Try integer (Long to match Go's int64).
    s.toLongOrNull()?.let { return it }

    // Try float.
    s.toDoubleOrNull()?.let { return it }

    return s
}

/**
 * Convert a Payload to a generic map for uniform return type.
 */
private fun payloadToMap(p: Payload): Map<String, Any?> {
    val syms = p.symbols.map { s ->
        mapOf(
            "qualifiedName" to s.qualifiedName,
            "kind" to s.kind,
            "score" to s.score,
            "provenance" to s.provenance,
            "distance" to s.distance,
        )
    }
    val edges = p.edges.map { e ->
        val m = mutableMapOf<String, Any?>(
            "source" to e.source,
            "target" to e.target,
            "edgeType" to e.edgeType,
        )
        if (e.status.isNotEmpty()) {
            m["status"] = e.status
        }
        m
    }
    return mapOf(
        "tool" to p.tool,
        "tokenBudget" to p.tokenBudget,
        "tokensUsed" to p.tokensUsed,
        "packRoot" to p.packRoot,
        "symbols" to syms,
        "edges" to edges,
    )
}
