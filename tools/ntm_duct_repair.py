#!/usr/bin/env python3
"""
NTM NEXT duct repair: rebuild duct connections without losing their fluid types.

NTM keeps every duct's fluid type in fluid_pipe_graph*.dat files, separate from the chunks. When the two get out of
step (for example after a server crash), ducts stop connecting properly. Deleting those files fixes the connections
but leaves every duct with no fluid type.

This script copies each duct's fluid type from the graph files into the chunk it sits in, under the "hbm_duct_fluids"
record that NTM itself reads when a chunk loads, and then moves the graph files aside. On the next start NTM rebuilds
every duct from scratch and gives it back its fluid type.

Usage (server stopped, on a backup copy first):
    python3 ntm_duct_repair.py <world folder>            # dry run: only reports what it would do
    python3 ntm_duct_repair.py <world folder> --apply    # writes the changes

Only the Python standard library is used. Region files must use the default compression (zlib); LZ4 is not supported.
"""

import argparse
import gzip
import io
import os
import struct
import sys
import time
import zlib

DUCT_KEY = "hbm_duct_fluids"
GRAPH_PREFIX = "fluid_pipe_graph"

# --------------------------------------------------------------------------- NBT

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class Tag:
    """A typed NBT value. Compounds hold dicts of Tag, lists hold (element_type, [Tag])."""

    __slots__ = ("type", "value", "raw_names")

    def __init__(self, type_, value):
        self.type = type_
        self.value = value
        self.raw_names = None  # compound only: original encoded key bytes, written back unchanged


def text(tag):
    """A string tag's text. String tags keep their original bytes so unchanged ones are written back exactly."""
    v = tag.value
    return _mutf8_decode(v) if isinstance(v, (bytes, bytearray)) else v


def _mutf8_decode(b):
    out = []
    i = 0
    n = len(b)
    while i < n:
        c = b[i]
        if c < 0x80:
            out.append(c)
            i += 1
        elif c & 0xE0 == 0xC0:
            out.append(((c & 0x1F) << 6) | (b[i + 1] & 0x3F))
            i += 2
        elif c & 0xF0 == 0xE0:
            out.append(((c & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F))
            i += 3
        else:  # standard 4-byte UTF-8, not produced by Java but tolerated
            cp = ((c & 0x07) << 18) | ((b[i + 1] & 0x3F) << 12) | ((b[i + 2] & 0x3F) << 6) | (b[i + 3] & 0x3F)
            cp -= 0x10000
            out += [0xD800 | (cp >> 10), 0xDC00 | (cp & 0x3FF)]
            i += 4
    # Combine surrogate pairs the way Java would.
    s = "".join(chr(u) for u in out)
    return s.encode("utf-16", "surrogatepass").decode("utf-16")


def _mutf8_encode(s):
    units = s.encode("utf-16-be", "surrogatepass")
    out = bytearray()
    for i in range(0, len(units), 2):
        u = (units[i] << 8) | units[i + 1]
        if 0 < u < 0x80:
            out.append(u)
        elif u < 0x800:
            out += bytes((0xC0 | (u >> 6), 0x80 | (u & 0x3F)))
        else:
            out += bytes((0xE0 | (u >> 12), 0x80 | ((u >> 6) & 0x3F), 0x80 | (u & 0x3F)))
    return bytes(out)


class _Reader:
    def __init__(self, data):
        self.d = data
        self.p = 0

    def take(self, n):
        b = self.d[self.p:self.p + n]
        if len(b) != n:
            raise ValueError("NBT data ended early")
        self.p += n
        return b

    def unpack(self, fmt):
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.take(size))[0]

    def raw_string(self):
        return self.take(self.unpack(">H"))

    def payload(self, t):
        if t == COMPOUND:
            d, raw = self._payload(t)
            return _Compound(d, raw)
        return self._payload(t)

    def _payload(self, t):
        if t == BYTE:
            return self.unpack(">b")
        if t == SHORT:
            return self.unpack(">h")
        if t == INT:
            return self.unpack(">i")
        if t == LONG:
            return self.unpack(">q")
        if t == FLOAT:
            return self.unpack(">f")
        if t == DOUBLE:
            return self.unpack(">d")
        if t == BYTE_ARRAY:
            return self.take(self.unpack(">i"))
        if t == STRING:
            return self.raw_string()
        if t == LIST:
            et = self.unpack(">b")
            n = self.unpack(">i")
            return (et, [_tag(et, self.payload(et)) for _ in range(n)])
        if t == COMPOUND:
            d = {}
            raw = {}
            while True:
                ct = self.unpack(">b")
                if ct == END:
                    return d, raw
                name_bytes = self.raw_string()
                name = _mutf8_decode(name_bytes)
                raw[name] = name_bytes
                d[name] = _tag(ct, self.payload(ct))
        if t == INT_ARRAY:
            n = self.unpack(">i")
            return list(struct.unpack(">%di" % n, self.take(4 * n)))
        if t == LONG_ARRAY:
            n = self.unpack(">i")
            return list(struct.unpack(">%dq" % n, self.take(8 * n)))
        raise ValueError("unknown NBT tag type %d" % t)


