"""
Class layouts for Unity 2.x serialized objects (format 6, type trees
stripped), decoded by hand against iQuarters' own data. Every reader here
must consume its object exactly; `check_all` in `extract.py` asserts that
for every object of every class it decodes, which is what makes these
layouts findings rather than guesses.
"""
import struct


class PPtr(tuple):
    """(fileID, pathID). fileID is 0 for this file, else 1-based into its externals."""


class Reader:
    def __init__(self, data):
        self.d, self.p = data, 0

    def i32(self):
        v, = struct.unpack_from("<i", self.d, self.p); self.p += 4; return v

    def u32(self):
        v, = struct.unpack_from("<I", self.d, self.p); self.p += 4; return v

    def u16(self):
        v, = struct.unpack_from("<H", self.d, self.p); self.p += 2; return v

    def u8(self):
        v = self.d[self.p]; self.p += 1; return v

    def f32(self):
        v, = struct.unpack_from("<f", self.d, self.p); self.p += 4; return v

    def floats(self, n):
        v = struct.unpack_from(f"<{n}f", self.d, self.p); self.p += 4 * n; return list(v)

    def align(self):
        self.p = (self.p + 3) & ~3

    def string(self):
        n = self.i32()
        s = self.d[self.p:self.p + n].decode("utf-8", "replace"); self.p += n
        self.align()
        return s

    def bytes_(self, n):
        v = self.d[self.p:self.p + n]; self.p += n; return v

    def pptr(self):
        return PPtr((self.i32(), self.i32()))

    def array(self, item):
        return [item() for _ in range(self.i32())]

    def vec3(self):
        return self.floats(3)

    @property
    def done(self):
        return self.p == len(self.d)


def game_object(r):
    comps = r.array(lambda: (r.i32(), r.pptr()))
    layer = r.i32()
    name = r.string()
    tag = r.u16()
    active = r.u8()
    return dict(components=comps, layer=layer, name=name, tag=tag, active=bool(active))


def transform(r):
    return dict(
        game_object=r.pptr(),
        rotation=r.floats(4),           # x, y, z, w
        position=r.vec3(),
        scale=r.vec3(),
        children=r.array(r.pptr),
        father=r.pptr(),
    )


def physic_material(r):
    return dict(
        name=r.string(),
        dynamic_friction=r.f32(),
        static_friction=r.f32(),
        bounciness=r.f32(),
        friction_combine=r.i32(),       # 0 Average, 1 Multiply, 2 Minimum, 3 Maximum
        bounce_combine=r.i32(),
        friction_direction2=r.vec3(),
        dynamic_friction2=r.f32(),
        static_friction2=r.f32(),
    )


TEXTURE_FORMATS = {1: "Alpha8", 2: "ARGB4444", 3: "RGB24", 4: "RGBA32", 5: "ARGB32",
                   7: "RGB565", 30: "PVRTC_RGB2", 31: "PVRTC_RGBA2",
                   32: "PVRTC_RGB4", 33: "PVRTC_RGBA4"}


def texture2d(r):
    t = dict(name=r.string(), width=r.i32(), height=r.i32(),
             complete_image_size=r.i32(), format=r.i32())
    t["mipmap"] = bool(r.u8()); r.align()
    t["image_count"] = r.i32()
    t["dimension"] = r.i32()
    t["filter_mode"] = r.i32()
    t["aniso"] = r.i32()
    t["mip_bias"] = r.f32()
    t["wrap_mode"] = r.i32()
    t["image"] = r.bytes_(r.i32())
    r.align()
    return t


