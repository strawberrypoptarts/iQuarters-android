"""Decode MonoBehaviour field bytes against the decompiled C# scripts.

Unity 2.x writes a script's serializable fields in declaration order with no type tree:
public (or [SerializeField]) instance fields, not static/const/readonly/[NonSerialized],
whose type Unity can serialize. Each field is then aligned to 4 bytes. A field whose
type is a [Serializable] class of the project is written inline, recursively.

The scripts are a decompiled reconstruction, so their declarations are only as good as
the decompiler. `decode_fields` therefore demands that every byte is consumed: a field
list that is wrong is seen as a mismatch, not read as plausible-looking values.
"""

import os
import re

from classes import Reader

PRIMS = {
    "float": ("f32", 4), "single": ("f32", 4), "int": ("i32", 4), "int32": ("i32", 4),
    "uint": ("u32", 4), "bool": ("u8", 1), "boolean": ("u8", 1), "byte": ("u8", 1),
    "short": ("i16", 2), "char": ("u16", 2), "layermask": ("i32", 4),
}
STRUCTS = {
    "vector2": 2, "vector3": 3, "vector4": 4, "quaternion": 4, "color": 4, "rect": 4,
    "matrix4x4": 16,
}
# Unity-engine types that are not Objects but are written inline.
INLINE_UNITY = {"guistyle", "animationcurve", "guicontent", "rectoffset", "gradient"}
# Collections Unity 2.x does not serialize at all; such a field takes no bytes.
UNSERIALIZABLE = {"ArrayList", "Hashtable", "Dictionary", "Queue", "Stack", "HashSet"}
# Anything else starting with a capital that is not a project class is taken as a
# UnityEngine.Object subclass (GameObject, Transform, Material, GUISkin, Texture2D...),
# which serializes as an 8-byte PPtr.

FIELD_RE = re.compile(
    r"^\s*((?:\[[^\]]*\]\s*)*)((?:public|private|protected|internal|static|readonly|const|new)\s+)*"
    r"([A-Za-z_][\w.<>,\[\] ]*?)\s+([A-Za-z_]\w*)\s*(?:=[^;]*)?;\s*$")
CLASS_RE = re.compile(r"\b(class|struct|enum)\s+(\w+)")


class Field:
    def __init__(self, name, type_, serialized):
        self.name, self.type, self.serialized = name, type_, serialized


class ScriptSet:
    def __init__(self, script_dir):
        self.classes = {}         # name -> [Field]
        self.enums = set()
        self.serializable = set()
        for fn in sorted(os.listdir(script_dir)):
            if fn.endswith(".cs"):
                with open(os.path.join(script_dir, fn), encoding="utf-8-sig") as f:
                    self._parse(f.read())

    def _parse(self, src):
        depth = 0
        stack = []              # (class name, depth of its body)
        attrs = ""
        for line in src.splitlines():
            code = line.split("//")[0]
            m = CLASS_RE.search(code)
            if m and "(" not in code.split(m.group(0))[0]:
                kind, name = m.groups()
                if kind == "enum":
                    self.enums.add(name)
                else:
                    self.classes.setdefault(name, [])
                    if "Serializable" in attrs or "Serializable" in code or kind == "struct":
                        self.serializable.add(name)
                    stack.append((name, depth + 1, kind))
                attrs = ""
            elif code.strip().startswith("["):
                attrs += code
            elif stack and depth == stack[-1][1]:
                fm = FIELD_RE.match(code)
                if fm and "(" not in code.split("=")[0]:
                    lead = code.split(fm.group(3))[0]
                    mods = set(re.findall(r"\b(public|private|protected|internal|static|readonly|const)\b", lead))
                    fattrs = attrs + fm.group(1)
                    ser = (("public" in mods or "SerializeField" in fattrs)
                           and not mods & {"static", "const", "readonly"}
                           and "NonSerialized" not in fattrs)
                    self.classes[stack[-1][0]].append(Field(fm.group(4), fm.group(3).strip(), ser))
                if code.strip():
                    attrs = ""
            new = depth + code.count("{") - code.count("}")
            if new < depth:
                while stack and new < stack[-1][1]:
                    stack.pop()
            depth = new

    def fields(self, cls):
        return [f for f in self.classes.get(cls, [])
                if f.serialized and f.type.split("<")[0].split(".")[-1] not in UNSERIALIZABLE]

    # -- reading ---------------------------------------------------------

    def read_type(self, r, t):
        t = t.replace("UnityEngine.", "").replace("System.", "").strip()
        if t.endswith("[]"):
            n = r.i32()
            if not 0 <= n < 100000:
                raise ValueError(f"array length {n} for {t}")
            inner = t[:-2]
            base = inner.lower()
            items = [self.read_type(r, inner) for _ in range(n)]
            if base in PRIMS and PRIMS[base][1] < 4:
                r.align()
            return items
        if t.startswith("List<"):
            return self.read_type(r, t[5:-1] + "[]")
        lo = t.lower()
        if lo in PRIMS:
            kind, _ = PRIMS[lo]
            if kind == "i16":
                v = r.u16(); return v - 0x10000 if v & 0x8000 else v
            return getattr(r, kind)()
        if lo == "string":
            return r.string()
        if lo in STRUCTS:
            return r.floats(STRUCTS[lo])
        if t in self.enums:
            return r.i32()
        if t in self.classes:
            if t not in self.serializable:
                raise ValueError(f"{t} is not [Serializable] but is a field type")
            return self.read_object(r, t)
        if lo in INLINE_UNITY:
            raise NotImplementedError(f"inline Unity type {t}")
        return r.pptr()

    def read_object(self, r, cls):
        out = {}
        for f in self.fields(cls):
            out[f.name] = self.read_type(r, f.type)
            r.align()
        return out

    def decode_fields(self, cls, data):
        r = Reader(data)
        out = self.read_object(r, cls)
        if not r.done:
            raise ValueError(f"{cls}: {len(data) - r.p} of {len(data)} bytes left over")
        return out
