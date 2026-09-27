"""Minimal ECMA-335 reader and CIL disassembler, stdlib only.

iQuarters was written in UnityScript and its compiled IL is in the bundle as
`Data/Assembly - UnityScript.dll`. The C# scripts in circulation are a decompiled
reconstruction, and at least one condition in them calls an accessor the assembly never
references, so where a constant or a branch matters, read it here:

    python3 tools/iquarters/cil.py "Data/Assembly - UnityScript.dll" QuarterTrigger OnCollisionStay
    python3 tools/iquarters/cil.py DLL QuarterTrigger          # list methods and fields
    python3 tools/iquarters/cil.py DLL                         # list types
"""

import struct
import sys

# ECMA-335 II.22: every table's columns. Types: 2/4 fixed widths, 's' string, 'g' guid,
# 'b' blob, ('t', n) simple index into table n, ('c', name) coded index.
CODED = {
    "TypeDefOrRef": (2, [0x02, 0x01, 0x1B]),
    "HasConstant": (2, [0x04, 0x08, 0x17]),
    "HasCustomAttribute": (5, [0x06, 0x04, 0x01, 0x02, 0x08, 0x09, 0x0A, 0x00, 0x0E, 0x17,
                               0x14, 0x11, 0x1A, 0x1B, 0x20, 0x23, 0x26, 0x27, 0x28]),
    "HasFieldMarshal": (1, [0x04, 0x08]),
    "HasDeclSecurity": (2, [0x02, 0x06, 0x20]),
    "MemberRefParent": (3, [0x02, 0x01, 0x1A, 0x06, 0x1B]),
    "HasSemantics": (1, [0x14, 0x17]),
    "MethodDefOrRef": (1, [0x06, 0x0A]),
    "MemberForwarded": (1, [0x04, 0x06]),
    "Implementation": (2, [0x26, 0x23, 0x27]),
    "CustomAttributeType": (3, [0x06, 0x06, 0x06, 0x0A, 0x06]),
    "ResolutionScope": (2, [0x00, 0x1A, 0x23, 0x01]),
    "TypeOrMethodDef": (1, [0x02, 0x06]),
}
T = lambda n: ("t", n)
C = lambda n: ("c", n)
SCHEMA = {
    0x00: ("Module", [2, "s", "g", "g", "g"]),
    0x01: ("TypeRef", [C("ResolutionScope"), "s", "s"]),
    0x02: ("TypeDef", [4, "s", "s", C("TypeDefOrRef"), T(0x04), T(0x06)]),
    0x03: ("FieldPtr", [T(0x04)]),
    0x04: ("Field", [2, "s", "b"]),
    0x05: ("MethodPtr", [T(0x06)]),
    0x06: ("MethodDef", [4, 2, 2, "s", "b", T(0x08)]),
    0x07: ("ParamPtr", [T(0x08)]),
    0x08: ("Param", [2, 2, "s"]),
    0x09: ("InterfaceImpl", [T(0x02), C("TypeDefOrRef")]),
    0x0A: ("MemberRef", [C("MemberRefParent"), "s", "b"]),
    0x0B: ("Constant", [2, C("HasConstant"), "b"]),
    0x0C: ("CustomAttribute", [C("HasCustomAttribute"), C("CustomAttributeType"), "b"]),
    0x0D: ("FieldMarshal", [C("HasFieldMarshal"), "b"]),
    0x0E: ("DeclSecurity", [2, C("HasDeclSecurity"), "b"]),
    0x0F: ("ClassLayout", [2, 4, T(0x02)]),
    0x10: ("FieldLayout", [4, T(0x04)]),
    0x11: ("StandAloneSig", ["b"]),
    0x12: ("EventMap", [T(0x02), T(0x14)]),
    0x13: ("EventPtr", [T(0x14)]),
    0x14: ("Event", [2, "s", C("TypeDefOrRef")]),
    0x15: ("PropertyMap", [T(0x02), T(0x17)]),
    0x16: ("PropertyPtr", [T(0x17)]),
    0x17: ("Property", [2, "s", "b"]),
    0x18: ("MethodSemantics", [2, T(0x06), C("HasSemantics")]),
    0x19: ("MethodImpl", [T(0x02), C("MethodDefOrRef"), C("MethodDefOrRef")]),
    0x1A: ("ModuleRef", ["s"]),
    0x1B: ("TypeSpec", ["b"]),
    0x1C: ("ImplMap", [2, C("MemberForwarded"), "s", T(0x1A)]),
    0x1D: ("FieldRVA", [4, T(0x04)]),
    0x20: ("Assembly", [4, 2, 2, 2, 2, 4, "b", "s", "s"]),
    0x21: ("AssemblyProcessor", [4]),
    0x22: ("AssemblyOS", [4, 4, 4]),
    0x23: ("AssemblyRef", [2, 2, 2, 2, 4, "b", "s", "s", "b"]),
    0x24: ("AssemblyRefProcessor", [4, T(0x23)]),
    0x25: ("AssemblyRefOS", [4, 4, 4, T(0x23)]),
    0x26: ("File", [4, "s", "b"]),
    0x27: ("ExportedType", [4, 4, "s", "s", C("Implementation")]),
    0x28: ("ManifestResource", [4, 4, "s", C("Implementation")]),
    0x29: ("NestedClass", [T(0x02), T(0x02)]),
    0x2A: ("GenericParam", [2, 2, C("TypeOrMethodDef"), "s"]),
    0x2B: ("MethodSpec", [C("MethodDefOrRef"), "b"]),
    0x2C: ("GenericParamConstraint", [T(0x2A), C("TypeDefOrRef")]),
}


