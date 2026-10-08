"""列出某个类的全部方法，并把指定方法的 invoke 目标打出来（定位「谁负责通知 UI」用）。

用法：
    python reference/dexcode.py <apk> <类名如 Lcom/tencent/wetype/plugin/hld/model/i0;> [方法名...]
不传方法名时只列方法表。
"""
import struct, sys, zipfile
from dexfind import Dex

# opcode -> 指令占多少个 16-bit code unit
WIDTH = {}
for op in range(0x00, 0x100):
    WIDTH[op] = 1
# move/from16 系
for op in (0x02, 0x05, 0x08):
    WIDTH[op] = 2
# move/16 系
for op in (0x03, 0x06, 0x09):
    WIDTH[op] = 3
# const/16 系
for op in (0x13, 0x16, 0x19):
    WIDTH[op] = 2
# const 系
for op in (0x14, 0x17, 0x1a):
    WIDTH[op] = 3
# const/high16 系
for op in (0x15, 0x18, 0x1b):
    WIDTH[op] = 2
WIDTH[0x23] = WIDTH[0x24] = 2
WIDTH[0x25] = WIDTH[0x26] = WIDTH[0x27] = 3
WIDTH[0x2a] = 2
WIDTH[0x2b] = 3
for op in (0x2c, 0x2d, 0x2e, 0x2f, 0x30, 0x31):
    WIDTH[op] = 3
for op in range(0x32, 0x44):  # 23x / 22t / 21t
    WIDTH[op] = 2
for op in range(0x44, 0x6e):  # 23x / 22c / 21c
    WIDTH[op] = 2
for op in range(0x6e, 0x73):
    WIDTH[op] = 3
for op in range(0x74, 0x79):
    WIDTH[op] = 3
for op in range(0x90, 0xb0):
    WIDTH[op] = 2
for op in range(0xd0, 0xe3):
    WIDTH[op] = 2
WIDTH[0xfa] = WIDTH[0xfb] = 4
WIDTH[0xfc] = WIDTH[0xfd] = 3
WIDTH[0xfe] = WIDTH[0xff] = 2


def find_class(d, name):
    for i in range(d.class_count):
        off = d.class_defs_off + 32 * i
        cidx = struct.unpack_from('<I', d.b, off)[0]
        if d.type_name(cidx) == name:
            return off
    return None


def class_methods(d, class_def_off):
    """返回 [(method_idx, access, code_off, name, proto)]，按 dex 里的顺序（direct 在前、virtual 在后）"""
    class_data_off = struct.unpack_from('<I', d.b, class_def_off + 24)[0]
    if not class_data_off:
        return []
    p = class_data_off
    sf, p = d.uleb(p)
    inf, p = d.uleb(p)
    dm, p = d.uleb(p)
    vm, p = d.uleb(p)
    for _ in range(sf):
        _, p = d.uleb(p); _, p = d.uleb(p)
    for _ in range(inf):
        _, p = d.uleb(p); _, p = d.uleb(p)
    out = []
    for kind, n in (('direct', dm), ('virtual', vm)):
        midx = 0
        for _ in range(n):
            diff, p = d.uleb(p); midx += diff
            access, p = d.uleb(p)
            code_off, p = d.uleb(p)
            _, name_idx, proto_idx = d.method(midx)
            out.append((midx, access, code_off, kind, d.string(name_idx), d.proto_desc(proto_idx)))
    return out


def invoked_methods(d, code_off):
    """把 code_item 里的 invoke-* 目标按出现顺序解出来"""
    if not code_off:
        return []
    insns_size = struct.unpack_from('<I', d.b, code_off + 12)[0]
    base = code_off + 16
    out = []
    i = 0
    while i < insns_size:
        code = struct.unpack_from('<H', d.b, base + 2 * i)[0]
        op = code & 0xff
        hi = code >> 8
        if op == 0x00 and hi != 0:
            # payload
            if hi == 0x01:  # packed-switch-payload
                size = struct.unpack_from('<H', d.b, base + 2 * (i + 1))[0]
                i += (size * 2) + 4
            elif hi == 0x02:  # sparse-switch-payload
                size = struct.unpack_from('<H', d.b, base + 2 * (i + 1))[0]
                i += (size * 4) + 2
            elif hi == 0x03:  # fill-array-data-payload
                size = struct.unpack_from('<I', d.b, base + 2 * (i + 1))[0]
                ew = struct.unpack_from('<H', d.b, base + 2 * (i + 3))[0]
                i += (size * ew + 1) // 2 + 4
            else:
                i += 1
            continue
        if 0x6e <= op <= 0x72 or 0x74 <= op <= 0x78 or op == 0xfc or op == 0xfd:
            midx = struct.unpack_from('<H', d.b, base + 2 * (i + 1))[0]
            _, name_idx, proto_idx = d.method(midx)
            cls_idx = d.method(midx)[0]
            out.append((d.type_name(cls_idx), d.string(name_idx), d.proto_desc(proto_idx)))
        elif 0x52 <= op <= 0x6d:
            # iget/iput/sget/sput：22c / 21c，字段索引在第二个 code unit
            fidx = struct.unpack_from('<H', d.b, base + 2 * (i + 1))[0]
            fc, ft, fn = d.field(fidx)
            kind = '写字段' if op in (0x59, 0x5a, 0x5b, 0x5c, 0x5d, 0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c, 0x6d) else '读字段'
            out.append((d.type_name(fc), f'{kind}:{d.string(fn)}', d.type_name(ft)))
        i += WIDTH.get(op, 1)
    return out


def main():
    apk, cls = sys.argv[1], sys.argv[2]
    wanted = sys.argv[3:]
    z = zipfile.ZipFile(apk)
    names = [n for n in z.namelist() if n.startswith('classes') and n.endswith('.dex')]
    for n in names:
        d = Dex(z.read(n))
        off = find_class(d, cls)
        if off is None:
            continue
        print(f'== {cls} 在 {n}')
        ms = class_methods(d, off)
        if not wanted:
            for midx, access, code_off, kind, name, proto in ms:
                print(f'   [{kind}] {name}{proto}   code={"有" if code_off else "无"}')
            return
        for midx, access, code_off, kind, name, proto in ms:
            if name not in wanted:
                continue
            print(f'-- {name}{proto}')
            for c, mn, mp in invoked_methods(d, code_off):
                print(f'     {c.split("/")[-1].rstrip(";")}.{mn}{mp}')
        return
    print(f'没找到 {cls}')


if __name__ == '__main__':
    main()
