"""
Bake iQuarters' two scenes into the pack the native port loads.

    python3 tools/iquarters/bake.py DATA_DIR CS_DIR OUT_DIR [--list]

DATA_DIR is the IPA's `Data/` folder, CS_DIR the decompiled scripts (only their field
declarations are read, to decode MonoBehaviour values; see `scripts.py`). OUT_DIR is
normally `app/src/main/assets/iquarters/`: it gets `scene.pack` and `audio/*.wav`,
which, like the `.ipa` beside them, are the owner's own copy of a commercial game and
not for redistribution.

Both scenes go in whole: `mainData` (the front end, which the scripts call "frontend")
and `level0` (the game). Every GameObject, with its transform, renderer, colliders,
rigidbody, light, camera, Animation, AudioSource and every MonoBehaviour's serialized
fields; every mesh, material, texture, physic material and animation clip any of them
uses; and every audio clip, as a WAV beside the pack.

The format is little-endian and read by `IqPack.kt`:

    "IQP2" u32 version
    u32 n textures:  str name, u16 w, u16 h, u8 wrap, u8 filter, u8 mipmap, u8 0,
                     u32 zlen, zlib(RGBA8 rows, bottom row first, as GL uploads them)
    u32 n meshes:    str name, u32 nverts, u8 attrs (1 normal, 2 uv, 4 colour, 8 uv1),
                     f32 xyz[n], [f32 nxyz[n]], [f32 uv[n]], [u8 rgba[n]], [f32 uv1[n]],
                     u16 nsub, per submesh u32 nidx, u16 idx[nidx] (triangle list)
    u32 n physmats:  str name, f32 dynamic, f32 static, f32 bounce, u8 fcomb, u8 bcomb
    u32 n materials: str name, str shader, f32 color[4] spec[4] emission[4] tint[4],
                     f32 shininess, i32 mainTex, f32 mainST[4], i32 detailTex, f32 detailST[4]
    u32 n clips:     str name, f32 sampleRate, u8 wrap, u16 n curves, each:
                         str path, u8 kind (0 position 1 rotation 2 scale), u8 pre, u8 post,
                         u16 n keys, each f32 time, value[d], inSlope[d], outSlope[d]
                         (d = 4 for rotation, else 3)
    u32 n audio:     str name, str file (under audio/), f32 length
    u32 n settings:  str key, f32 value (physics and time: shared by both scenes)
    u32 n scenes:    str name, u32 n settings (str key, f32 value), u32 n nodes, each:
        str name, i32 parent, u8 active, u8 layer, u16 tag, f32 pos[3] rot[4] scale[3],
        i32 mesh, u8 renderer (0 none, 1 on, 2 off), u8 n, i32 material[n],
        u8 rigidbody, [f32 mass, drag, angularDrag, u8 gravity, kinematic, interp],
        u8 n colliders, each: u8 kind (1 box 2 sphere 3 capsule 4 mesh),
            u8 trigger, u8 convex, i32 physmat, f32 center[3], f32 a[3], i32 mesh
            (box: a = size; sphere: a.x = radius; capsule: radius, height, dir),
        u8 light, [u8 enabled, u8 type, f32 color[4], intensity, range, spotAngle, u32 mask],
        u8 camera, [u8 enabled, f32 fov, near, far, u8 ortho, f32 size, depth, u8 clear,
                    f32 bg[4], u32 mask, f32 viewport[4]],
        u8 animation, [u8 enabled, i32 clip, u8 n, i32 clips[n], u8 wrap, u8 playAuto],
        u8 audio, [u8 enabled, i32 clip, u8 playOnAwake, f32 volume, pitch, u8 loop],
        u8 particles, [f32 minSize maxSize minEnergy maxEnergy minEmission maxEmission,
                       f32 worldVel[3] localVel[3] rndVel[3], emitterVelocityScale,
                       tangentVel[3], u8 emit, worldSpace, oneShot, f32 ellipsoid[3],
                       minEmitterRange,
                       u8 animator, [u8 animateColor, u8 rgba[5][4], f32 worldAxis[3],
                                     localAxis[3], sizeGrow, rndForce[3], force[3],
                                     damping, u8 autodestruct],
                       u8 renderer, [u8 enabled, i32 material, u8 stretch,
                                     i32 xTile, yTile, f32 cycles]],
        u8 n scripts, each: str class, u8 enabled, value (an object)

    value: u8 tag, then
        0 null | 1 f64 number (int, float and bool alike) | 2 str
        3 u8 n, f32[n] (Vector2/3/4, Quaternion, Color, Rect)
        4 i32 node, u16 unity class id (1 = the GameObject itself), u8 ordinal among that
          node's components of that class  (a reference into the same scene)
        5 u8 asset kind (1 texture 2 material 3 mesh 4 audio 5 clip 6 physmat), i32 index
        6 str name (an asset of a kind not baked: GUISkin, Font, ...)
        7 u16 n, value[n] (array)
        8 u16 n, (str key, value)[n] (object)
"""
import math
import os
import struct
import sys
import zlib

