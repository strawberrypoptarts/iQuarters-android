"""
Extract iQuarters' scenes and assets out of the IPA's Unity data.

    python3 tools/iquarters/extract.py DATA_DIR --check
    python3 tools/iquarters/extract.py DATA_DIR --tree level0
    python3 tools/iquarters/extract.py DATA_DIR --dump OUT [--scripts CS_DIR]

DATA_DIR is the bundle's `Data/` folder (unzip the IPA). `--check` decodes
every object whose class has a layout in `classes.py` and reports any that is
not consumed exactly. `--dump` writes textures (PNG), meshes (OBJ), audio (WAV),
shaders, animation clips and one JSON per scene with every component decoded
and every PPtr resolved; with `--scripts` (the decompiled .cs files) the
MonoBehaviours' field values are decoded too, see `scripts.py`.

iQuarters is a commercial game: OUT belongs outside the repository.
"""
import collections
import os
import re
import struct
import sys
from collections import Counter

from unity6 import SerializedFile, CLASS_NAMES
from classes import Reader, DECODERS, PPtr

FILES = ["mainData", "level0", "sharedassets0.assets", "sharedassets1.assets"]


def decode(obj):
    r = Reader(obj.data)
    v = DECODERS[obj.class_id](r)
    if not r.done:
        raise ValueError(f"{obj}: {len(obj.data) - r.p} bytes left over")
    return v


def check_all(data_dir):
    ok, bad = Counter(), Counter()
    for name in FILES:
        f = SerializedFile(f"{data_dir}/{name}")
        for o in f.objects.values():
            if o.class_id not in DECODERS:
                continue
            try:
                decode(o); ok[o.class_name] += 1
            except Exception as e:
                if bad[o.class_name] < 3:
                    print("  FAIL", o, e)
                bad[o.class_name] += 1
    for k in sorted(set(ok) | set(bad)):
        print(f"{k:16s} ok {ok[k]:4d}  bad {bad[k]:4d}")
    return not bad


if __name__ == "__main__":
    data_dir = sys.argv[1]
    if "--check" in sys.argv:
        sys.exit(0 if check_all(data_dir) else 1)


class Bundle:
    """All four files, with PPtrs resolved across them by external name."""

    def __init__(self, data_dir):
        self.files = {n: SerializedFile(f"{data_dir}/{n}") for n in FILES}
        self.cache = {}

    def resolve(self, src, pptr):
        file_id, path_id = pptr
        if path_id == 0:
            return None
        f = src if file_id == 0 else self.files.get(src.externals[file_id - 1][0])
        if f is None:
            return None                      # unity default resources, library cache
        return f.objects.get(path_id)

    def get(self, obj):
        key = (obj.file.name, obj.path_id)
        if key not in self.cache:
            self.cache[key] = decode(obj)
        return self.cache[key]

    def deref(self, src, pptr):
        o = self.resolve(src, pptr)
        return (o, self.get(o)) if o is not None and o.class_id in DECODERS else (o, None)


def component_summary(b, f, cid, co):
    o, d = b.deref(f, co)
    if d is None:
        return CLASS_NAMES.get(cid, cid)
    n = CLASS_NAMES.get(cid, cid)
    if cid == 33:
        m = b.deref(o.file, d["mesh"])[1]
        return f"MeshFilter({m['name'] if m else '?'})"
    if cid == 114:
        s = b.deref(o.file, d["script"])[1]
        return f"{s['class_name'] if s else '?'}"
    if cid == 54:
        return f"Rigidbody(m={d['mass']:g} flags={d['flags']})"
    if cid in (65, 135, 136, 64):
        mat = b.deref(o.file, d["material"])[1]
        return f"{n}({mat['name'] if mat else '-'}{' trigger' if d['is_trigger'] else ''})"
    return n


def print_tree(b, fname):
    f = b.files[fname]
    gos = {o.path_id: b.get(o) for o in f.by_class(1)}
    trs = {o.path_id: b.get(o) for o in f.by_class(4)}
    tr_of_go = {}
    for pid, go in gos.items():
        for cid, co in go["components"]:
            if cid == 4:
                tr_of_go[pid] = co[1]
    go_of_tr = {v: k for k, v in tr_of_go.items()}

    def walk(tr_id, depth):
        t = trs[tr_id]
        go = gos[go_of_tr[tr_id]]
        comps = [component_summary(b, f, cid, co) for cid, co in go["components"] if cid != 4]
        pos = ",".join(f"{v:.2f}" for v in t["position"])
        print("  " * depth + f"{go['name']} [{pos}] " + " ".join(comps))
        for c in t["children"]:
            walk(c[1], depth + 1)

    for tid, t in trs.items():
        if t["father"][1] == 0:
            walk(tid, 0)


if __name__ == "__main__" and "--tree" in sys.argv:
    print_tree(Bundle(sys.argv[1]), sys.argv[sys.argv.index("--tree") + 1])


# -- dump -------------------------------------------------------------------

def unstrip(strip):
    """Triangles of a Unity strip, alternating winding, degenerates dropped."""
    tris = []
    for i in range(len(strip) - 2):
        a, b, c = strip[i], strip[i + 1], strip[i + 2]
        if a == b or b == c or a == c:
            continue
        tris.append((a, b, c) if i % 2 == 0 else (b, a, c))
    return tris


def mesh_triangles(m):
    subs = []
    for s in m["submeshes"]:
        first = s["first_byte"] // (2 if m["use16bit"] else 4)
        tris = unstrip(m["indices"][first:first + s["index_count"]])
        if len(tris) != s["triangle_count"]:
            raise ValueError(f"{m['name']}: {len(tris)} triangles unstripped, {s['triangle_count']} stored")
        subs.append(tris)
    return subs