class _Compound:
    def __init__(self, d, raw):
        self.d = d
        self.raw = raw


def _tag(t, payload):
    if isinstance(payload, _Compound):
        tag = Tag(COMPOUND, payload.d)
        tag.raw_names = payload.raw
        return tag
    return Tag(t, payload)


def read_nbt(data):
    """Returns (root_name_bytes, root_tag)."""
    r = _Reader(data)
    t = r.unpack(">b")
    if t != COMPOUND:
        raise ValueError("NBT root is not a compound")
    name = r.raw_string()
    return name, _tag(COMPOUND, r.payload(COMPOUND))


def _write_payload(out, tag):
    t, v = tag.type, tag.value
    if t == BYTE:
        out.write(struct.pack(">b", v))
    elif t == SHORT:
        out.write(struct.pack(">h", v))
    elif t == INT:
        out.write(struct.pack(">i", v))
    elif t == LONG:
        out.write(struct.pack(">q", v))
    elif t == FLOAT:
        out.write(struct.pack(">f", v))
    elif t == DOUBLE:
        out.write(struct.pack(">d", v))
    elif t == BYTE_ARRAY:
        out.write(struct.pack(">i", len(v)))
        out.write(v)
    elif t == STRING:
        b = v if isinstance(v, (bytes, bytearray)) else _mutf8_encode(v)
        out.write(struct.pack(">H", len(b)))
        out.write(b)
    elif t == LIST:
        et, items = v
        out.write(struct.pack(">bi", et, len(items)))
        for item in items:
            _write_payload(out, item)
    elif t == COMPOUND:
        raw = tag.raw_names or {}
        for name, child in v.items():
            out.write(struct.pack(">b", child.type))
            b = raw.get(name) or _mutf8_encode(name)
            out.write(struct.pack(">H", len(b)))
            out.write(b)
            _write_payload(out, child)
        out.write(b"\x00")
    elif t == INT_ARRAY:
        out.write(struct.pack(">i", len(v)))
        out.write(struct.pack(">%di" % len(v), *v))
    elif t == LONG_ARRAY:
        out.write(struct.pack(">i", len(v)))
        out.write(struct.pack(">%dq" % len(v), *v))
    else:
        raise ValueError("unknown NBT tag type %d" % t)


def write_nbt(name, root):
    out = io.BytesIO()
    out.write(struct.pack(">b", COMPOUND))
    b = name if isinstance(name, (bytes, bytearray)) else _mutf8_encode(name)
    out.write(struct.pack(">H", len(b)))
    out.write(b)
    _write_payload(out, root)
    return out.getvalue()


# --------------------------------------------------------------------------- block positions

def unpack_block_pos(v):
    """Minecraft BlockPos.asLong: x in bits 38-63, z in bits 12-37, y in bits 0-11, all signed."""
    x = _signed((v >> 38) & ((1 << 26) - 1), 26)
    z = _signed((v >> 12) & ((1 << 26) - 1), 26)
    y = _signed(v & ((1 << 12) - 1), 12)
    return x, y, z


def _signed(v, bits):
    return v - (1 << bits) if v & (1 << (bits - 1)) else v


# --------------------------------------------------------------------------- graph files

def find_graph_files(world):
    found = []
    for root, _dirs, files in os.walk(world):
        for f in files:
            if f.startswith(GRAPH_PREFIX) and f.endswith(".dat"):
                found.append(os.path.join(root, f))
    return sorted(found)


