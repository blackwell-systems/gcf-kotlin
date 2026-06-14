package com.blackwellsystems.gcf

/**
 * Decode GCF  generic or graph profile text into a value.
 */
@Suppress("UNCHECKED_CAST")
fun decodeGeneric(input: String): Any? {
    val trimmed = input.trimEnd('\n', '\r')
    if (trimmed.isEmpty()) throw IllegalArgumentException("missing_header: empty input")

    val lines = trimmed.split("\n")
    val header = lines[0].trimEnd('\r')
    if (!header.startsWith("GCF ")) throw IllegalArgumentException("missing_header: first line does not begin with GCF")

    val profile = parseHeaderProfile(header)
    if (profile == "graph") {
        val p = decode(input)
        return payloadToMap(p)
    }
    if (profile != "generic") throw IllegalArgumentException("unknown_profile: $profile")

    val contentLines = mutableListOf<String>()
    var summaryLine = ""
    var deferredCount = 0
    for (line in lines.drop(1)) {
        val l = line.trimEnd('\r')
        if (l.isEmpty()) continue
        for (c in l) { if (c == '\t') throw IllegalArgumentException("tab_indentation: tabs in leading whitespace"); if (c != ' ') break }
        val t = l.trimStart()
        if (t.startsWith("# ")) continue
        if (t.startsWith("##! ")) { summaryLine = t; continue }
        if (t.startsWith("## ") && "[?]" in t) deferredCount++
        contentLines.add(l)
    }

    if (summaryLine.isNotEmpty() && deferredCount > 0) {
        validateSummaryCounts(summaryLine, deferredCount, contentLines)
    }

    if (contentLines.isEmpty()) return linkedMapOf<String, Any?>()

    val first = contentLines[0].trimStart()
    if (first.startsWith("=")) {
        if (contentLines.size > 1) throw IllegalArgumentException("trailing_characters: extra lines after root scalar")
        return scalarToAny(parseScalarValue(first.drop(1)))
    }
    if (first.startsWith("## [")) {
        val (arr, _) = parseArrayFromHeader(contentLines, 0, 0, first.drop(3))
        return arr
    }

    val result = linkedMapOf<String, Any?>()
    parseObjectBody(contentLines, 0, 0, result)
    return result
}

private fun parseHeaderProfile(header: String): String {
    val parts = header.split(Regex("\\s+"))
    if (parts.size < 2) throw IllegalArgumentException("missing_profile")
    val seen = mutableSetOf<String>()
    var profile = ""
    for (p in parts.drop(1)) {
        val eq = p.indexOf('=')
        if (eq < 0) throw IllegalArgumentException("malformed_header_field: $p")
        val key = p.substring(0, eq)
        if (key in seen) throw IllegalArgumentException("duplicate_header_field: $key")
        seen.add(key)
        if (key == "profile") profile = p.substring(eq + 1)
    }
    if (profile.isEmpty()) throw IllegalArgumentException("missing_profile")
    return profile
}

private fun scalarToAny(sv: ScalarParsed): Any? = when (sv) {
    is ScalarParsed.Null -> null
    is ScalarParsed.BoolVal -> sv.value
    is ScalarParsed.IntVal -> sv.value
    is ScalarParsed.DoubleVal -> sv.value
    is ScalarParsed.StringVal -> sv.value
    is ScalarParsed.Missing -> throw IllegalArgumentException("invalid_missing")
    is ScalarParsed.Attachment -> throw IllegalArgumentException("invalid_attachment_marker")
    is ScalarParsed.InlineAttachment -> throw IllegalArgumentException("invalid_inline_attachment_marker")
}