def write_obj(path, m):
    with open(path, "w") as f:
        f.write(f"# {m['name']}\n")
        for v in m["vertices"]:
            f.write("v %g %g %g\n" % tuple(v))
        for uv in m["uv"]:
            f.write("vt %g %g\n" % tuple(uv))
        for t in m["tangent_space"]:
            f.write("vn %g %g %g\n" % tuple(t["normal"]))
        has_uv, has_n = bool(m["uv"]), bool(m["tangent_space"])
        for si, tris in enumerate(mesh_triangles(m)):
            f.write(f"g submesh{si}\n")
            for tri in tris:
                refs = []
                for i in tri:
                    i += 1
                    refs.append(f"{i}/{i if has_uv else ''}/{i if has_n else ''}".rstrip("/")
                                if has_uv or has_n else str(i))
                f.write("f " + " ".join(refs) + "\n")


def write_wav(path, clip):
    data = clip["data"]
    with open(path, "wb") as f:
        f.write(b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVE")
        f.write(b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, clip["frequency"],
                                      clip["frequency"] * 2, 2, 16))
        f.write(b"data" + struct.pack("<I", len(data)) + data)


def safe(name):
    return re.sub(r"[^\w.-]+", "_", name) or "unnamed"


def dump(b, out, scripts_dir):
    from scripts import ScriptSet
    import json
    import textures
    ss = ScriptSet(scripts_dir) if scripts_dir else None
    for sub in ("textures", "meshes", "audio", "shaders", "scenes", "animations"):
        os.makedirs(os.path.join(out, sub), exist_ok=True)

    def ref(src, pptr):
        o = b.resolve(src, pptr)
        if o is None:
            return None if pptr[1] == 0 else {"external": src.externals[pptr[0] - 1][0] if pptr[0] else None,
                                                "path_id": pptr[1]}
        d = b.get(o) if o.class_id in DECODERS else {}
        name = d.get("name") if isinstance(d, dict) else None
        if o.class_id in (4, 33, 23, 54, 65, 135, 136, 64, 114) and not name:
            go = b.resolve(o.file, d["game_object"])
            name = b.get(go)["name"] if go else None
        return {"file": o.file.name, "path_id": o.path_id,
                "class": CLASS_NAMES.get(o.class_id, o.class_id), "name": name}

    def jsonable(src, v, key=None):
        if isinstance(v, (bytes, bytearray)):
            return v.hex() if len(v) <= 64 else f"<{len(v)} bytes>"
        if isinstance(v, dict):
            return {k: jsonable(src, x, k) for k, x in v.items()}
        if isinstance(v, (list, tuple)):
            if isinstance(v, PPtr):
                return ref(src, v)
            return [jsonable(src, x, key) for x in v]
        return v

    counts = collections.Counter()
    for fname, f in b.files.items():
        for o in f.objects.values():
            if o.class_id not in DECODERS:
                continue
            d = b.get(o)
            base = f"{safe(d.get('name', ''))}_{safe(fname)}_{o.path_id}"
            if o.class_id == 28:
                textures.png(os.path.join(out, "textures", base + ".png"),
                             d["width"], d["height"], textures.decode(d))
            elif o.class_id == 43:
                write_obj(os.path.join(out, "meshes", base + ".obj"), d)
            elif o.class_id == 83:
                write_wav(os.path.join(out, "audio", base + ".wav"), d)
            elif o.class_id == 74:
                with open(os.path.join(out, "animations", base + ".json"), "w") as af:
                    json.dump(d, af, indent=1)
            elif o.class_id == 48:
                with open(os.path.join(out, "shaders", base + ".shader"), "w") as sf:
                    sf.write(d["script"])
            else:
                continue
            counts[CLASS_NAMES[o.class_id]] += 1

        managers = {CLASS_NAMES.get(c, "TimeManager" if c == 5 else c): jsonable(f, b.get(o))
                    for c in (55, 5) for o in f.by_class(c)}
        if managers:
            with open(os.path.join(out, "scenes", safe(fname) + ".settings.json"), "w") as jf:
                json.dump(managers, jf, indent=1)

        gos = f.by_class(1)
        if not gos:
            continue
        scene = []
        for go_obj in gos:
            go = b.get(go_obj)
            entry = {"path_id": go_obj.path_id, "name": go["name"], "layer": go["layer"],
                     "tag": go["tag"], "active": go["active"], "components": []}
            for cid, co in go["components"]:
                o = b.resolve(f, co)
                comp = {"class": CLASS_NAMES.get(cid, cid), "path_id": co[1]}
                if o is not None and cid in DECODERS:
                    d = dict(b.get(o))
                    d.pop("game_object", None)
                    if cid == 114:
                        s = b.deref(f, d["script"])[1]
                        d.pop("script")
                        comp["script"] = s["class_name"] if s else None
                        if ss and s:
                            d["fields"] = ss.decode_fields(s["class_name"], d["fields"])
                    comp.update(jsonable(f, d))
                entry["components"].append(comp)
            scene.append(entry)
        with open(os.path.join(out, "scenes", safe(fname) + ".json"), "w") as jf:
            json.dump(scene, jf, indent=1)
        counts["scene"] += 1
    return counts



if __name__ == "__main__" and "--dump" in sys.argv:
    a = sys.argv
    sd = a[a.index("--scripts") + 1] if "--scripts" in a else None
    print(dump(Bundle(a[1]), a[a.index("--dump") + 1], sd))