def dimension_root(graph_file, world):
    """The folder holding this dimension's 'region' folder: walk up from the graph file."""
    d = os.path.dirname(graph_file)
    world = os.path.abspath(world)
    while True:
        if os.path.isdir(os.path.join(d, "region")):
            return d
        if os.path.abspath(d) == world or os.path.dirname(d) == d:
            return None
        d = os.path.dirname(d)


def _find_graph_body(tag):
    """The compound that holds 'data' (fluid table), 'nodes' and 'segs', wherever the saved-data wrapper put it."""
    if tag.type != COMPOUND:
        return None
    v = tag.value
    if "nodes" in v and "data" in v and v["nodes"].type == LIST:
        return v
    for child in v.values():
        if child.type == COMPOUND:
            hit = _find_graph_body(child)
            if hit is not None:
                return hit
    return None


def read_graph(path):
    """Returns {block_pos_long: fluid_id} for every duct cell in a graph file."""
    with open(path, "rb") as fh:
        raw = fh.read()
    try:
        raw = gzip.decompress(raw)
    except OSError:
        pass  # already uncompressed
    _name, root = read_nbt(raw)
    body = _find_graph_body(root)
    if body is None:
        raise ValueError("no duct graph found inside %s" % path)

    table = []
    for entry in body["data"].value[1]:
        fluid = entry.value.get("fluid")
        table.append(text(fluid) if fluid is not None and fluid.type == STRING else None)

    result = {}

    def put(pos, idx):
        if 0 <= idx < len(table):
            fluid = table[idx]
            if fluid and fluid != "minecraft:empty":
                result[pos] = fluid

    for node in body["nodes"].value[1]:
        n = node.value
        if "owned" in n and n["owned"].value:
            continue  # a machine's port, not a duct
        put(n["p"].value, n["d"].value)
    segs = body.get("segs")
    if segs is not None:
        for seg in segs.value[1]:
            s = seg.value
            for cell in s["cells"].value:
                put(cell, s["d"].value)
    return result


# --------------------------------------------------------------------------- region files

SECTOR = 4096


def read_region(path):
    """Returns (timestamps[1024], chunks{index: (compression, payload_bytes)}, external_indices)."""
    with open(path, "rb") as fh:
        data = fh.read()
    if len(data) < 2 * SECTOR:
        return [0] * 1024, {}, set()
    offsets = struct.unpack(">1024I", data[:SECTOR])
    stamps = list(struct.unpack(">1024I", data[SECTOR:2 * SECTOR]))
    chunks = {}
    external = set()
    for i, off in enumerate(offsets):
        if off == 0:
            continue
        start = (off >> 8) * SECTOR
        length = struct.unpack(">I", data[start:start + 4])[0]
        comp = data[start + 4]
        if comp & 0x80:
            external.add(i)
        chunks[i] = (comp, data[start + 5:start + 4 + length])
    return stamps, chunks, external


def decompress_chunk(comp, payload):
    kind = comp & 0x7F
    if kind == 1:
        return gzip.decompress(payload)
    if kind == 2:
        return zlib.decompress(payload)
    if kind == 3:
        return payload
    raise ValueError("unsupported chunk compression %d (set region-file-compression=deflate)" % kind)


def compress_chunk(comp, raw):
    kind = comp & 0x7F
    if kind == 1:
        return gzip.compress(raw)
    if kind == 2:
        return zlib.compress(raw)
    return raw


def write_region(path, stamps, chunks):
    header = bytearray(2 * SECTOR)
    body = bytearray()
    sector = 2
    for i in range(1024):
        if i not in chunks:
            continue
        comp, payload = chunks[i]
        blob = struct.pack(">IB", len(payload) + 1, comp) + payload
        pad = (-len(blob)) % SECTOR
        blob += b"\x00" * pad
        count = len(blob) // SECTOR
        if count > 255:
            raise ValueError("chunk %d in %s is too large to store inline" % (i, path))
        struct.pack_into(">I", header, i * 4, (sector << 8) | count)
        body += blob
        sector += count
    struct.pack_into(">1024I", header, SECTOR, *stamps)
    tmp = path + ".tmp"
    with open(tmp, "wb") as fh:
        fh.write(header)
        fh.write(body)
    os.replace(tmp, path)