private fun parseObjectBody(lines: List<String>, start: Int, depth: Int, out: LinkedHashMap<String, Any?>): Int {
    val ind = "  ".repeat(depth)
    var i = start
    while (i < lines.size) {
        val line = lines[i]
        if (depth > 0 && !line.startsWith(ind)) break
        val content = if (depth > 0) line.drop(ind.length) else line
        if (content.isNotEmpty() && content[0] == ' ') throw IllegalArgumentException("invalid_indent: indentation increases by more than one level")

        if (content.startsWith("## ")) {
            val hdr = content.drop(3)
            val bi = hdr.indexOf(" [")
            if (bi >= 0) {
                val name = parseKeyFromHeader(hdr.substring(0, bi))
                checkDup(out, name)
                val (arr, consumed) = parseArrayFromHeader(lines, i, depth, hdr.substring(bi))
                out[name] = arr
                i += consumed; continue
            }
            val name = parseKeyFromHeader(hdr)
            checkDup(out, name)
            i++
            val nested = linkedMapOf<String, Any?>()
            val consumed = parseObjectBody(lines, i, depth + 1, nested)
            out[name] = nested
            i += consumed; continue
        }

        if (!content.startsWith("@") && !content.startsWith("##")) {
            val bracketIdx = content.indexOf('[')
            if (bracketIdx > 0) {
                val rest = content.substring(bracketIdx)
                val closeIdx = rest.indexOf(']')
                if (closeIdx >= 0) {
                    val after = rest.substring(closeIdx + 1)
                    if (after.startsWith(": ") || after == ":") {
                        val name = parseKeyFromHeader(content.substring(0, bracketIdx))
                        checkDup(out, name)
                        val (arr, _) = parseArrayFromHeader(lines, i, depth, rest)
                        out[name] = arr
                        i++; continue
                    }
                }
            }
        }

        val eqIdx = findKVSplit(content)
        if (eqIdx != null && eqIdx > 0) {
            val name = parseKeyFromHeader(content.substring(0, eqIdx))
            checkDup(out, name)
            out[name] = scalarToAny(parseScalarValue(content.substring(eqIdx + 1)))
            i++; continue
        }
        i++
    }
    return i - start
}

private fun findKVSplit(s: String): Int? {
    if (s.isEmpty()) return null
    if (s[0] == '"') {
        var i = 1
        while (i < s.length) {
            if (s[i] == '\\') { i += 2; continue }
            if (s[i] == '"') return if (i + 1 < s.length && s[i + 1] == '=') i + 1 else null
            i++
        }
        return null
    }
    val idx = s.indexOf('=')
    return if (idx >= 0) idx else null
}

private fun parseKeyFromHeader(s: String): String {
    val trimmed = s.trim()
    return if (trimmed.length >= 2 && trimmed[0] == '"') parseQuotedStringValue(trimmed) else trimmed
}

private fun checkDup(map: Map<String, Any?>, key: String) {
    if (key in map) throw IllegalArgumentException("duplicate_key: $key")
}

private fun parseArrayFromHeader(lines: List<String>, headerLine: Int, depth: Int, bracketPart: String): Pair<Any, Int> {
    val bp = bracketPart.trimStart()
    if (!bp.startsWith("[")) throw IllegalArgumentException("invalid_count: $bp")
    val close = bp.indexOf(']')
    if (close < 0) throw IllegalArgumentException("invalid_count: $bp")
    val countStr = bp.substring(1, close)
    val after = bp.substring(close + 1)
    val count = if (countStr == "?") -1 else parseCountVal(countStr)

    if (count == 0 && !after.startsWith("{") && !after.startsWith(":")) return emptyList<Any?>() to 1

    if (after.startsWith(": ") || after == ":") {
        val valsStr = if (after.startsWith(": ")) after.drop(2) else ""
        if (valsStr.isEmpty()) {
            if (count >= 0 && count != 0) throw IllegalArgumentException("count_mismatch: declared $count, got 0")
            return emptyList<Any?>() to 1
        }
        val vals = splitRespectingQuotes(valsStr, ',')
        if (count >= 0 && vals.size != count) throw IllegalArgumentException("count_mismatch: declared $count, got ${vals.size}")
        return vals.map { scalarToAny(parseScalarValue(it.trim())) } to 1
    }

    if (after.startsWith("{")) {
        val braceEnd = findClosingBraceIdx(after) ?: throw IllegalArgumentException("invalid field declaration")
        val fields = splitFieldDeclValue(after.substring(0, braceEnd + 1))
        val (rows, consumed) = parseTabularBody(lines, headerLine + 1, depth, fields, count)
        if (count >= 0 && rows.size != count) throw IllegalArgumentException("count_mismatch: declared $count, got ${rows.size}")
        return rows to consumed + 1
    }

    val (items, consumed) = parseExpandedBody(lines, headerLine + 1, depth)
    if (count >= 0 && items.size != count) throw IllegalArgumentException("count_mismatch: declared $count, got ${items.size}")
    return items to consumed + 1
}

