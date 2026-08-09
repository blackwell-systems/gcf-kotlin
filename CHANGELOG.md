# Changelog

## v2.5.2 (2026-08-09)

- **Score rounding fix (SPEC 5, spec v3.5.1 errata).** The graph node-line `score` now rounds half-to-even on the exact IEEE-754 double, matching the Go/Rust/Python/Swift/.NET reference. The previous `"%.2f".format` (java.util.Formatter) formatter rounded half-up, so it diverged at exact binary midpoints (`0.125` -> `0.13` instead of `0.12`, `0.625` -> `0.63` instead of `0.62`) - a silent, non-interoperable wire difference. Pinned by the new `graph-encode/004_score_midpoint_rounding` conformance fixture. Encoding is unchanged for every non-midpoint score.

## v2.5.1 (2026-08-07)

- Decoders now reject a declared `[N]` section count that does not match the actual item count, in both directions, per SPEC Section 13 (Count Validation). A declared count smaller than the rows or entries present was previously read as a limit and the surplus was dropped; it is now an error. Covers the generic tabular, keyed-map, and root-array forms, the delta and full-set decoders, and the graph `## edges [N]` section. Valid payloads and encoding are unchanged.

## v2.5.0 (2026-08-07)

### Added
- Keyed-tabular map encoding (SPEC 7.2a): a JSON object whose values are all objects forming a tabular set is encoded as a keyed table (`## [N:]{key,...}`) - the shared value fields are declared once, with one key-prefixed row per member. Canonical by default, supported in nested and streaming positions, and integrated with generic delta using the map key as the identity.

### Changed
- Negative zero is canonicalized to `0` for both integer and floating-point values (SPEC 2.3.1).
- Canonical-output alignment across all six SDKs: object key ordering, graph header fields, and symbol ordering follow the specification and reference implementation exactly.

### Testing
- Conformance runners assert re-encode idempotence (`encode(decode(x)) == x`) for the generic, graph, and delta profiles; a differential cross-SDK fuzz was added to the verification suite.

## v2.4.0 (2026-07-12)

### Fixes

- The conformance runner now hard-fails on any unhandled operation (instead of silently skipping it) and exercises session, delta, roundtrip, and pack-root fixtures end to end; the graph delta wire decode and verify path is now covered, so no operations remain allow-listed.
- Implemented the graph delta wire decoder and verifier (`decodeDelta` / `verifyDelta`): parse a `GCF profile=graph delta=true` wire back into removed/added symbols and edge changes, apply them atomically to a base snapshot, recompute `pack_root`, and reject a wrong `new_root` with `root_mismatch` (SPEC 10.4). The `## added` encoder now emits the trailing `distance` field (SPEC 3.4.1, Section 10.1). The shared `graph-delta` fixtures now run end to end: 001 (encode, gains the trailing distance), 002 (verified apply), 003 (`root_mismatch` rejection).
- **Session encoding correctness fix.** `encodeWithSession` assigned per-response local IDs instead of stable session-global IDs, so the cross-call dedup references (`@N  # previously transmitted`) pointed at the wrong symbols, and the header emitted zero-valued `budget`/`tokens`/`edges`. Both are fixed to match the reference; graph session output is now byte-identical across all six SDKs. This had gone undetected because the conformance runner skipped the shared graph-session fixtures (now wired).
- Added the graph-profile PackRoot (`packRoot(symbols, edges)`, gcf-pack-root-v1, SPEC 10.2): the content-addressed sha256 over canonical, independently-sorted symbol/edge records, byte-identical to gcf-go/rust/typescript/python/swift. The conformance runner now exercises the shared `graph-pack-root` fixtures, which it had been skipping (so this primitive was previously unimplemented and untested).
- Buffered graph encoder: order edges by source ID, then target ID, then edge type (SPEC 16.1), instead of emitting them in input order. Decode-invariant (edges are a set) and does not affect `pack_root` (which sorts edge records independently), so no content addresses change. Pinned by shared fixture `graph-encode/003`. Streaming edges remain in producer-arrival order.
- Decoder: reject an orphan `.field` attachment (a `.field` whose name is neither a `^`-marked column of its row nor a `>`-containing field name, SPEC 7.4.6.1.4) instead of silently absorbing it as an undeclared extra field. Such a stray attachment previously decoded to a record no encoder produces, silently injecting a field onto the last-parsed row (a lossless round-trip hole); now rejected per SPEC 16.5 (`orphan_attachment`).
- Decoder: reject an orphan positional inline body (a pipe-delimited line with no eligible `^{}` attachment-marker cell) instead of silently dropping it. The object-body parser previously skipped any unrecognized line, so a stray positional body (e.g. a second `Bob|b@t.com` after a row's one inline cell was filled) vanished with no error (silent data loss); now rejected per SPEC 16.5 (`orphan_inline_attachment`).
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
