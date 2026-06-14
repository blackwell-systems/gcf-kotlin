# Changelog

## v2.1.0 (2026-06-14)

### Spec v3.1

- `tool` field in graph profile header is now optional (SHOULD be present for MCP, not required)

### Bug Fixes

- Quote strings containing commas (conformance: `inline-schema/006_inline_with_quoted_values`)
- Decode v2-format indented attachments in tabular rows (conformance: `decode/002_attachment`)
- Reject duplicate attachments on the same row (conformance: `errors-v2/027_duplicate_attachment`)
- Reject orphan attachments on rows without `^` cells (conformance: `errors-v2/016_orphan_attachment`)

## v2.0.0 (2026-06-12)

### Breaking Changes

- `encodeGeneric` now produces inline schema format (not backwards compatible with v1.x decoders)
- Attachment lines no longer indented (same depth as parent row)
- Inline object fields use positional encoding without field-name prefix

### New Features

- Inline object schema: objects with 3+ scalar fields encoded positionally with `^{fields}` header
- Shared array schemas: identical nested arrays omit `{fields}` after first row
- 472M+ fuzz iterations across all 6 implementations, zero failures

### Bug Fixes

- Quote strings starting with `.` (dot prefix)
- Quote C1 control characters (U+0080-U+009F)
- Quote Unicode whitespace (NBSP, hair space, etc.)

## v1.0.1 (2026-06-10)

- CLI: `encode`, `decode`, `encode-generic`, `decode-generic` subcommands
- Both graph and generic profiles supported from the command line
- Fat jar target for standalone execution

## v1.0.0 (2026-06-07)

- SPEC v2.0 implementation: common scalar grammar, full JSON escaping, attachments, expanded form
- 40M property-based round-trips with zero failures
- 133/141 conformance fixtures passing

## v0.5.0 (2026-06-05)

- `GenericStreamEncoder`: zero-buffering tabular streaming encode (beginArray/writeRow/endArray/writeKV/writeSection/writeInlineArray)
- `decodeGeneric`: parse GCF tabular text into `Any?` (tabular arrays, key-value, nested sections, inline arrays, nested row fields, empty arrays, graph fallback)

## v0.3.0 (2026-06-05)

- `encodeGeneric`: primitive arrays inlined as `name[N]: val1,val2,val3`

## v0.2.0 (2026-06-05)

- **Breaking**: `encode()` now emits `edges=N` in header line
- **Breaking**: `encode()` now emits `## edges [N]` section header (was `## edges`)
- `decode()` updated to parse `## edges [N]` format (strips bracket suffix)
- Session encoder updated to emit new edge count format

## v0.1.0 (2026-06-04)

- Initial release
- `encode` / `decode`: full GCF round-trip
- `encodeWithSession`: session deduplication
- `encodeDelta`: delta encoding
- `encodeGeneric`: tabular profile encoding
- Thread-safe `Session` class (synchronized)
- 16 kind abbreviations
- JitPack distribution, zero dependencies