private fun parseAttachmentName(rest: String): Pair<String, String> {
    if (rest.isNotEmpty() && rest[0] == '"') {
        var j = 1
        while (j < rest.length) {
            if (rest[j] == '\\') { j += 2; continue }
            if (rest[j] == '"') {
                val name = parseQuotedStringValue(rest.substring(0, j + 1))
                return name to rest.substring(j + 1)
            }
            j++
        }
        return "" to rest
    }
    val sp = rest.indexOf(' ')
    return if (sp >= 0) rest.substring(0, sp) to rest.substring(sp) else rest to ""
}

private data class AttachmentResult(val name: String, val value: Any?, val consumed: Int, val parsedFields: List<String>?)

@Suppress("UNCHECKED_CAST")
private fun parseAttachment(lines: List<String>, lineIdx: Int, rest: String, depth: Int, sharedSchemas: MutableMap<String, List<String>>): AttachmentResult {
    val (name, afterNameRaw) = parseAttachmentName(rest)
    if (name.isEmpty() && !rest.startsWith("\"\"")) throw IllegalArgumentException("invalid attachment: $rest")
    val afterName = afterNameRaw.trimStart()

    if (afterName.startsWith("{}")) {
        val nested = linkedMapOf<String, Any?>()
        val consumed = parseObjectBody(lines, lineIdx + 1, depth, nested)
        return AttachmentResult(name, nested, consumed + 1, null)
    }
    if (afterName.startsWith("[")) {
        val cb = afterName.indexOf(']')
        if (cb < 0) throw IllegalArgumentException("invalid_count: missing ]")
        val afterClose = afterName.substring(cb + 1)

        if (afterClose.startsWith("{")) {
            val endBrace = findClosingBraceIdx(afterClose)
            var parsedFields: List<String>? = null
            if (endBrace != null) {
                try { parsedFields = splitFieldDeclValue(afterClose.substring(0, endBrace + 1)) } catch (_: Exception) {}
            }
            val (arr, consumed) = parseArrayFromHeader(lines, lineIdx, depth, afterName)
            return AttachmentResult(name, arr, consumed, parsedFields)
        }

        // Inline primitive array.
        if (afterClose.startsWith(": ") || afterClose == ":") {
            val (arr, consumed) = parseArrayFromHeader(lines, lineIdx, depth, afterName)
            return AttachmentResult(name, arr, consumed, null)
        }

        // Shared schema.
        if (name in sharedSchemas) {
            val sf = sharedSchemas[name]!!
            val countStr = afterName.substring(1, cb)
            val count = if (countStr == "?") -1 else countStr.toInt()
            if (count == 0) return AttachmentResult(name, emptyList<Any?>(), 1, null)
            var useShared = true
            val nextIdx = lineIdx + 1
            val ind = "  ".repeat(depth)
            if (nextIdx < lines.size) {
                var nc = lines[nextIdx]
                if (depth > 0 && nc.startsWith(ind)) nc = nc.drop(ind.length)
                if (nc.trimStart().startsWith("@")) useShared = false
            }
            if (useShared) {
                val (rows, consumed) = parseTabularBody(lines, lineIdx + 1, depth, sf, count)
                if (count >= 0 && rows.size != count) throw IllegalArgumentException("count_mismatch: declared $count, got ${rows.size}")
                return AttachmentResult(name, rows, consumed + 1, null)
            }
        }

        val (arr, consumed) = parseArrayFromHeader(lines, lineIdx, depth, afterName)
        return AttachmentResult(name, arr, consumed, null)
    }
    throw IllegalArgumentException("invalid attachment form: $afterName")
}

