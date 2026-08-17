"""Generate the real Anvil-format merge fixture pair under core/src/test/resources/Fixtures/merge.

Produces genuine Minecraft region (.mca) files: 8 KiB header (4096-byte location
table + 4096-byte timestamp table) followed by 4 KiB-sector-aligned chunks, each
chunk being a 4-byte big-endian length, a 1-byte compression type (2 = ZLIB) and a
ZLIB-compressed NBT compound. base/ holds a complete region (slots 0..99) plus its
entities/poi and a base-only region; patch/ holds a partial subset (slots 0..39).

Re-run with: python tools/gen_merge_fixtures.py
"""

import os
import struct
import zlib

ROOT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..",
    "core",
    "src",
    "test",
    "resources",
    "Fixtures",
    "merge",
)
DIM = "dimensions/minecraft/overworld"


def nbt_payload(inhabited: int) -> bytes:
    nbt = bytearray()
    nbt += b"\x0a"  # TAG_Compound
    nbt += b"\x00\x00"  # empty name
    nbt += b"\x03" + b"\x00\x0a" + b"DataVersion" + struct.pack(">i", 4326)
    nbt += b"\x04" + b"\x00\x0e" + b"InhabitedTime" + struct.pack(">q", inhabited)
    nbt += b"\x00"  # TAG_End
    return bytes(nbt)


def chunk_bytes(index: int, inhabited: int) -> bytes:
    comp = zlib.compress(nbt_payload(inhabited))
    return struct.pack(">i", 1 + len(comp)) + bytes([2]) + comp


def write_region(path: str, slots: list):
    loc = bytearray(4096)
    tim = bytearray(4096)
    data = bytearray()
    offset = 8192
    for index, inhabited in slots:
        entry = chunk_bytes(index, inhabited)
        pad = (4096 - (len(entry) % 4096)) % 4096
        data += entry + b"\x00" * pad
        off_sectors = offset // 4096
        size_sectors = (len(entry) + pad) // 4096
        loc[index * 4 : index * 4 + 4] = struct.pack(">I", (off_sectors << 8) | (size_sectors & 0xFF))
        tim[index * 4 : index * 4 + 4] = struct.pack(">i", 1700000000)
        offset += len(entry) + pad
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(loc)
        f.write(tim)
        f.write(data)


def main():
    base = os.path.join(ROOT, "base")
    patch = os.path.join(ROOT, "patch")

    base_slots = [(i, 1000 + i) for i in range(100)]
    patch_slots = [(i, 2000 + i) for i in range(40)]

    # base: complete region with entities/poi, plus a base-only region and level.dat
    write_region(os.path.join(base, DIM, "region", "r.0.0.mca"), base_slots)
    write_region(os.path.join(base, DIM, "entities", "r.0.0.mca"), base_slots)
    write_region(os.path.join(base, DIM, "poi", "r.0.0.mca"), base_slots)
    write_region(os.path.join(base, DIM, "region", "r.1.1.mca"), [(i, 500 + i) for i in range(10)])
    os.makedirs(base, exist_ok=True)
    with open(os.path.join(base, "level.dat"), "wb") as f:
        f.write(b"base-level-dat")

    # patch: partial subset of r.0.0 with entities/poi, and an updated level.dat
    write_region(os.path.join(patch, DIM, "region", "r.0.0.mca"), patch_slots)
    write_region(os.path.join(patch, DIM, "entities", "r.0.0.mca"), patch_slots)
    write_region(os.path.join(patch, DIM, "poi", "r.0.0.mca"), patch_slots)
    os.makedirs(patch, exist_ok=True)
    with open(os.path.join(patch, "level.dat"), "wb") as f:
        f.write(b"patch-level-dat")

    print("generated fixtures under", ROOT)


if __name__ == "__main__":
    main()