def mesh(r):
    m = dict(name=r.string())
    m["use16bit"] = r.i32() != 0
    raw = r.bytes_(r.i32()); r.align()
    fmt = "<%dH" if m["use16bit"] else "<%dI"
    m["indices"] = list(struct.unpack(fmt % (len(raw) // (2 if m["use16bit"] else 4)), raw))
    m["submeshes"] = r.array(lambda: dict(first_byte=r.u32(), index_count=r.u32(),
                                          topology=r.i32(), triangle_count=r.u32()))
    m["vertices"] = r.array(r.vec3)
    m["skin"] = r.array(lambda: dict(weights=r.floats(4), bones=[r.i32() for _ in range(4)]))
    m["bind_pose"] = r.array(lambda: r.floats(16))
    m["uv"] = r.array(lambda: r.floats(2))
    m["uv1"] = r.array(lambda: r.floats(2))
    m["tangent_space"] = r.array(lambda: dict(normal=r.vec3(), tangent=r.vec3(), handedness=r.f32()))
    m["aabb"] = dict(center=r.vec3(), extent=r.vec3())
    m["colors"] = r.array(r.u32)
    m["collision_triangles"] = r.array(r.u32)
    m["collision_vertex_count"] = r.i32()
    m["tail"] = r.i32()
    return m


DECODERS = {1: game_object, 4: transform, 28: texture2d, 43: mesh, 134: physic_material}


def _flag(r):
    v = bool(r.u8()); r.align(); return v


def behaviour_head(r):
    go = r.pptr()
    enabled = bool(r.u8()); r.align()
    return go, enabled


def mesh_filter(r):
    return dict(game_object=r.pptr(), mesh=r.pptr())


def mesh_renderer(r):
    d = dict(game_object=r.pptr())
    # Four flag bytes; the last is the lightmap index (255 = none). Which of
    # the first three is enabled / castShadows / receiveShadows is not
    # settled, so they are kept raw.
    d["flags"] = list(r.bytes_(4))
    d["materials"] = r.array(r.pptr)
    d["subset_indices"] = r.array(r.u32)
    d["static_batch_root"] = r.pptr()
    d["lightmap_tiling_offset"] = r.floats(4)
    return d


def material(r):
    m = dict(name=r.string(), shader=r.pptr())
    m.update(properties(r))
    return m


def properties(r):
    """The texture/float/colour maps a Material and a Shader's defaults share."""
    m = {}
    m["textures"] = {}
    for _ in range(r.i32()):
        key = r.string()
        m["textures"][key] = dict(texture=r.pptr(), scale=r.floats(2), offset=r.floats(2))
    m["floats"] = {}
    for _ in range(r.i32()):
        key = r.string(); m["floats"][key] = r.f32()
    m["colors"] = {}
    for _ in range(r.i32()):
        key = r.string(); m["colors"][key] = r.floats(4)
    return m


def shader(r):
    d = dict(name=r.string(), script=r.string())
    d["dependencies"] = r.array(r.pptr)
    d["defaults"] = properties(r)
    d["tail"] = r.bytes_(12).hex()          # three zero words in all four shaders
    return d


def audio_clip(r):
    d = dict(name=r.string(), format=r.i32(), length=r.f32(), frequency=r.i32(),
             size=r.i32(), unknown=r.i32())
    d["data"] = r.bytes_(r.i32()); r.align()
    # 64 bytes of runtime state (heap pointers, zeros) the editor saved as is.
    d["runtime"] = r.bytes_(64)
    return d


def mono_script(r):
    d = dict(name=r.string(), execution_order=r.i32(), class_name=r.string(),
             assembly=r.string())
    d["is_editor_script"] = bool(r.u8())
    return d


def rigidbody(r):
    d = dict(game_object=r.pptr(), mass=r.f32(), drag=r.f32(), angular_drag=r.f32())
    d["flags"] = list(r.bytes_(4))          # useGravity / isKinematic / interpolate?, raw
    return d


def collider_head(r):
    return dict(game_object=r.pptr(), material=r.pptr(), is_trigger=bool(r.u8()),
                _pad=r.bytes_(3))


def box_collider(r):
    d = collider_head(r); d.update(size=r.vec3(), center=r.vec3()); return d


def sphere_collider(r):
    d = collider_head(r); d.update(radius=r.f32(), center=r.vec3()); return d


def capsule_collider(r):
    d = collider_head(r)
    d.update(radius=r.f32(), height=r.f32(), direction=r.i32(), center=r.vec3())
    return d


def mesh_collider(r):
    d = dict(game_object=r.pptr(), material=r.pptr(), is_trigger=bool(r.u8()),
             smooth_sphere_collisions=bool(r.u8()), convex=bool(r.u8()))
    r.align()
    d["mesh"] = r.pptr()
    return d


def camera(r):
    go, enabled = behaviour_head(r)
    return dict(game_object=go, enabled=enabled, clear_flags=r.i32(),
                background=r.floats(4), viewport=r.floats(4), near=r.f32(),
                far=r.f32(), fov=r.f32(), orthographic=_flag(r),
                ortho_size=r.f32(), depth=r.f32(), culling_mask=r.u32(),
                target_texture=r.pptr())


def light(r):
    go, enabled = behaviour_head(r)
    d = dict(game_object=go, enabled=enabled, type=r.i32(), color=r.floats(4))
    d["rest"] = r.bytes_(len(r.d) - r.p).hex()      # not needed yet
    return d


def audio_source(r):
    go, enabled = behaviour_head(r)
    d = dict(game_object=go, enabled=enabled, clip=r.pptr())
    d["rest"] = r.bytes_(len(r.d) - r.p).hex()
    return d


def animation(r):
    go, enabled = behaviour_head(r)
    d = dict(game_object=go, enabled=enabled, clip=r.pptr(), clips=r.array(r.pptr),
             wrap_mode=r.i32(), play_automatically=bool(r.u8()),
             animate_physics=bool(r.u8()))
    return d


def mono_behaviour(r):
    go, enabled = behaviour_head(r)
    d = dict(game_object=go, enabled=enabled, script=r.pptr(), name=r.string())
    d["fields"] = r.bytes_(len(r.d) - r.p)    # decoded against the script, see scripts.py
    return d


DECODERS.update({
    33: mesh_filter, 23: mesh_renderer, 21: material, 48: shader, 83: audio_clip,
    115: mono_script, 54: rigidbody, 65: box_collider, 135: sphere_collider,
    136: capsule_collider, 64: mesh_collider, 20: camera, 108: light,
    82: audio_source, 111: animation, 114: mono_behaviour,
})


def physics_manager(r):
    """Unity 2.x PhysicsManager, stock order; the defaults are 2/0.15/0.14/7/0.01/7."""
    return dict(gravity=r.vec3(), default_material=r.pptr(), bounce_threshold=r.f32(),
                sleep_velocity=r.f32(), sleep_angular_velocity=r.f32(),
                max_angular_velocity=r.f32(), min_penetration_for_penalty=r.f32(),
                solver_iteration_count=r.i32())


def time_manager(r):
    return dict(fixed_timestep=r.f32(), maximum_allowed_timestep=r.f32(), time_scale=r.f32())


DECODERS.update({55: physics_manager, 5: time_manager})


def _curve(r, n):
    """AnimationCurve of n-float values: keyframes {time, value, inSlope, outSlope}, then
    the pre- and post-infinity wrap modes (2 = WrapMode.Loop... as Unity 2.x numbers them)."""
    keys = r.array(lambda: dict(time=r.f32(), value=r.floats(n), in_slope=r.floats(n),
                                out_slope=r.floats(n)))
    return dict(keys=keys, pre_infinity=r.i32(), post_infinity=r.i32())


def animation_clip(r):
    d = dict(name=r.string())
    for kind, n in (("rotation", 4), ("position", 3), ("scale", 3)):
        d[kind] = r.array(lambda: dict(curve=_curve(r, n), path=r.string()))
    # No iQuarters clip has a float curve, so this record's layout is unverified.
    d["float"] = r.array(lambda: dict(curve=_curve(r, 1), attribute=r.string(),
                                      path=r.string(), class_id=r.i32()))
    d["sample_rate"] = r.f32()
    d["wrap_mode"] = r.i32()
    return d


DECODERS[74] = animation_clip