@Suppress("UNCHECKED_CAST")
private fun parseTabularBody(lines: List<String>, start: Int, depth: Int, fields: List<String>, expectedCount: Int): Pair<List<Any>, Int> {
    val ind = "  ".repeat(depth)
    val rows = mutableListOf<Any>()
    var i = start

    val inlineSchemas = mutableMapOf<String, List<String>>()
    val sharedArraySchemas = mutableMapOf<String, List<String>>()

    while (i < lines.size) {
        val line = lines[i]
        val content = if (depth > 0) { if (!line.startsWith(ind)) break; line.drop(ind.length) } else line
        if (content.startsWith("## ") || content.startsWith("##!")) break
        if (content.isNotEmpty() && content[0] == ' ') {
            val trimmed = content.trimStart()
            if (trimmed.startsWith(".")) break
            break
        }

        var rowData = content
        var rowHasID = false
        if (rowData.startsWith("@")) {
            val sp = rowData.indexOf(' ')
            if (sp > 0) {
                val idStr = rowData.substring(1, sp)
                if (idStr.all { it.isDigit() } && idStr.isNotEmpty()) {
                    rowData = rowData.substring(sp + 1); rowHasID = true
                }
            }
        }

        val vals = splitRespectingQuotes(rowData, '|')
        if (vals.size != fields.size) throw IllegalArgumentException("row_width_mismatch: expected ${fields.size}, got ${vals.size}")

        val cellValues = linkedMapOf<String, Any?>()
        val traditionalAttFields = mutableListOf<String>()
        val inlineAttFields = mutableListOf<String>()
        val inlineAttOrder = mutableListOf<String>()
        val missingFields = mutableSetOf<String>()

        for ((j, f) in fields.withIndex()) {
            val cellVal = vals[j]
            if (cellVal.startsWith("^{") && cellVal.endsWith("}")) {
                val ifs = splitFieldDeclValue(cellVal.drop(1))
                inlineSchemas[f] = ifs
                inlineAttFields.add(f)
                inlineAttOrder.add(f)
                continue
            }
            when (val parsed = parseScalarValue(cellVal, tabularContext = true)) {
                is ScalarParsed.Missing -> missingFields.add(f)
                is ScalarParsed.Attachment -> {
                    if (f in inlineSchemas) { inlineAttFields.add(f); inlineAttOrder.add(f) }
                    else traditionalAttFields.add(f)
                }
                is ScalarParsed.InlineAttachment -> {
                    val ifs = splitFieldDeclValue(parsed.schema)
                    inlineSchemas[f] = ifs
                    inlineAttFields.add(f)
                    inlineAttOrder.add(f)
                }
                else -> cellValues[f] = scalarToAny(parsed)
            }
        }
        i++

        val allAttFields = traditionalAttFields + inlineAttFields
        val attachmentValues = linkedMapOf<String, Any?>()

        // Check for orphan attachments when row has ID but no ^ cells.
        if (rowHasID && allAttFields.isEmpty()) {
            if (i < lines.size) {
                val peekLine = lines[i]
                var peekContent = ""
                if (depth == 0 || peekLine.startsWith(ind)) {
                    peekContent = if (depth > 0) peekLine.drop(ind.length) else peekLine
                }
                // Handle v2 indented attachments.
                if (!peekContent.startsWith(".") && peekContent.startsWith("  .")) {
                    peekContent = peekContent.drop(2)
                }
                if (peekContent.startsWith(".")) {
                    val (orphanName, _) = parseAttachmentName(peekContent.drop(1))
                    throw IllegalArgumentException("orphan_attachment: .$orphanName without matching ^ cell")
                }
            }
        }

        if (rowHasID && allAttFields.isNotEmpty()) {
            val resolvedAttachments = mutableSetOf<String>()
            var inlineIdx = 0

            while (i < lines.size && attachmentValues.size < allAttFields.size) {
                val aLine = lines[i]
                var aContent: String? = when {
                    depth == 0 || aLine.startsWith(ind) -> if (depth > 0) aLine.drop(ind.length) else aLine
                    else -> null
                }
                if (aContent == null) break

                // Handle v2 indented attachments: strip one extra indent level.
                if (aContent != null && !aContent.startsWith(".") && aContent.startsWith("  .")) {
                    aContent = aContent.drop(2)
                }

                if (aContent!!.startsWith(".")) {
                    val rest = aContent.drop(1)
                    val (attName, afterNameR) = parseAttachmentName(rest)
                    val afterNameS = afterNameR.trimStart()

                    // Check orphan: attachment for field not in allAttFields.
                    if (attName !in allAttFields) {
                        throw IllegalArgumentException("orphan_attachment: $attName without matching ^ cell")
                    }
                    // Check duplicate.
                    if (attName in resolvedAttachments) {
                        throw IllegalArgumentException("duplicate_attachment: $attName")
                    }

                    val ifs = inlineSchemas[attName]
                    if (ifs != null && !afterNameS.startsWith("{}") && !afterNameS.startsWith("[")) {
                        val inlineVals = splitRespectingQuotes(afterNameS, '|')
                        if (inlineVals.size != ifs.size) throw IllegalArgumentException("inline_width_mismatch: $attName expected ${ifs.size}, got ${inlineVals.size}")
                        val obj = linkedMapOf<String, Any?>()
                        for ((k, inf) in ifs.withIndex()) {
                            val p = parseScalarValue(inlineVals[k], tabularContext = true)
                            if (p !is ScalarParsed.Missing) obj[inf] = scalarToAny(p)
                        }
                        attachmentValues[attName] = obj
                        resolvedAttachments.add(attName)
                        i++; continue
                    }

                    val result = parseAttachment(lines, i, rest, depth + 2, sharedArraySchemas)
                    if (rows.isEmpty() && result.parsedFields != null) {
                        sharedArraySchemas[result.name] = result.parsedFields
                    }
                    attachmentValues[result.name] = result.value
                    resolvedAttachments.add(result.name)
                    i += result.consumed; continue
                }

                // No-prefix: positional inline data.
                var foundInline = false
                var nextInlineField = ""
                while (inlineIdx < inlineAttOrder.size) {
                    val candidate = inlineAttOrder[inlineIdx]
                    if (candidate !in attachmentValues) { nextInlineField = candidate; foundInline = true; break }
                    inlineIdx++
                }
                if (!foundInline) break

                val ifs = inlineSchemas[nextInlineField]!!
                val inlineVals = splitRespectingQuotes(aContent, '|')
                if (inlineVals.size != ifs.size) throw IllegalArgumentException("inline_width_mismatch: $nextInlineField expected ${ifs.size}, got ${inlineVals.size}")
                val obj = linkedMapOf<String, Any?>()
                for ((k, inf) in ifs.withIndex()) {
                    val p = parseScalarValue(inlineVals[k], tabularContext = true)
                    if (p !is ScalarParsed.Missing) obj[inf] = scalarToAny(p)
                }
                attachmentValues[nextInlineField] = obj
                inlineIdx++; i++
            }

            // Check for extra attachment lines after all fields resolved (duplicate).
            if (i < lines.size) {
                val extraLine = lines[i]
                var extraContent = ""
                if (depth == 0 || extraLine.startsWith(ind)) {
                    extraContent = if (depth > 0) extraLine.drop(ind.length) else extraLine
                }
                // Handle v2 indented attachments.
                if (!extraContent.startsWith(".") && extraContent.startsWith("  .")) {
                    extraContent = extraContent.drop(2)
                }
                if (extraContent.startsWith(".")) {
                    val (extraName, _) = parseAttachmentName(extraContent.drop(1))
                    if (extraName in resolvedAttachments) {
                        throw IllegalArgumentException("duplicate_attachment: $extraName")
                    }
                }
            }

            for (f in allAttFields) {
                if (f !in attachmentValues) throw IllegalArgumentException("missing_attachment: $f")
            }
        }

        if (!rowHasID || allAttFields.isEmpty()) {
            val attIndent = ind + "  "
            if (i < lines.size && lines[i].startsWith(attIndent)) {
                val peek = lines[i].drop(attIndent.length)
                if (peek.startsWith(".")) throw IllegalArgumentException("orphan_attachment: $peek")
            }
        }

        val row = linkedMapOf<String, Any?>()
        for (f in fields) {
            if (f in missingFields) continue
            if (f in cellValues) { row[f] = cellValues[f]; continue }
            if (f in attachmentValues) { row[f] = attachmentValues[f]; continue }
        }
        rows.add(row)

        if (expectedCount >= 0 && rows.size >= expectedCount) break
    }
    return rows to (i - start)
}

