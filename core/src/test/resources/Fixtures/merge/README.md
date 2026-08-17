# Merge fixtures

Real Minecraft Anvil-format (`.mca`) fixture pair used by `RealMcaMergeTest` to exercise
the `WorldMerger` slot-level merge through the production `RealFileSystem` +
`DefaultMcaIOFactory` path.

Layout:
- `base/` — complete region `r.0.0.mca` (slots 0..99) with lockstep `entities`/`poi`,
  a base-only region `r.1.1.mca` (slots 0..9), and `level.dat`.
- `patch/` — partial subset of the same region (slots 0..39) with lockstep
  `entities`/`poi`, and a newer `level.dat`.

Each chunk is a genuine Anvil chunk: 4-byte big-endian length + 1-byte compression
type (2 = ZLIB) + ZLIB-compressed NBT compound carrying `DataVersion` and
`InhabitedTime`, sector-aligned in a 8 KiB header region file.

Regenerate with:

```
python tools/gen_merge_fixtures.py
```