class Assembly:
    def __init__(self, path):
        self.d = d = open(path, "rb").read()
        pe = struct.unpack_from("<I", d, 0x3C)[0]
        nsec = struct.unpack_from("<H", d, pe + 6)[0]
        optsize = struct.unpack_from("<H", d, pe + 20)[0]
        opt = pe + 24
        magic = struct.unpack_from("<H", d, opt)[0]
        ddir = opt + (96 if magic == 0x10B else 112)
        self.sections = []
        s = opt + optsize
        for i in range(nsec):
            vsize, va, rsize, raw = struct.unpack_from("<IIII", d, s + 40 * i + 8)
            self.sections.append((va, max(vsize, rsize), raw))
        cli_rva = struct.unpack_from("<I", d, ddir + 14 * 8)[0]
        cli = self.off(cli_rva)
        md_rva, md_size = struct.unpack_from("<II", d, cli + 8)
        md = self.off(md_rva)
        vlen = struct.unpack_from("<I", d, md + 12)[0]
        p = md + 16 + vlen + 2
        nstreams = struct.unpack_from("<H", d, p)[0]
        p += 2
        self.streams = {}
        for _ in range(nstreams):
            o, sz = struct.unpack_from("<II", d, p)
            p += 8
            e = d.index(b"\0", p)
            name = d[p:e].decode()
            p = (e + 4) & ~3
            self.streams[name] = (md + o, sz)
        self._tables()

    def off(self, rva):
        for va, size, raw in self.sections:
            if va <= rva < va + size:
                return rva - va + raw
        raise ValueError(hex(rva))

    def _tables(self):
        d = self.d
        base = self.streams["#~"][0] if "#~" in self.streams else self.streams["#-"][0]
        heap = d[base + 6]
        self.wide = {"s": 4 if heap & 1 else 2, "g": 4 if heap & 2 else 2, "b": 4 if heap & 4 else 2}
        valid = struct.unpack_from("<Q", d, base + 8)[0]
        p = base + 24
        self.rows = {}
        for i in range(64):
            if valid >> i & 1:
                self.rows[i] = struct.unpack_from("<I", d, p)[0]
                p += 4

        def width(col):
            if isinstance(col, int):
                return col
            if isinstance(col, str):
                return self.wide[col]
            kind, arg = col
            if kind == "t":
                return 4 if self.rows.get(arg, 0) > 0xFFFF else 2
            bits, tabs = CODED[arg]
            return 4 if max(self.rows.get(t, 0) for t in tabs) >= (1 << (16 - bits)) else 2

        self.tables = {}
        for i in sorted(self.rows):
            name, cols = SCHEMA[i]
            ws = [width(c) for c in cols]
            rows = []
            for _ in range(self.rows[i]):
                r = []
                for c, w in zip(cols, ws):
                    v = struct.unpack_from("<H" if w == 2 else "<I", d, p)[0]
                    p += w
                    if isinstance(c, tuple) and c[0] == "c":
                        bits, tabs = CODED[c[1]]
                        v = (tabs[v & ((1 << bits) - 1)], v >> bits)
                    r.append(v)
                rows.append(r)
            self.tables[name] = rows

    # -- heaps -------------------------------------------------------------

    def string(self, i):
        o = self.streams["#Strings"][0] + i
        return self.d[o:self.d.index(b"\0", o)].decode("utf-8", "replace")

    def blob(self, i):
        o = self.streams["#Blob"][0] + i
        n, k = self._cu(o)
        return self.d[o + k:o + k + n]

    def us(self, i):
        o = self.streams["#US"][0] + i
        n, k = self._cu(o)
        return self.d[o + k:o + k + n - 1].decode("utf-16-le", "replace")

    def _cu(self, o):
        b = self.d[o]
        if b & 0x80 == 0:
            return b, 1
        if b & 0xC0 == 0x80:
            return ((b & 0x3F) << 8) | self.d[o + 1], 2
        return ((b & 0x1F) << 24) | (self.d[o + 1] << 16) | (self.d[o + 2] << 8) | self.d[o + 3], 4

    # -- names -------------------------------------------------------------

    def type_name(self, tab, idx):
        if idx == 0:
            return "?"
        if tab == 0x02:
            return self.string(self.tables["TypeDef"][idx - 1][1])
        if tab == 0x01:
            return self.string(self.tables["TypeRef"][idx - 1][1])
        return f"typespec#{idx}"

    def token(self, tok):
        tab, idx = tok >> 24, tok & 0xFFFFFF
        try:
            if tab == 0x0A:
                parent, name, _ = self.tables["MemberRef"][idx - 1]
                return f"{self.type_name(*parent)}::{self.string(name)}"
            if tab == 0x06:
                return f"{self.owner(idx)}::{self.string(self.tables['MethodDef'][idx - 1][3])}"
            if tab == 0x04:
                return f"{self.field_owner(idx)}::{self.string(self.tables['Field'][idx - 1][1])}"
            if tab in (0x01, 0x02):
                return self.type_name(tab, idx)
            if tab == 0x70:
                return repr(self.us(idx))
        except (IndexError, KeyError):
            pass
        return f"tok {tok:#010x}"

    def _range(self, tname, col, i):
        rows = self.tables["TypeDef"]
        start = rows[i][col]
        end = rows[i + 1][col] if i + 1 < len(rows) else len(self.tables[tname]) + 1
        return start, end

    def owner(self, midx):
        for i, r in enumerate(self.tables["TypeDef"]):
            s, e = self._range("MethodDef", 5, i)
            if s <= midx < e:
                return self.string(r[1])
        return "?"

    def field_owner(self, fidx):
        for i, r in enumerate(self.tables["TypeDef"]):
            s, e = self._range("Field", 4, i)
            if s <= fidx < e:
                return self.string(r[1])
        return "?"

    def type_index(self, name):
        for i, r in enumerate(self.tables["TypeDef"]):
            if self.string(r[1]) == name:
                return i
        raise KeyError(name)

    def methods(self, tname):
        i = self.type_index(tname)
        s, e = self._range("MethodDef", 5, i)
        return [(m, self.tables["MethodDef"][m - 1]) for m in range(s, e)]

    def fields(self, tname):
        i = self.type_index(tname)
        s, e = self._range("Field", 4, i)
        out = []
        for f in range(s, e):
            flags, name, sig = self.tables["Field"][f - 1]
            out.append((f, flags, self.string(name), self.field_init(f)))
        return out

    def field_init(self, f):
        for kind, (tab, idx), blob in self.tables.get("Constant", []):
            if tab == 0x04 and idx == f:
                return self.blob(blob).hex()
        return None

    # -- IL ------------------------------------------------------------------

    def body(self, rva):
        o = self.off(rva)
        h = self.d[o]
        if h & 3 == 2:
            return self.d[o + 1:o + 1 + (h >> 2)]
        size = struct.unpack_from("<I", self.d, o + 4)[0]
        hsize = (struct.unpack_from("<H", self.d, o)[0] >> 12) * 4
        return self.d[o + hsize:o + hsize + size]

    def disassemble(self, rva):
        code = self.body(rva)
        out = []
        p = 0
        while p < len(code):
            start = p
            op = code[p]
            p += 1
            if op == 0xFE:
                op = 0xFE00 | code[p]
                p += 1
            name, kind = OPCODES.get(op, (f"op_{op:x}", ""))
            arg = ""
            if kind == "i1":
                arg = str(struct.unpack_from("<b", code, p)[0]); p += 1
            elif kind == "u1":
                arg = str(code[p]); p += 1
            elif kind == "br1":
                arg = f"IL_{p + 1 + struct.unpack_from('<b', code, p)[0]:04x}"; p += 1
            elif kind == "i4":
                arg = str(struct.unpack_from("<i", code, p)[0]); p += 4
            elif kind == "br4":
                arg = f"IL_{p + 4 + struct.unpack_from('<i', code, p)[0]:04x}"; p += 4
            elif kind == "i8":
                arg = str(struct.unpack_from("<q", code, p)[0]); p += 8
            elif kind == "r4":
                arg = repr(struct.unpack_from("<f", code, p)[0]); p += 4
            elif kind == "r8":
                arg = repr(struct.unpack_from("<d", code, p)[0]); p += 8
            elif kind == "tok":
                arg = self.token(struct.unpack_from("<I", code, p)[0]); p += 4
            elif kind == "u2":
                arg = str(struct.unpack_from("<H", code, p)[0]); p += 2
            elif kind == "sw":
                n = struct.unpack_from("<I", code, p)[0]; p += 4
                targets = struct.unpack_from(f"<{n}i", code, p); p += 4 * n
                arg = ", ".join(f"IL_{p + t:04x}" for t in targets)
            out.append(f"IL_{start:04x}: {name} {arg}".rstrip())
        return out