private fun parseAttachment(lines: List<String>, lineIdx: Int, rest: String, depth: Int): Triple<String, Any?, Int> {
    val name: String
    val afterName: String
    if (rest.isNotEmpty() && rest[0] == '"') {
        var closeIdx = -1
        var j = 1
        while (j < rest.length) {
            if (rest[j] == '\\') { j += 2; continue }
            if (rest[j] == '"') { closeIdx = j; break }
            j++
        }
        if (closeIdx < 0) throw IllegalArgumentException("unterminated_quote")
        name = parseQuotedStringValue(rest.substring(0, closeIdx + 1))
        afterName = rest.substring(closeIdx + 1).trimStart()
    } else {
        val sp = rest.indexOf(' ')
        if (sp < 0) throw IllegalArgumentException("invalid attachment: $rest")
        name = rest.substring(0, sp)
        afterName = rest.substring(sp).trimStart()
    }

    if (afterName.startsWith("{}")) {
        val nested = linkedMapOf<String, Any?>()
        val consumed = parseObjectBody(lines, lineIdx + 1, depth, nested)
        return Triple(name, nested, consumed + 1)
    }
    if (afterName.startsWith("[")) {
        val (arr, consumed) = parseArrayFromHeader(lines, lineIdx, depth, afterName)
        return Triple(name, arr, consumed)
    }
    throw IllegalArgumentException("invalid attachment form: $afterName")
}