from classes import PPtr
from extract import Bundle, mesh_triangles, write_wav
from scripts import ScriptSet
import textures

SCENES = ["mainData", "level0"]

# Unity's built-in shaders are not in the bundle; materials name them by their
# `unity default resources` path ID. 7 is Diffuse (every Unity scene's
# Default-Diffuse points there); 30 Transparent/Diffuse and 34 Transparent/VertexLit
# are argued from what uses them (the drop shadows; the glasses, whose material
# carries VertexLit's _SpecColor/_Emission/_Shininess); 6 carries VertexLit's
# properties on an opaque backdrop and is taken as VertexLit. 32 is argued the same
# way as 34: its two users are the digit atlas (`number_map`) and a glass pane, both
# of which need their alpha. 200 is the light-ray card, whose material carries a
# particle shader's _TintColor, taken as Particles/Additive; 10101 is the three font
# materials, the GUI text shader.
BUILTIN_SHADERS = {7: "Diffuse", 6: "VertexLit", 30: "Transparent/Diffuse",
                   32: "Transparent/VertexLit", 34: "Transparent/VertexLit",
                   200: "Particles/Additive", 10101: "GUI/Text Shader", 10302: "Diffuse"}

# `library/unity default resources` meshes, synthesized: Unity's primitive cylinder is
# radius 0.5, height 2 about Y, its cube is the unit cube, and its plane is 10x10 in XZ
# facing +Y as an 11x11 vertex grid; its sphere is radius 0.5 and its capsule radius
# 0.5, height 2 about Y. The sphere and capsule users are all collider helpers whose
# renderers are off, so those two are only rough shapes.
BUILTIN_MESHES = {10206: "cylinder", 10202: "cube", 10209: "plane", 10207: "sphere",
                  10208: "capsule"}
CYLINDER_SEGMENTS = 20


class Out:
    def __init__(self):
        self.b = bytearray()

    def u8(self, v): self.b += struct.pack("<B", v)
    def u16(self, v): self.b += struct.pack("<H", v)
    def u32(self, v): self.b += struct.pack("<I", v & 0xffffffff)
    def i32(self, v): self.b += struct.pack("<i", v)
    def f32(self, *v): self.b += struct.pack(f"<{len(v)}f", *v)
    def raw(self, v): self.b += v

    def str(self, s):
        e = s.encode("utf-8"); self.u16(len(e)); self.b += e


