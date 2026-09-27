"""Texture2D pixel decoding and a stdlib PNG writer.

Unity 2.x iPhone texture formats seen in iQuarters: 1 Alpha8, 2 ARGB4444, 3 RGB24,
5 ARGB32, 32 PVRTC_RGB4, 33 PVRTC_RGBA4 (30/31, the 2bpp ones, are not used and not
decoded). Rows are stored bottom-up, as OpenGL uploads them; `decode` returns RGBA rows
top-down.

PVRTC 4bpp follows Imagination's reference decompressor: 64-bit blocks in Morton order,
each a 32-bit modulation word then a 32-bit colour word holding two low-precision colours,
A and B. Each colour is bilinearly upscaled from the four nearest block centres, and each
pixel's 2-bit modulation value blends A towards B.
"""

import struct
import zlib


def png(path, w, h, rows):
    """Write RGBA8 `rows` (a list of h bytes objects, top first) as a PNG."""
    raw = b"".join(b"\0" + r for r in rows)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 6)))
        f.write(chunk(b"IEND", b""))


def _twiddle(bw, bh, x, y):
    lo, hi = (bh, x) if bh < bw else (bw, y)
    out, bit, dst, shift = 0, 1, 1, 0
    while bit < lo:
        if y & bit:
            out |= dst
        if x & bit:
            out |= dst << 1
        bit <<= 1
        dst <<= 2
        shift += 1
    return out | ((hi >> shift) << (2 * shift))


def _colour_b(c):
    if c & 0x80000000:
        return ((c >> 26) & 31, (c >> 21) & 31, (c >> 16) & 31, 15)
    r, g, b = (c >> 24) & 15, (c >> 20) & 15, (c >> 16) & 15
    return ((r << 1) | (r >> 3), (g << 1) | (g >> 3), (b << 1) | (b >> 3), ((c >> 28) & 7) << 1)


def _colour_a(c):
    if c & 0x8000:
        b = (c >> 1) & 15
        return ((c >> 10) & 31, (c >> 5) & 31, (b << 1) | (b >> 3), 15)
    r, g, b = (c >> 8) & 15, (c >> 4) & 15, (c >> 1) & 7
    return ((r << 1) | (r >> 3), (g << 1) | (g >> 3), (b << 2) | (b >> 1), ((c >> 12) & 7) << 1)


def pvrtc4(data, w, h):
    bw, bh = max(w // 4, 2), max(h // 4, 2)
    mod = [[0] * bw for _ in range(bh)]
    col = [[0] * bw for _ in range(bh)]
    ca = [[None] * bw for _ in range(bh)]
    cb = [[None] * bw for _ in range(bh)]
    for by in range(bh):
        for bx in range(bw):
            m, c = struct.unpack_from("<II", data, 8 * _twiddle(bw, bh, bx, by))
            mod[by][bx], col[by][bx] = m, c
            ca[by][bx], cb[by][bx] = _colour_a(c), _colour_b(c)

    rows = []
    for py in range(h):
        row = bytearray(4 * w)
        v = py - 2
        y0 = (v >> 2) % bh
        y1 = (y0 + 1) % bh
        fy = v & 3
        for px in range(w):
            u = px - 2
            x0 = (u >> 2) % bw
            x1 = (x0 + 1) % bw
            fx = u & 3
            w00, w10, w01, w11 = (4 - fx) * (4 - fy), fx * (4 - fy), (4 - fx) * fy, fx * fy
            p = [0] * 8
            for cs, off in ((ca, 0), (cb, 4)):
                a, b, c, d = cs[y0][x0], cs[y0][x1], cs[y1][x0], cs[y1][x1]
                for k in range(4):
                    t = a[k] * w00 + b[k] * w10 + c[k] * w01 + d[k] * w11   # x16
                    if k < 3:
                        t >>= 1
                        t += t >> 5
                    else:
                        t += t >> 4
                    p[off + k] = t
            bx, by = px >> 2, py >> 2
            m = (mod[by][bx] >> (2 * ((py & 3) * 4 + (px & 3)))) & 3
            punch = False
            if col[by][bx] & 1:
                m, punch = (0, 4, 4, 8)[m], m == 2
            else:
                m = (0, 3, 5, 8)[m]
            o = 4 * px
            for k in range(4):
                row[o + k] = (p[k] * (8 - m) + p[4 + k] * m) // 8
            if punch:
                row[o + 3] = 0
        rows.append(bytes(row))
    return rows


def decode(tex):
    w, h, fmt, img = tex["width"], tex["height"], tex["format"], tex["image"]
    if fmt in (32, 33):
        rows = pvrtc4(img, w, h)
        if fmt == 32:
            rows = [bytes(b if i % 4 != 3 else 255 for i, b in enumerate(r)) for r in rows]
    else:
        rows = []
        for y in range(h):
            row = bytearray(4 * w)
            for x in range(w):
                i = y * w + x
                if fmt == 5:
                    a, r, g, b = img[4 * i:4 * i + 4]
                elif fmt == 3:
                    (r, g, b), a = img[3 * i:3 * i + 3], 255
                elif fmt == 1:
                    r = g = b = 255
                    a = img[i]
                elif fmt == 2:
                    v, = struct.unpack_from("<H", img, 2 * i)
                    a, r, g, b = [((v >> s) & 15) * 17 for s in (12, 8, 4, 0)]
                else:
                    raise NotImplementedError(f"texture format {fmt}")
                row[4 * x:4 * x + 4] = bytes((r, g, b, a))
            rows.append(bytes(row))
    return rows[::-1]