private fun parseExpandedBody(lines: List<String>, start: Int, depth: Int): Pair<List<Any?>, Int> {
    val ind = "  ".repeat(depth)
    val items = mutableListOf<Any?>()
    var i = start

    while (i < lines.size) {
        val line = lines[i]
        val content = if (depth > 0) { if (!line.startsWith(ind)) break; line.drop(ind.length) } else line
        if (content.startsWith("## ") || content.startsWith("##!")) break
        if (!content.startsWith("@")) break
        val sp = content.indexOf(' ')
        if (sp < 0) break

        val idStr = content.substring(1, sp)
        val id = idStr.toIntOrNull()
        if (id != null && id != items.size) throw IllegalArgumentException("invalid_item_id: expected @${items.size}, got @$idStr")

        val marker = content.substring(sp + 1)

        if (marker.startsWith("=")) {
            items.add(scalarToAny(parseScalarValue(marker.drop(1))))
            i++; continue
        }
        if (marker.startsWith("{}")) {
            val nested = linkedMapOf<String, Any?>()
            i++
            val consumed = parseObjectBody(lines, i, depth + 1, nested)
            items.add(nested)
            i += consumed; continue
        }
        if (marker.startsWith("[")) {
            val (arr, consumed) = parseArrayFromHeader(lines, i, depth + 1, marker)
            items.add(arr)
            i += consumed; continue
        }
        break
    }
    return items to (i - start)
}

private fun parseCountVal(s: String): Int {
    if (s == "0") return 0
    if (s.isEmpty() || s[0] == '0') throw IllegalArgumentException("invalid_count: $s")
    val n = s.toIntOrNull() ?: throw IllegalArgumentException("invalid_count: $s")
    if (n.toString() != s) throw IllegalArgumentException("invalid_count: $s")
    return n
}

private fun payloadToMap(p: Payload): Map<String, Any?> = linkedMapOf(
    "tool" to p.tool,
    "tokenBudget" to p.tokenBudget,
    "tokensUsed" to p.tokensUsed,
    "packRoot" to p.packRoot,
    "symbols" to p.symbols.map {
        linkedMapOf("qualifiedName" to it.qualifiedName, "kind" to it.kind,
            "score" to it.score, "provenance" to it.provenance, "distance" to it.distance)
    },
    "edges" to p.edges.map {
        linkedMapOf("source" to it.source, "target" to it.target,
            "edgeType" to it.edgeType, "status" to it.status)
    }
)

private fun validateSummaryCounts(summaryLine: String, deferredCount: Int, contentLines: List<String>) {
    val countsStr = summaryLine.split(Regex("\\s+")).firstOrNull { it.startsWith("counts=") }?.drop(7) ?: return
    val countVals = countsStr.split(",")
    if (countVals.size != deferredCount) throw IllegalArgumentException("count_mismatch: summary has ${countVals.size} count entries but $deferredCount deferred sections")

    val actualCounts = mutableListOf<Int>()
    var inDeferred = false
    var currentCount = 0
    for (line in contentLines) {
        val t = line.trimStart()
        if (t.startsWith("## ") && "[?]" in t) { if (inDeferred) actualCounts.add(currentCount); inDeferred = true; currentCount = 0; continue }
        if (t.startsWith("## ")) { if (inDeferred) { actualCounts.add(currentCount); inDeferred = false }; continue }
        if (inDeferred && !t.startsWith(" ") && !t.startsWith(".")) currentCount++
    }
    if (inDeferred) actualCounts.add(currentCount)
    for ((idx, cv) in countVals.withIndex()) {
        val declared = cv.toIntOrNull() ?: throw IllegalArgumentException("count_mismatch: invalid count value '$cv'")
        if (idx < actualCounts.size && declared != actualCounts[idx]) throw IllegalArgumentException("count_mismatch: section $idx declared $declared in summary, actual ${actualCounts[idx]}")
    }
}