def builtin_mesh(kind):
    if kind == "plane":
        v, uv, tris = [], [], []
        for j in range(11):
            for i in range(11):
                v.append((5.0 - i, 0.0, 5.0 - j)); uv.append((i / 10, j / 10))
        for j in range(10):
            for i in range(10):
                a = j * 11 + i
                tris += [(a, a + 11, a + 1), (a + 1, a + 11, a + 12)]
        return dict(name="builtin_plane", vertices=v, normals=[(0.0, 1.0, 0.0)] * len(v),
                    uv=uv, colors=[], uv1=[], submeshes=[tris])
    if kind == "cube":
        v = [(x, y, z) for x in (-.5, .5) for y in (-.5, .5) for z in (-.5, .5)]
        return dict(name="builtin_cube", vertices=v, normals=[], uv=[], colors=[], uv1=[],
                    submeshes=[[(0, 1, 3), (0, 3, 2), (4, 6, 7), (4, 7, 5), (0, 4, 5), (0, 5, 1),
                                (2, 3, 7), (2, 7, 6), (0, 2, 6), (0, 6, 4), (1, 5, 7), (1, 7, 3)]])
    if kind in ("sphere", "capsule"):
        rings, segs = 8, 12
        v, tris = [], []
        for j in range(rings + 1):
            t = math.pi * j / rings
            y = 0.5 * math.cos(t)
            if kind == "capsule":
                y += 0.5 if j <= rings // 2 else -0.5
            for i in range(segs):
                a = 2 * math.pi * i / segs
                v.append((0.5 * math.sin(t) * math.cos(a), y, 0.5 * math.sin(t) * math.sin(a)))
        for j in range(rings):
            for i in range(segs):
                a, b = j * segs + i, j * segs + (i + 1) % segs
                tris += [(a, b, a + segs), (b, b + segs, a + segs)]
        return dict(name="builtin_" + kind, vertices=v, normals=[], uv=[], colors=[], uv1=[],
                    submeshes=[tris])
    n = CYLINDER_SEGMENTS
    v = []
    for y in (-1.0, 1.0):
        for i in range(n):
            a = 2 * math.pi * i / n
            v.append((0.5 * math.cos(a), y, 0.5 * math.sin(a)))
    v += [(0, -1.0, 0), (0, 1.0, 0)]
    tris = []
    for i in range(n):
        j = (i + 1) % n
        tris += [(i, n + i, n + j), (i, n + j, j), (2 * n, i, j), (2 * n + 1, n + j, n + i)]
    return dict(name="builtin_cylinder", vertices=v, normals=[], uv=[], colors=[], uv1=[],
                submeshes=[tris])