def _opcodes():
    t = {}
    simple = ("nop break ldarg.0 ldarg.1 ldarg.2 ldarg.3 ldloc.0 ldloc.1 ldloc.2 ldloc.3 "
              "stloc.0 stloc.1 stloc.2 stloc.3").split()
    for i, n in enumerate(simple):
        t[i] = (n, "")
    for op, n in ((0x0E, "ldarg.s"), (0x0F, "ldarga.s"), (0x10, "starg.s"), (0x11, "ldloc.s"),
                  (0x12, "ldloca.s"), (0x13, "stloc.s")):
        t[op] = (n, "u1")
    t[0x14] = ("ldnull", "")
    for i in range(9):
        t[0x16 + i] = (f"ldc.i4.{i}" if i < 9 else "", "")
    t[0x15] = ("ldc.i4.m1", "")
    t[0x1F] = ("ldc.i4.s", "i1"); t[0x20] = ("ldc.i4", "i4"); t[0x21] = ("ldc.i8", "i8")
    t[0x22] = ("ldc.r4", "r4"); t[0x23] = ("ldc.r8", "r8")
    t[0x25] = ("dup", ""); t[0x26] = ("pop", ""); t[0x27] = ("jmp", "tok")
    t[0x28] = ("call", "tok"); t[0x29] = ("calli", "tok"); t[0x2A] = ("ret", "")
    br = "br brfalse brtrue beq bge bgt ble blt bne.un bge.un bgt.un ble.un blt.un".split()
    for i, n in enumerate(br):
        t[0x2B + i] = (n + ".s", "br1")
        t[0x38 + i] = (n, "br4")
    t[0x45] = ("switch", "sw")
    for i, n in enumerate("ldind.i1 ldind.u1 ldind.i2 ldind.u2 ldind.i4 ldind.u4 ldind.i8 ldind.i "
                          "ldind.r4 ldind.r8 ldind.ref stind.ref stind.i1 stind.i2 stind.i4 stind.i8 "
                          "stind.r4 stind.r8 add sub mul div div.un rem rem.un and or xor shl shr "
                          "shr.un neg not conv.i1 conv.i2 conv.i4 conv.i8 conv.r4 conv.r8 conv.u4 "
                          "conv.u8".split()):
        t[0x46 + i] = (n, "")
    t[0x6F] = ("callvirt", "tok"); t[0x70] = ("cpobj", "tok"); t[0x71] = ("ldobj", "tok")
    t[0x72] = ("ldstr", "tok"); t[0x73] = ("newobj", "tok"); t[0x74] = ("castclass", "tok")
    t[0x75] = ("isinst", "tok"); t[0x76] = ("conv.r.un", ""); t[0x79] = ("unbox", "tok")
    t[0x7A] = ("throw", "")
    for op, n in ((0x7B, "ldfld"), (0x7C, "ldflda"), (0x7D, "stfld"), (0x7E, "ldsfld"),
                  (0x7F, "ldsflda"), (0x80, "stsfld"), (0x81, "stobj"), (0x8C, "box"),
                  (0x8D, "newarr"), (0x8F, "ldelema"), (0xA3, "ldelem"), (0xA4, "stelem"),
                  (0xA5, "unbox.any"), (0xC2, "refanyval"), (0xC6, "mkrefany"), (0xD0, "ldtoken")):
        t[op] = (n, "tok")
    t[0x8E] = ("ldlen", "")
    for i, n in enumerate("ldelem.i1 ldelem.u1 ldelem.i2 ldelem.u2 ldelem.i4 ldelem.u4 ldelem.i8 "
                          "ldelem.i ldelem.r4 ldelem.r8 ldelem.ref stelem.i stelem.i1 stelem.i2 "
                          "stelem.i4 stelem.i8 stelem.r4 stelem.r8 stelem.ref".split()):
        t[0x90 + i] = (n, "")
    for op, n in ((0x82, "conv.ovf.i1.un"), (0x8B, "conv.ovf.u.un"), (0xB3, "conv.ovf.i1"),
                  (0xC3, "ckfinite"), (0xD1, "conv.u2"), (0xD2, "conv.u1"), (0xD3, "conv.i"),
                  (0xD4, "conv.ovf.i"), (0xD5, "conv.ovf.u"), (0xD6, "add.ovf"),
                  (0xD7, "add.ovf.un"), (0xD8, "mul.ovf"), (0xD9, "mul.ovf.un"),
                  (0xDA, "sub.ovf"), (0xDB, "sub.ovf.un"), (0xDC, "endfinally"),
                  (0xDF, "stind.i"), (0xE0, "conv.u")):
        t[op] = (n, "")
    t[0xDD] = ("leave", "br4"); t[0xDE] = ("leave.s", "br1")
    for op, (n, k) in {0x00: ("arglist", ""), 0x01: ("ceq", ""), 0x02: ("cgt", ""),
                       0x03: ("cgt.un", ""), 0x04: ("clt", ""), 0x05: ("clt.un", ""),
                       0x06: ("ldftn", "tok"), 0x07: ("ldvirtftn", "tok"), 0x09: ("ldarg", "u2"),
                       0x0A: ("ldarga", "u2"), 0x0B: ("starg", "u2"), 0x0C: ("ldloc", "u2"),
                       0x0D: ("ldloca", "u2"), 0x0E: ("stloc", "u2"), 0x0F: ("localloc", ""),
                       0x11: ("endfilter", ""), 0x12: ("unaligned.", "u1"), 0x13: ("volatile.", ""),
                       0x14: ("tail.", ""), 0x15: ("initobj", "tok"), 0x16: ("constrained.", "tok"),
                       0x17: ("cpblk", ""), 0x18: ("initblk", ""), 0x1A: ("rethrow", ""),
                       0x1C: ("sizeof", "tok"), 0x1D: ("refanytype", "")}.items():
        t[0xFE00 | op] = (n, k)
    return t


OPCODES = _opcodes()


if __name__ == "__main__":
    a = Assembly(sys.argv[1])
    if len(sys.argv) == 2:
        for r in a.tables["TypeDef"]:
            print(a.string(r[2]) + ("." if a.string(r[2]) else "") + a.string(r[1]))
    elif len(sys.argv) == 3:
        for f, flags, name, init in a.fields(sys.argv[2]):
            print(f"field {name} flags={flags:#x}" + (f" = {init}" if init else ""))
        for m, r in a.methods(sys.argv[2]):
            print(f"method {a.string(r[3])} rva={r[0]:#x}")
    else:
        for m, r in a.methods(sys.argv[2]):
            if a.string(r[3]) == sys.argv[3]:
                print("\n".join(a.disassemble(r[0]) if r[0] else ["(no body)"]))