# --------------------------------------------------------------------------- main

def plan(world):
    """Groups every duct's fluid by dimension root, region file and chunk."""
    graphs = find_graph_files(world)
    by_dim = {}
    for g in graphs:
        root = dimension_root(g, world)
        if root is None:
            print("  ! no region folder found for %s, skipping it" % g)
            continue
        cells = read_graph(g)
        print("  %s: %d duct cells with a fluid type" % (os.path.relpath(g, world), len(cells)))
        by_dim.setdefault(root, {}).update(cells)
    return graphs, by_dim


def apply_dimension(root, cells, write):
    """Adds the hbm_duct_fluids record to each chunk. Returns (chunks_updated, cells_written, missing_chunks)."""
    per_region = {}
    for pos, fluid in cells.items():
        x, _y, z = unpack_block_pos(pos)
        cx, cz = x >> 4, z >> 4
        region = (cx >> 5, cz >> 5)
        index = (cx & 31) + (cz & 31) * 32
        per_region.setdefault(region, {}).setdefault(index, {})[pos] = fluid

    updated = written = missing = 0
    for (rx, rz), chunk_map in sorted(per_region.items()):
        path = os.path.join(root, "region", "r.%d.%d.mca" % (rx, rz))
        if not os.path.isfile(path):
            missing += len(chunk_map)
            continue
        stamps, chunks, external = read_region(path)
        changed = False
        for index, entries in chunk_map.items():
            if index not in chunks or index in external:
                missing += 1
                continue
            comp, payload = chunks[index]
            name, tag = read_nbt(decompress_chunk(comp, payload))
            existing = {}
            old = tag.value.get(DUCT_KEY)
            if old is not None and old.type == COMPOUND:
                ps = old.value.get("pos")
                fs = old.value.get("fluid")
                if ps is not None and fs is not None:
                    for p, f in zip(ps.value, fs.value[1]):
                        existing[p] = text(f)
            existing.update(entries)
            positions = list(existing.keys())
            tag.value[DUCT_KEY] = Tag(COMPOUND, {
                "pos": Tag(LONG_ARRAY, positions),
                "fluid": Tag(LIST, (STRING, [Tag(STRING, existing[p]) for p in positions])),
            })
            chunks[index] = (comp, compress_chunk(comp, write_nbt(name, tag)))
            stamps[index] = int(time.time())
            changed = True
            updated += 1
            written += len(entries)
        if changed and write:
            write_region(path, stamps, chunks)
    return updated, written, missing


def main():
    ap = argparse.ArgumentParser(description="Rebuild NTM NEXT duct connections while keeping each duct's fluid type.")
    ap.add_argument("world", help="the world folder (the one with level.dat)")
    ap.add_argument("--apply", action="store_true", help="write the changes (default is a dry run)")
    args = ap.parse_args()

    world = os.path.abspath(args.world)
    if not os.path.isfile(os.path.join(world, "level.dat")):
        sys.exit("No level.dat in %s. Point this at the world folder." % world)
    if args.apply:
        print("Make sure the server is stopped and you have a backup of this world.")
    print("Reading NTM duct data in %s" % world)
    graphs, by_dim = plan(world)
    if not graphs:
        sys.exit("No %s*.dat files found. Nothing to do." % GRAPH_PREFIX)

    total_chunks = total_cells = total_missing = 0
    for root, cells in by_dim.items():
        updated, written, missing = apply_dimension(root, cells, args.apply)
        print("  %s: %d chunks, %d ducts%s" % (
            os.path.relpath(root, world) or ".", updated, written,
            ", %d chunks not found (skipped)" % missing if missing else ""))
        total_chunks += updated
        total_cells += written
        total_missing += missing

    if not args.apply:
        print("\nDry run: %d ducts in %d chunks would be recorded. Run again with --apply to write." % (total_cells, total_chunks))
        return

    stamp = time.strftime("%Y%m%d-%H%M%S")
    for g in graphs:
        os.replace(g, "%s.before-repair-%s" % (g, stamp))
    print("\nDone: %d ducts recorded in %d chunks. The old duct data files were renamed to *.before-repair-%s." % (
        total_cells, total_chunks, stamp))
    print("Start the server. Each duct gets its fluid back the first time its chunk loads.")


if __name__ == "__main__":
    main()