class Baker:
    def __init__(self, data_dir, cs_dir):
        self.b = Bundle(data_dir)
        self.ss = ScriptSet(cs_dir)
        self.textures, self.meshes, self.physmats, self.materials = [], [], [], []
        self.clips, self.audio = [], []
        self.index = {}                    # (kind, file, path_id) -> index

    # -- assets ----------------------------------------------------------------

    def _key(self, src, pptr):
        o = self.b.resolve(src, pptr)
        if o is None:
            return None, None
        return (o.file.name, o.path_id), o

    def texture(self, src, pptr):
        if pptr is None or pptr[1] == 0:
            return -1
        key, o = self._key(src, pptr)
        if o is None:
            return -1
        k = ("tex",) + key
        if k not in self.index:
            self.index[k] = len(self.textures)
            self.textures.append(self.b.get(o))
        return self.index[k]

    def mesh(self, src, pptr):
        if pptr is None or pptr[1] == 0:
            return -1
        key, o = self._key(src, pptr)
        if o is None:
            kind = BUILTIN_MESHES.get(pptr[1])
            if kind is None:
                raise ValueError(f"unknown built-in mesh {pptr}")
            k = ("mesh", "builtin", pptr[1])
            if k not in self.index:
                self.index[k] = len(self.meshes)
                self.meshes.append(builtin_mesh(kind))
            return self.index[k]
        k = ("mesh",) + key
        if k not in self.index:
            m = self.b.get(o)
            self.index[k] = len(self.meshes)
            self.meshes.append(dict(
                name=m["name"], vertices=m["vertices"],
                normals=[t["normal"] for t in m["tangent_space"]],
                uv=m["uv"], uv1=m["uv1"], colors=m["colors"],
                submeshes=mesh_triangles(m)))
        return self.index[k]

    def physmat(self, src, pptr):
        if pptr is None or pptr[1] == 0:
            return -1
        key, o = self._key(src, pptr)
        if o is None:
            return -1
        k = ("pm",) + key
        if k not in self.index:
            self.index[k] = len(self.physmats)
            self.physmats.append(self.b.get(o))
        return self.index[k]

    def material(self, src, pptr):
        if pptr is None or pptr[1] == 0:
            return -1
        key, o = self._key(src, pptr)
        if o is None:
            k = ("mat", "builtin", pptr[1])
            if k not in self.index:
                self.index[k] = len(self.materials)
                self.materials.append(dict(name=f"builtin_{pptr[1]}", shader="Diffuse",
                                           colors={}, floats={}, tex={}))
            return self.index[k]
        k = ("mat",) + key
        if k not in self.index:
            m = self.b.get(o)
            so = self.b.resolve(o.file, m["shader"])
            if so is None:
                shader = BUILTIN_SHADERS[m["shader"][1]]
            else:
                shader = self.b.get(so)["name"]
            tex = {}
            for name, t in m["textures"].items():
                tex[name] = (self.texture(o.file, t["texture"]), t["scale"] + t["offset"])
            self.index[k] = len(self.materials)
            self.materials.append(dict(name=m["name"], shader=shader, colors=m["colors"],
                                       floats=m["floats"], tex=tex))
        return self.index[k]

    def clip(self, src, pptr):
        if pptr is None or pptr[1] == 0:
            return -1
        key, o = self._key(src, pptr)
        if o is None:
            return -1
        k = ("clip",) + key
        if k not in self.index:
            self.index[k] = len(self.clips)
            self.clips.append(self.b.get(o))
        return self.index[k]

    def audio_clip(self, src, pptr):
        if pptr is None or pptr[1] == 0:
            return -1
        key, o = self._key(src, pptr)
        if o is None:
            return -1
        k = ("audio",) + key
        if k not in self.index:
            self.index[k] = len(self.audio)
            self.audio.append(self.b.get(o))
        return self.index[k]

    # -- scenes ----------------------------------------------------------------

    def scene(self, fname):
        """Nodes of one scene, parents before children, in each Transform's child order."""
        f = self.b.files[fname]
        gos = {o.path_id: self.b.get(o) for o in f.by_class(1)}
        trs = {o.path_id: self.b.get(o) for o in f.by_class(4)}
        tr_of_go = {}
        for pid, go in gos.items():
            for cid, co in go["components"]:
                if cid == 4:
                    tr_of_go[pid] = co[1]
        go_of_tr = {v: k for k, v in tr_of_go.items()}
        order = []

        def add(go_id, parent):
            i = len(order)
            order.append((go_id, parent))
            for c in trs[tr_of_go[go_id]]["children"]:
                add(go_of_tr[c[1]], i)

        for go_id in sorted(gos):
            if trs[tr_of_go[go_id]]["father"][1] == 0:
                add(go_id, -1)
        node_of_go = {gid: i for i, (gid, _) in enumerate(order)}
        # Every component of the scene -> (node, class id, ordinal), for references.
        comp_ref = {}
        for gid, i in node_of_go.items():
            seen = {}
            for cid, co in gos[gid]["components"]:
                n = seen.get(cid, 0)
                seen[cid] = n + 1
                comp_ref[co[1]] = (i, cid, n)
        return f, gos, trs, tr_of_go, order, node_of_go, comp_ref

    def value(self, out, f, v, node_of_go, comp_ref):
        if v is None:
            out.u8(0)
        elif isinstance(v, PPtr):
            if v[1] == 0:
                out.u8(0)
                return
            if v[0] == 0:
                if v[1] in node_of_go:
                    out.u8(4); out.i32(node_of_go[v[1]]); out.u16(1); out.u8(0)
                elif v[1] in comp_ref:
                    n, cid, k = comp_ref[v[1]]
                    out.u8(4); out.i32(n); out.u16(cid); out.u8(k)
                else:
                    out.u8(0)
                return
            o = self.b.resolve(f, v)
            if o is None:
                out.u8(0)
                return
            kind, idx = {28: (1, self.texture), 21: (2, self.material), 43: (3, self.mesh),
                         83: (4, self.audio_clip), 74: (5, self.clip),
                         134: (6, self.physmat)}.get(o.class_id, (0, None))
            if idx is None:
                d = self.b.get(o) if o.class_id in (114, 128) or hasattr(o, "data") else None
                name = ""
                try:
                    name = self.b.get(o).get("name", "")
                except Exception:
                    pass
                out.u8(6); out.str(name or "")
                return
            out.u8(5); out.u8(kind); out.i32(idx(f, v))
        elif isinstance(v, bool) or isinstance(v, (int, float)):
            out.u8(1); out.b += struct.pack("<d", float(v))
        elif isinstance(v, str):
            out.u8(2); out.str(v)
        elif isinstance(v, dict):
            out.u8(8); out.u16(len(v))
            for k, x in v.items():
                out.str(k); self.value(out, f, x, node_of_go, comp_ref)
        elif isinstance(v, (list, tuple)):
            if v and all(isinstance(x, float) for x in v) and len(v) in (2, 3, 4, 16):
                out.u8(3); out.u8(len(v)); out.f32(*v)
            else:
                out.u8(7); out.u16(len(v))
                for x in v:
                    self.value(out, f, x, node_of_go, comp_ref)
        else:
            raise TypeError(f"field value {v!r}")

    def bake_scene(self, out, fname):
        f, gos, trs, tr_of_go, order, node_of_go, comp_ref = self.scene(fname)
        self.last_order = (fname, order, gos)
        rs = f.by_class(104)[0].data if f.by_class(104) else None
        settings = []
        if rs is not None:
            amb = struct.unpack_from("<4f", rs, 24)          # RenderSettings.m_AmbientLight
            settings += [("ambientR", amb[0]), ("ambientG", amb[1]), ("ambientB", amb[2])]
        out.str(fname)
        out.u32(len(settings))
        for k, v in settings:
            out.str(k); out.f32(v)
        out.u32(len(order))
        for gid, parent in order:
            self.bake_node(out, f, gos[gid], trs[tr_of_go[gid]], parent, node_of_go, comp_ref)

    def bake_node(self, out, f, go, tr, parent, node_of_go, comp_ref):
        comps = {}
        for cid, co in go["components"]:
            comps.setdefault(cid, []).append(co)

        def get(co):
            return self.b.get(self.b.resolve(f, co))

        out.str(go["name"]); out.i32(parent)
        out.u8(1 if go["active"] else 0); out.u8(go["layer"]); out.u16(go["tag"])
        out.f32(*tr["position"]); out.f32(*tr["rotation"]); out.f32(*tr["scale"])

        out.i32(self.mesh(f, get(comps[33][0])["mesh"]) if 33 in comps else -1)
        if 23 in comps:
            mr = get(comps[23][0])
            out.u8(1 if mr["flags"][0] else 2)
            mats = [self.material(f, p) for p in mr["materials"]]
            out.u8(len(mats))
            for m in mats:
                out.i32(m)
        else:
            out.u8(0); out.u8(0)

        if 54 in comps:
            rb = get(comps[54][0])
            out.u8(1); out.f32(rb["mass"], rb["drag"], rb["angular_drag"])
            out.u8(rb["flags"][0]); out.u8(rb["flags"][1]); out.u8(rb["flags"][2])
        else:
            out.u8(0)

        cols = []
        for cid, co in go["components"]:
            if cid in (65, 135, 136, 64):
                cols.append((cid, get(co)))
        out.u8(len(cols))
        for cid, c in cols:
            out.u8({65: 1, 135: 2, 136: 3, 64: 4}[cid]); out.u8(1 if c["is_trigger"] else 0)
            out.u8(1 if c.get("convex") else 0)
            out.i32(self.physmat(f, c["material"]))
            if cid == 65:
                out.f32(*c["center"]); out.f32(*c["size"]); out.i32(-1)
            elif cid == 135:
                out.f32(*c["center"]); out.f32(c["radius"], 0, 0); out.i32(-1)
            elif cid == 136:
                out.f32(*c["center"]); out.f32(c["radius"], c["height"], c["direction"]); out.i32(-1)
            else:
                out.f32(0, 0, 0); out.f32(0, 0, 0); out.i32(self.mesh(f, c["mesh"]))

        if 108 in comps:
            lt = get(comps[108][0])
            rest = bytes.fromhex(lt["rest"])
            # Light after the colour: attenuate, intensity, range, spotAngle, the shadow
            # block, cookie, halo, flare, render mode, and the culling mask last.
            intensity, rng, spot = struct.unpack_from("<3f", rest, 4)
            mask, = struct.unpack_from("<I", rest, len(rest) - 4)
            out.u8(1); out.u8(1 if lt["enabled"] else 0); out.u8(lt["type"]); out.f32(*lt["color"])
            out.f32(intensity, rng, spot); out.u32(mask)
        else:
            out.u8(0)

        if 20 in comps:
            c = get(comps[20][0])
            out.u8(1); out.u8(1 if c["enabled"] else 0)
            out.f32(c["fov"], c["near"], c["far"]); out.u8(1 if c["orthographic"] else 0)
            out.f32(c["ortho_size"], c["depth"]); out.u8(c["clear_flags"]); out.f32(*c["background"])
            out.u32(c["culling_mask"]); out.f32(*c["viewport"])
        else:
            out.u8(0)

        if 111 in comps:
            a = get(comps[111][0])
            clips = [self.clip(f, p) for p in a["clips"]]
            out.u8(1); out.u8(1 if a["enabled"] else 0); out.i32(self.clip(f, a["clip"]))
            out.u8(len(clips))
            for c in clips:
                out.i32(c)
            out.u8(a["wrap_mode"]); out.u8(1 if a["play_automatically"] else 0)
        else:
            out.u8(0)

        if 82 in comps:
            a = get(comps[82][0])
            # After the clip: playOnAwake (aligned), volume, pitch, minVolume, maxVolume,
            # rolloffFactor, loop. Read off all thirty sources: only the front end's and
            # the trophy's play on awake, only the lazy susan's loops.
            rest = bytes.fromhex(a["rest"])
            poa = rest[0]
            vol, pitch = struct.unpack_from("<2f", rest, 4)
            loop = rest[24]
            out.u8(1); out.u8(1 if a["enabled"] else 0); out.i32(self.audio_clip(f, a["clip"]))
            out.u8(poa); out.f32(vol, pitch); out.u8(loop)
        else:
            out.u8(0)

        if 15 in comps:
            self.bake_particles(out, f, comps)
        else:
            out.u8(0)

        scripts = []
        for co in comps.get(114, []):
            d = get(co)
            s = self.b.deref(f, d["script"])[1]
            if s is None:
                continue
            scripts.append((s["class_name"], d["enabled"],
                            self.ss.decode_fields(s["class_name"], d["fields"])))
        out.u8(len(scripts))
        for name, enabled, fields in scripts:
            out.str(name); out.u8(1 if enabled else 0)
            self.value(out, f, fields, node_of_go, comp_ref)

    def bake_particles(self, out, f, comps):
        """
        The legacy particle trio, read raw: this build strips the type trees and no
        decoder in `classes.py` covers them. The one emitter in the game, `GlassFlash`
        in `level0`, is what the offsets were read off. None of the three has an
        enabled flag after its GameObject PPtr, unlike a Behaviour.

        EllipsoidParticleEmitter (108 bytes): +8 emit (padded to 4), +12 minSize maxSize
        minEnergy maxEnergy minEmission maxEmission, +36 worldVelocity, +48 localVelocity,
        +60 rndVelocity, +72 emitterVelocityScale, +76 tangentVelocity, +88 simulate in
        world space and one shot (padded to 4), +92 ellipsoid, +104 minEmitterRange.
        GlassFlash reads 1.2/1.2, 0.1/0.1, 1/1, and 0.05 for the velocity scale, which is
        Unity's default for it. Reading the ranges from +8 instead puts maxEmission's 1 in
        worldVelocity.x, and every flash drifts right. Which bool is which at +88
        (`01 01`) is argued from Unity's field order, not traced.

        ParticleAnimator (90 bytes): +8 doesAnimateColor (padded to 4), +12 five
        colours as RGBA bytes, +32 worldRotationAxis, +44 localRotationAxis,
        +56 sizeGrow, +60 rndForce, +72 force, +84 damping, +88 stopSimulation and
        autodestruct.

        ParticleRenderer (120 bytes): the Renderer head (+8 enabled, castShadows,
        receiveShadows, lightmap index; +12 the material array), then +52 camera
        velocity scale, +56 stretch mode (0 billboard), +60..+68 three floats (length
        scale 2 and two more that billboards do not read), three empty curves, and
        +108 the UV animation's x tile, y tile, cycles.
        """
        def raw(cid):
            if cid not in comps:
                return None
            o = self.b.resolve(f, comps[cid][0])
            return o.file.data[o.start:o.start + o.size]

        e = raw(15)
        out.u8(1)
        out.f32(*struct.unpack_from("<6f", e, 12))     # size, energy, emission ranges
        out.f32(*struct.unpack_from("<13f", e, 36))    # velocities, emitter scale, tangent
        out.u8(e[8]); out.u8(e[88]); out.u8(e[89])     # emit, world space, one shot
        out.f32(*struct.unpack_from("<4f", e, 92))     # ellipsoid, min emitter range

        a = raw(12)
        if a is None:
            out.u8(0)
        else:
            out.u8(1); out.u8(a[8]); out.raw(a[12:32])
            out.f32(*struct.unpack_from("<14f", a, 32))  # axes, sizeGrow, forces, damping
            out.u8(a[89])                                # autodestruct

        r = raw(26)
        if r is None:
            out.u8(0)
        else:
            n, = struct.unpack_from("<I", r, 12)
            assert n == 1, "the offsets below assume one material"
            mat = self.material(f, struct.unpack_from("<ii", r, 16))
            out.u8(1); out.u8(r[8]); out.i32(mat)
            out.u8(struct.unpack_from("<i", r, 56)[0])
            x_tile, y_tile = struct.unpack_from("<2i", r, 108)
            out.i32(x_tile); out.i32(y_tile); out.f32(*struct.unpack_from("<f", r, 116))

    # -- write -----------------------------------------------------------------

    def bake(self):
        scenes = Out()
        scenes.u32(len(SCENES))
        for s in SCENES:
            self.bake_scene(scenes, s)

        out = Out()
        out.raw(b"IQP2"); out.u32(3)
        out.u32(len(self.textures))
        for t in self.textures:
            rows = textures.decode(t)[::-1]           # back to bottom-up, as stored
            out.str(t["name"]); out.u16(t["width"]); out.u16(t["height"])
            out.u8(t["wrap_mode"]); out.u8(t["filter_mode"]); out.u8(1 if t["mipmap"] else 0); out.u8(0)
            z = zlib.compress(b"".join(rows), 9)
            out.u32(len(z)); out.raw(z)
        out.u32(len(self.meshes))
        for m in self.meshes:
            n = len(m["vertices"])
            attrs = ((1 if m["normals"] else 0) | (2 if m["uv"] else 0)
                     | (4 if m["colors"] else 0) | (8 if m["uv1"] else 0))
            out.str(m["name"]); out.u32(n); out.u8(attrs)
            for v in m["vertices"]:
                out.f32(*v)
            for v in m["normals"]:
                out.f32(*v)
            for v in m["uv"]:
                out.f32(*v)
            for c in m["colors"]:
                out.u32(c)
            for v in m["uv1"]:
                out.f32(*v)
            if n > 65535:
                raise ValueError(f"{m['name']}: {n} vertices")
            out.u16(len(m["submeshes"]))
            for tris in m["submeshes"]:
                out.u32(3 * len(tris))
                for t in tris:
                    out.u16(t[0]); out.u16(t[1]); out.u16(t[2])
        out.u32(len(self.physmats))
        for p in self.physmats:
            out.str(p["name"]); out.f32(p["dynamic_friction"], p["static_friction"], p["bounciness"])
            out.u8(p["friction_combine"]); out.u8(p["bounce_combine"])
        out.u32(len(self.materials))
        for m in self.materials:
            c = m["colors"]
            out.str(m["name"]); out.str(m["shader"])
            for key, default in (("_Color", (1, 1, 1, 1)), ("_SpecColor", (1, 1, 1, 0)),
                                 ("_Emission", (0, 0, 0, 0)), ("_TintColor", (.5, .5, .5, .5))):
                out.f32(*c.get(key, default))
            out.f32(m["floats"].get("_Shininess", 0.7))
            for key in ("_MainTex", "_Detail"):
                t, st = m["tex"].get(key, (-1, [1, 1, 0, 0]))
                out.i32(t); out.f32(*st)
        out.u32(len(self.clips))
        for c in self.clips:
            curves = [(k, cv) for k, kind in ((0, "position"), (1, "rotation"), (2, "scale"))
                      for cv in c[kind]]
            out.str(c["name"]); out.f32(c["sample_rate"]); out.u8(c["wrap_mode"])
            out.u16(len(curves))
            for kind, cv in curves:
                cu = cv["curve"]
                out.str(cv["path"]); out.u8(kind)
                out.u8(cu["pre_infinity"]); out.u8(cu["post_infinity"])
                out.u16(len(cu["keys"]))
                for key in cu["keys"]:
                    out.f32(key["time"]); out.f32(*key["value"])
                    out.f32(*key["in_slope"]); out.f32(*key["out_slope"])
        out.u32(len(self.audio))
        self.audio_files = []
        for i, a in enumerate(self.audio):
            fn = f"{i:02d}_{a['name']}.wav"
            self.audio_files.append((fn, a))
            out.str(a["name"]); out.str(fn); out.f32(a["length"])
        settings = self.settings()
        out.u32(len(settings))
        for k, v in settings:
            out.str(k); out.f32(v)
        out.raw(bytes(scenes.b))
        return bytes(out.b)

    def settings(self):
        md = self.b.files["mainData"]
        pm = self.b.get(md.by_class(55)[0])
        tm = self.b.get(md.by_class(5)[0])
        return [("gravityX", pm["gravity"][0]), ("gravityY", pm["gravity"][1]),
                ("gravityZ", pm["gravity"][2]), ("bounceThreshold", pm["bounce_threshold"]),
                ("sleepVelocity", pm["sleep_velocity"]),
                ("sleepAngularVelocity", pm["sleep_angular_velocity"]),
                ("maxAngularVelocity", pm["max_angular_velocity"]),
                ("minPenetrationForPenalty", pm["min_penetration_for_penalty"]),
                ("solverIterationCount", pm["solver_iteration_count"]),
                ("fixedTimestep", tm["fixed_timestep"]),
                ("maximumAllowedTimestep", tm["maximum_allowed_timestep"]),
                ("timeScale", tm["time_scale"])]

    def write(self, out_dir):
        data = self.bake()
        os.makedirs(os.path.join(out_dir, "audio"), exist_ok=True)
        with open(os.path.join(out_dir, "scene.pack"), "wb") as fh:
            fh.write(data)
        for fn, a in self.audio_files:
            write_wav(os.path.join(out_dir, "audio", fn), a)
        return data


if __name__ == "__main__":
    a = sys.argv
    bk = Baker(a[1], a[2])
    data = bk.write(a[3])
    print(f"{len(data)} bytes: {len(bk.meshes)} meshes, {len(bk.textures)} textures, "
          f"{len(bk.materials)} materials, {len(bk.physmats)} physmats, {len(bk.clips)} clips, "
          f"{len(bk.audio)} audio clips")
