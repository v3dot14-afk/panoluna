#!/usr/bin/env python3
"""Минимальный архиватор ZIP с выравниванием по типу zipalign для упаковки APK.

Android требует хранить resources.arsc без сжатия и выравнивать начало данных по границе 4 байт
(система отображает файл в память). Вместо включения внешнего zipalign скрипт формирует архив
самостоятельно: перед каждым несжатым элементом добавляется
дополнительное поле ZIP, чтобы данные начинались на границе 4 байт; нативные
библиотеки при необходимости выравниваются по размеру страницы памяти.
"""
import struct
import sys
import zlib


def _local_header(name, crc, csize, usize, method, align, mtime, abs_offset):
    name_b = name.encode("utf-8")
    # Заполнение хранится в дополнительном поле и должно выровнять смещение начала данных
    # (abs_offset + 30 + name + extra) по требуемой границе.
    base = abs_offset + 30 + len(name_b)
    pad = (-base) % align
    extra = b"\x00" * pad
    flags = 0x0000
    hdr = struct.pack(
        "<IHHHHHIIIHH",
        0x04034B50,
        20,          # требуемая версия формата
        flags,
        method,
        mtime,       # время в формате DOS
        0x21,        # дата в формате DOS (1980-01-01)
        crc,
        csize,
        usize,
        len(name_b),
        len(extra),
    )
    return hdr + name_b + extra


def build(entries, out_path, page_align_so=False):
    """entries: список (имя, данные, тип сжатия). compress_type 0 означает хранение без сжатия."""
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
        name_b = name.encode("utf-8")
        base = offset + 30 + len(name_b)
        pad = (-base) % align
        extra = b"\x00" * pad
        out += _local_header(name, crc, len(payload), len(data), method, align, 0, offset)
        out += payload
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
            len(extra),
            0, 0,
            0,
            0,
            offset,
        )
        central += name_b
        central += extra
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
    """Возвращает упорядоченный список (имя, данные, тип сжатия)."""
    import zipfile
    out = []
    with zipfile.ZipFile(path) as zf:
        for info in zf.infolist():
            out.append((info.filename, zf.read(info.filename), info.compress_type))
    return out


if __name__ == "__main__":
    # Использование: zipapk.py out.apk in1.apk [in2.apk ...] (последующие файлы заменяют предыдущие).
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
