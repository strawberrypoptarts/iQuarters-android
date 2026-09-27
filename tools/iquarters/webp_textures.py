#!/usr/bin/env python3
"""
Rewrite a baked `scene.pack`'s textures as WebP, to keep the APK small.

    webp_textures.py IN.pack OUT.pack [QUALITY]

Each texture's `zlen, zlib(RGBA8 rows, bottom row first)` becomes `len, WebP` of the
same image the right way up. It is lossy (default quality 90) with lossless alpha and
`exact`, so RGB under transparent texels survives for bilinear filtering; a texture
whose WebP would not be smaller stays deflated. `IqPack.Texture.isWebp` tells the two
apart by the RIFF magic, and `IqRenderer` decodes them. Everything after the texture
table is copied unchanged. Needs Pillow.
"""
import io
import struct
import sys
import zlib

from PIL import Image


def main(src, dst, quality=90):
    d = open(src, "rb").read()
    assert d[:4] == b"IQP2", "not an iQuarters v2 pack"
    out = bytearray(d[:12])
    n = struct.unpack_from("<I", d, 8)[0]
    p = 12
    before = after = 0
    for _ in range(n):
        l = struct.unpack_from("<H", d, p)[0]
        head = d[p:p + 2 + l + 8]          # str name, u16 w, u16 h, u8 wrap, filter, mipmap, 0
        w, h = struct.unpack_from("<HH", d, p + 2 + l)
        p += 2 + l + 8
        z = struct.unpack_from("<I", d, p)[0]
        blob = d[p + 4:p + 4 + z]
        p += 4 + z
        if blob[:4] != b"RIFF":
            rgba = zlib.decompress(blob)
            im = Image.frombytes("RGBA", (w, h), rgba).transpose(Image.FLIP_TOP_BOTTOM)
            b = io.BytesIO()
            im.save(b, "WEBP", quality=quality, alpha_quality=100, exact=True, method=6)
            if len(b.getvalue()) < len(blob):
                blob = b.getvalue()
        before += z
        after += len(blob)
        out += head + struct.pack("<I", len(blob)) + blob
    out += d[p:]
    open(dst, "wb").write(out)
    print(f"{n} textures: {before} -> {after} bytes; pack {len(d)} -> {len(out)}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], *(int(a) for a in sys.argv[3:4]))
