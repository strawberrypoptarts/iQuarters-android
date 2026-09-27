"""
Reader for Unity's serialized-file format version 6 (Unity 2.x / Unity iPhone
1.x, 2009), the format of iQuarters' `Data/mainData`, `level0` and
`sharedassets*.assets`.

The layout, as found in those files:

- A big-endian header: metadataSize, fileSize, version (6), dataOffset (0).
- The metadata sits at the *end* of the file (fileSize - metadataSize), and
  starts with an endian byte (0 = little-endian for everything after it).
- A type count, which is 0 in a player build: the type trees are stripped,
  so no file describes its own class layouts. Those are decoded per class in
  `classes.py`.
- An object table: a count, then 20-byte records
  `{i32 pathID, u32 byteStart, u32 byteSize, i32 typeID, u16 classID,
  u16 isDestroyed}`, with byteStart relative to dataOffset.
- The externals: a count, then `{cstring empty, 16-byte GUID, i32 type,
  cstring path}` each, which a PPtr's fileID indexes (1-based; 0 = this file).

stdlib only.
"""
import struct
import sys
from collections import Counter

# Unity's class IDs, as far as this game uses them.
CLASS_NAMES = {
    1: "GameObject", 4: "Transform", 8: "Behaviour", 11: "AudioManager",
    12: "ParticleAnimator", 13: "InputManager", 15: "EllipsoidParticleEmitter",
    20: "Camera", 21: "Material", 23: "MeshRenderer", 25: "Renderer",
    26: "ParticleRenderer", 27: "Texture", 28: "Texture2D", 29: "SceneSettings",
    30: "GraphicsSettings", 33: "MeshFilter", 41: "OcclusionPortal", 43: "Mesh",
    45: "Skybox", 47: "QualitySettings", 48: "Shader", 49: "TextAsset",
    52: "NotificationManager", 53: "Rigidbody", 54: "Rigidbody",
    55: "PhysicsManager", 56: "Collider", 57: "Joint", 59: "HingeJoint",
    64: "MeshCollider", 65: "BoxCollider", 71: "AnimationManager",
    74: "AnimationClip", 75: "ConstantForce", 78: "TagManager",
    81: "AudioListener", 82: "AudioSource", 83: "AudioClip",
    84: "RenderTexture", 87: "MeshParticleEmitter", 88: "ParticleEmitter",
    89: "Cubemap", 90: "Avatar", 92: "GUILayer", 94: "ScriptMapper",
    96: "TrailRenderer", 98: "DelayedCallManager", 102: "TextMesh",
    104: "RenderSettings", 108: "Light", 109: "CGProgram", 110: "LightProbe",
    111: "Animation", 114: "MonoBehaviour", 115: "MonoScript",
    116: "MonoManager", 117: "Texture3D", 119: "Projector",
    120: "LineRenderer", 121: "Flare", 122: "Halo", 123: "LensFlare",
    124: "FlareLayer", 125: "HaloLayer", 126: "NavMeshLayers",
    127: "HaloManager", 128: "Font", 129: "PlayerSettings",
    130: "NamedObject", 131: "GUITexture", 132: "GUIText", 133: "GUIElement",
    134: "PhysicMaterial", 135: "SphereCollider", 136: "CapsuleCollider",
    137: "SkinnedMeshRenderer", 138: "FixedJoint", 141: "BuildSettings",
    142: "AssetBundle", 143: "CharacterController", 144: "CharacterJoint",
    145: "SpringJoint", 146: "WheelCollider", 147: "ResourceManager",
    148: "NetworkView", 149: "NetworkManager", 150: "PreloadData",
    152: "MovieTexture", 153: "ConfigurableJoint", 154: "TerrainCollider",
    156: "TerrainData", 157: "LightmapSettings",
}


class Obj:
    __slots__ = ("file", "path_id", "start", "size", "type_id", "class_id", "destroyed")

    def __init__(self, file, path_id, start, size, type_id, class_id, destroyed):
        self.file, self.path_id, self.start, self.size = file, path_id, start, size
        self.type_id, self.class_id, self.destroyed = type_id, class_id, destroyed

    @property
    def class_name(self):
        return CLASS_NAMES.get(self.class_id, f"class{self.class_id}")

    @property
    def data(self):
        return self.file.data[self.start:self.start + self.size]

    def __repr__(self):
        return f"<{self.class_name} {self.file.name}#{self.path_id} {self.size}B>"


class SerializedFile:
    def __init__(self, path):
        self.path = path
        self.name = path.rsplit("/", 1)[-1]
        self.data = open(path, "rb").read()
        meta_size, file_size, version, data_offset = struct.unpack(">4I", self.data[:16])
        if version != 6:
            raise ValueError(f"{path}: serialized format {version}, expected 6")
        m = self.data[file_size - meta_size:file_size]
        if m[0] != 0:
            raise ValueError(f"{path}: big-endian metadata is not handled")
        p = 1
        type_count, = struct.unpack_from("<i", m, p); p += 4
        if type_count:
            raise ValueError(f"{path}: has {type_count} type trees; this reader expects a stripped player build")
        count, = struct.unpack_from("<i", m, p); p += 4
        self.objects = {}
        for _ in range(count):
            pid, start, size, tid, cid, dead = struct.unpack_from("<iIIiHH", m, p); p += 20
            self.objects[pid] = Obj(self, pid, data_offset + start, size, tid, cid, dead)
        ext_count, = struct.unpack_from("<i", m, p); p += 4
        self.externals = []
        for _ in range(ext_count):
            end = m.index(b"\0", p); p = end + 1       # the empty string
            guid = m[p:p + 16]; p += 16
            etype, = struct.unpack_from("<i", m, p); p += 4
            end = m.index(b"\0", p)
            self.externals.append((m[p:end].decode("latin-1"), guid.hex(), etype)); p = end + 1
        self.trailing = len(m) - p

    def by_class(self, class_id):
        return [o for o in self.objects.values() if o.class_id == class_id]


if __name__ == "__main__":
    for path in sys.argv[1:]:
        f = SerializedFile(path)
        hist = Counter(o.class_name for o in f.objects.values())
        print(f"{f.name}: {len(f.objects)} objects, trailing metadata {f.trailing}B")
        for name, guid, etype in f.externals:
            print(f"  external {name} ({etype})")
        for name, n in hist.most_common():
            print(f"  {n:4d} {name}")
