#!/usr/bin/env python3
"""Minimal zip writer with zipalign-style alignment, used to package the APK.

Android requires resources.arsc to be stored uncompressed and 4-byte aligned
(the framework mmaps it).  Rather than shipping an external zipalign binary we
write the archive ourselves: every stored (uncompressed) entry is padded with a
zip "extra field" so that its data starts on a 4 byte boundary, and native
libraries are page aligned when requested.
"""
import struct
import sys
import zlib


def _local_header(name, crc, csize, usize, method, align, mtime):
    name_b = name.encode("utf-8")
    # data must start at an alignment boundary relative to the file start
    base = 30 + len(name_b)
    pad = (-base) % align
    extra = b"\x00\x00" + b"\x00" * pad  # keep at least a 2 byte extra field
    # extra field length must be even for the padding trick to work cleanly
    if len(extra) % 2:
        extra += b"\x00"
    flags = 0x0008 if method == 0 else 0x0000  # bit 3: data descriptor (unused, but harmless)
    flags = 0x0000
    hdr = struct.pack(
        "<IHHHHHIIIHH",
        0x04034B50,
        20,          # version needed
        flags,
        method,
        mtime,       # dos time
        0x21,        # dos date (1980-01-01)
        crc,
        csize,
        usize,
        len(name_b),
        len(extra),
    )
    return hdr + name_b + extra


def build(entries, out_path, page_align_so=False):
    """entries: list of (name, data, compress_type).  compress_type 0 = stored."""
    out = bytearray()
    central = bytearray()
    for name, data, method in entries:
        align = 4096 if (page_align_so and name.endswith(".so")) else 4
        if method == 8:
            comp = zlib.compressobj(9, zlib.DEFLATED, -15)
            payload = comp.compress(data) + comp.flush()
            if len(payload) >= len(data):
                method, payload = 0, data
            crc = zlib.crc32(data) & 0xFFFFFFFF
        else:
            payload = data
            crc = zlib.crc32(data) & 0xFFFFFFFF
        offset = len(out)
        out += _local_header(name, crc, len(payload), len(data), method, align, 0)
        out += payload
        name_b = name.encode("utf-8")
        central += struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014B50,
            20, 20,
            0x0000,
            method,
            0,
            0x21,
            crc,
            len(payload),
            len(data),
            len(name_b),
            0, 0, 0,
            0,
            0,
            offset,
        )
        central += name_b
    cd_offset = len(out)
    out += central
    out += struct.pack(
        "<IHHHHIIH",
        0x06054B50,
        0, 0,
        len(entries), len(entries),
        len(central),
        cd_offset,
        0,
    )
    with open(out_path, "wb") as fh:
        fh.write(out)
    return out_path


def read_zip(path):
    """Return list of (name, data, compress_type) preserving order."""
    import zipfile
    out = []
    with zipfile.ZipFile(path) as zf:
        for info in zf.infolist():
            out.append((info.filename, zf.read(info.filename), info.compress_type))
    return out


if __name__ == "__main__":
    # usage: zipapk.py out.apk in1.apk [in2.apk ...]  (later files override)
    dest = sys.argv[1]
    merged = {}
    order = []
    for path in sys.argv[2:]:
        for name, data, method in read_zip(path):
            if name not in merged:
                order.append(name)
            merged[name] = (name, data, method)
    build([merged[n] for n in order], dest)
    print("wrote", dest)
