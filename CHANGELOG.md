# Changelog

## v2.4.0 (2026-07-12)

### Fixes

- Decoder: reject an orphan `.field` attachment (a `.field` whose name is neither a `^`-marked column of its row nor a `>`-containing field name, SPEC 7.4.6.1.4) instead of silently absorbing it as an undeclared extra field. Such a stray attachment previously decoded to a record no encoder produces, silently injecting a field onto the last-parsed row (a lossless round-trip hole); now rejected per SPEC 16.5 (`orphan_attachment`).
- Graph streaming trailer: the edge count is now always the last `counts` entry, even when the stream has no edges (positional `counts=2,1,0`; labeled `counts=…,edges:0`). A zero-edge stream previously dropped it, violating the SPEC §8.4 / §8.4.1 rule that the edge count is always present and last (the invariant that keeps the positional form unambiguous). The graph trailer is decoder-ignored, so this changes producer output only.

### Streaming: opt-in labeled trailer counts (SPEC §8.4.1)

- New `StreamOptions.labeledTrailerCounts`. When set, the `##! summary` graph streaming trailer emits `counts=` in the labeled form `label:count` per group (e.g. `counts=targets:2,related:1,edges:3`) instead of the default positional values-only form (`counts=2,1,3`). Default false is byte-identical to prior output.
- Opt-in and non-breaking: a producer-side comprehension aid for known weak consumers. The trailer counts remain informational (decoder-ignored) in both forms; neither changes the decoded payload. Mirrors the `gcf-go` reference.

### Conformance and docs

- The conformance runner now executes the `graph-stream-encode` fixtures (streaming-encode parity, previously decode-only): fixture 004 (positional trailer) and 005 (labeled trailer).
- README: corrected the streaming example trailer from the defunct `## _summary … sections=` to the real `##! summary … counts=`; README now leads with the project diagram.

## v2.3.0 (2026-07-12)

### Generic-profile delta encoding (SPEC §10a)

- Full producer + consumer implementation of generic-profile delta, byte-for-byte interoperable with `gcf-go`, `gcf-python`, `gcf-typescript`, `gcf-rust`, and `gcf-swift`:
  - `GenericSet` (keyed record set), `GenericDeltaPayload`
  - `genericPackRoot` (`gcf-pack-root-v1`, generic profile) with a purpose-built cell canonicalization (`canonicalCell`) decoupled from the wire cell encoder: collision-free (null/bool/number bare, strings always quoted) and record-safe. Fields and records sort by UTF-8 byte order (a `Comparator` over UTF-8 bytes) rather than Kotlin's default char (UTF-16) ordering, so pack roots are identical across SDKs.
  - `diffGenericSets` (the blessed producer path; centralizes the keyed-diff invariants), `encodeGenericFull`, `encodeGenericDelta`
  - `decodeGenericFull`, `decodeGenericDelta` (consumer wire parsing)
  - `verifyGenericDelta` (atomic apply + `new_root` verification)
  - `GenericDeltaSession` (SPEC §10a.8): producer-side re-anchor cadence helper. Thin sugar over the primitives (introduces no wire syntax); each `next` emits either a compact delta or, on its chosen cadence, a full re-anchor, updating its held base. `ReanchorPolicy.FixedN(n)` (re-anchors every `n` turns; `n <= 0` falls back to `DEFAULT_REANCHOR_N = 15`) and `ReanchorPolicy.SizeGuard` (re-anchors once cumulative delta bytes reach the current full-payload byte size). Cumulative and payload sizes are measured in UTF-8 bytes (`toByteArray(Charsets.UTF_8).size`), matching Go's `len(string)`, so the cadence is identical across SDKs. A schema change forces a full (§10a.7).
- Delta is opt-in and bilateral; the existing `encodeGeneric` path is unchanged (backward compatible). SHA-256 uses `java.security.MessageDigest` (JDK standard library, no dependency added).

### Tests

- Unit suite mirroring the other SDKs: self-proving round-trip (diff -> encode -> apply -> recomputed root), determinism / row-order invariance, no-type-collision canonicalization, every invariant/error path, full-payload wire round-trip, the complete server -> wire -> consumer end-to-end loop, and malformed-wire-fails-closed.
- Conformance runner support for `generic-pack-root`, `generic-delta`, `generic-delta-verify`, `generic-delta-decode`, and `generic-delta-session` shared fixtures; produces identical pack roots, delta wire, and re-anchor cadence to the Go, Python, TypeScript, Rust, and Swift SDKs.
- `GenericDeltaSessionTest` mirrors the Go session suite: FixedN cadence pattern, SizeGuard triggering, schema-change forced full, exactly two re-anchors over 30 same-schema turns at N=15, and the load-bearing consumer-stays-in-sync loop (apply each emission; recomputed root matches the producer state every turn) under both policies.
- `GenericDeltaFuzzTest`, mirroring `gcf-go`: the decoder never crashes on arbitrary/mutated input, and arbitrary UTF-8 string cells (including multi-byte and control characters) survive the full-wire round-trip with the pack root preserved.

## v2.2.2 (2026-07-10)

### Fixes

- **Losslessness (nested null):** a nested object that is null at an intermediate level (e.g. `{"meta":{"owner":null}}`) is no longer flattened. Previously its leaves encoded as absent (`~`) and unflattened to a missing key, silently dropping the null. Such fields now fall back to the attachment mechanism; a top-level null still flattens losslessly (emits `-`, reconstructs via the all-null rule). Enforced by the shared conformance fixtures `flatten/017`–`019`. Prototype pollution does not affect Kotlin (map-based).

### Tests

- `RoundtripV2Test."flatten roundtrip"`: aligned arrays whose shared fields are fixed-shape nested objects, with a field or an intermediate nested level sometimes null/absent — the shape the prior scalar-only generator never produced, leaving the flatten/unflatten path unexercised. Verified to fail on the pre-fix encoder and pass on the fix.

## v2.2.1 (2026-06-23)

### Flatten Opt-Out

- Added `GenericOptions` data class with `noFlatten` parameter to disable nested object flattening
- `encodeGeneric(data, GenericOptions(noFlatten = true))` produces attachment syntax instead of path columns
- Backward compatible: `encodeGeneric(data)` behavior unchanged (flatten on by default)
- Fixed: field names containing `>` no longer appear as tabular columns (spec rule 7.4.6.1.4)
- Fixed: field names containing `>` no longer eligible for flattening analysis
- Fixed: decoder no longer treats literal `>` in key names as a path separator
- Fixed: decoder accepts orphan attachments (fields excluded from column list)
- 10 targeted edge case tests for `>` in field names (both flatten modes)

## v2.2.0 (2026-06-22)

### Spec v3.2: Nested Object Flattening

- Encoder automatically flattens fixed-shape nested objects into `>` path column names
- Decoder reconstructs nested objects from `>` path columns
- 20-48% fewer tokens on deeply nested API data
- Falls back to attachment mechanism for non-flattenable cases

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
