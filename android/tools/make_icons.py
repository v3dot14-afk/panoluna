#!/usr/bin/env python3
"""Создаёт значки запуска в формате PNG без внешних зависимостей.

Рисует янтарный круг с меридианами сферы — ту же пиктограмму, что используется для
панорамы. Для сглаживания краёв изображение рисуется с четырёхкратным увеличением.
"""
import os
import struct
import sys
import zlib

BG = (18, 20, 23, 255)
AMBER = (255, 179, 0, 255)
DARK = (18, 20, 23, 255)


def blend(pixel, color, alpha):
    if alpha <= 0:
        return pixel
    if alpha > 1:
        alpha = 1
    return (
        int(pixel[0] + (color[0] - pixel[0]) * alpha),
        int(pixel[1] + (color[1] - pixel[1]) * alpha),
        int(pixel[2] + (color[2] - pixel[2]) * alpha),
        255,
    )


def render(size):
    ss = 4
    big = size * ss
    pixels = [[BG for _ in range(big)] for _ in range(big)]
    center = (big - 1) / 2.0
    outer = big * 0.44
    inner = big * 0.30
    for y in range(big):
        for x in range(big):
            dx = x - center
            dy = y - center
            dist = (dx * dx + dy * dy) ** 0.5
            alpha = 0.0
            if dist <= outer:
                alpha = 1.0
            elif dist <= outer + ss:
                alpha = (outer + ss - dist) / ss
            if alpha > 0:
                pixels[y][x] = blend(pixels[y][x], AMBER, alpha)
    # Меридианы: вертикальный эллипс и две горизонтальные линии тёмного цвета.
    for y in range(big):
        for x in range(big):
            dx = x - center
            dy = y - center
            dist = (dx * dx + dy * dy) ** 0.5
            if dist > outer:
                continue
            u = dx / outer
            v = dy / outer
            on_ellipse = abs((u * u) + (v * v) - 0.42) < 0.012
            on_equator = abs(v) < 0.012
            on_meridian = abs(u) < 0.012
            if on_ellipse or on_equator or on_meridian:
                pixels[y][x] = blend(pixels[y][x], DARK, 0.92)
    # Уменьшение изображения с усреднением.
    out = [[(0, 0, 0, 255) for _ in range(size)] for _ in range(size)]
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0
            for sy in range(ss):
                for sx in range(ss):
                    p = pixels[y * ss + sy][x * ss + sx]
                    r += p[0]
                    g += p[1]
                    b += p[2]
                    a += p[3]
            n = ss * ss
            out[y][x] = (r // n, g // n, b // n, a // n)
    return out


def png(pixels, size):
    raw = bytearray()
    for y in range(size):
        raw.append(0)
        for x in range(size):
            raw.extend(pixels[y][x][:3])
    compressed = zlib.compress(bytes(raw), 9)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", header)
            + chunk(b"IDAT", compressed)
            + chunk(b"IEND", b""))


def main():
    root = sys.argv[1]
    densities = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for density, size in densities.items():
        folder = os.path.join(root, "app", "res", "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, "ic_launcher.png"), "wb") as fh:
            fh.write(png(render(size), size))
        print("mipmap-" + density + "/ic_launcher.png", size)


if __name__ == "__main__":
    main()
