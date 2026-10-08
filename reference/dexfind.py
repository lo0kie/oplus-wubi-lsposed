"""从 dex 里按方法名反查它所属的类（读 string_ids / type_ids / method_ids）。"""
import struct, sys, zipfile

class Dex:
    def __init__(self, b):
        self.b = b
        string_ids_size, string_ids_off = struct.unpack_from('<II', b, 56)
        type_ids_size, type_ids_off = struct.unpack_from('<II', b, 64)
        proto_ids_size, proto_ids_off = struct.unpack_from('<II', b, 72)
        field_ids_size, field_ids_off = struct.unpack_from('<II', b, 80)
        method_ids_size, method_ids_off = struct.unpack_from('<II', b, 88)
        class_defs_size, class_defs_off = struct.unpack_from('<II', b, 96)
        self.string_ids_off = string_ids_off
        self.string_count = string_ids_size
        self.type_ids = [struct.unpack_from('<I', b, type_ids_off + 4*i)[0] for i in range(type_ids_size)]
        self.proto_ids_off = proto_ids_off
        self.proto_count = proto_ids_size
        self.field_ids_off = field_ids_off
        self.field_count = field_ids_size
        self.method_ids_off = method_ids_off
        self.method_count = method_ids_size
        self.class_defs_off = class_defs_off
        self.class_count = class_defs_size

    def string(self, idx):
        off = struct.unpack_from('<I', self.b, self.string_ids_off + 4*idx)[0]
        # uleb128 长度
        p = off
        size = 0; shift = 0
        while True:
            c = self.b[p]; p += 1
            size |= (c & 0x7f) << shift
            if not (c & 0x80): break
            shift += 7
        end = self.b.index(b'\x00', p)
        return self.b[p:end].decode('utf-8', 'replace')

    def type_name(self, idx):
        return self.string(self.type_ids[idx])

    def uleb(self, off):
        b = self.b; val = 0; shift = 0
        while True:
            c = b[off]; off += 1
            val |= (c & 0x7f) << shift
            if not (c & 0x80): break
            shift += 7
        return val, off

    def method(self, idx):
        """返回 (class_idx, name_idx, proto_idx)"""
        off = self.method_ids_off + 8*idx
        class_idx, proto_idx, name_idx = struct.unpack_from('<HHI', self.b, off)
        return class_idx, name_idx, proto_idx

    def field(self, idx):
        off = self.field_ids_off + 8*idx
        class_idx, type_idx, name_idx = struct.unpack_from('<HHI', self.b, off)
        return class_idx, type_idx, name_idx

    def proto(self, idx):
        off = self.proto_ids_off + 12*idx
        return struct.unpack_from('<III', self.b, off)  # shorty_idx, return_type_idx, params_off

    def find_methods_by_name(self, name):
        hits = []
        for i in range(self.method_count):
            _, name_idx, proto_idx = self.method(i)
            if self.string(name_idx) == name:
                cls_idx = self.method(i)[0]
                hits.append((self.type_name(cls_idx), self.proto_desc(proto_idx)))
        return hits

    def proto_desc(self, idx):
        _, ret_idx, params_off = self.proto(idx)
        params = []
        if params_off:
            size = struct.unpack_from('<I', self.b, params_off)[0]
            for i in range(size):
                t = struct.unpack_from('<H', self.b, params_off + 4 + 2*i)[0]
                params.append(self.type_name(t))
        return '(' + ', '.join(params) + ') -> ' + self.type_name(ret_idx)

    def class_fields(self, class_idx):
        """列出这个类声明的字段（含静态/实例、类型名）"""
        want = self.type_name(class_idx)
        out = []
        for i in range(self.class_count):
            off = self.class_defs_off + 32*i
            cidx = struct.unpack_from('<I', self.b, off)[0]
            if self.type_name(cidx) != want: continue
            class_data_off = struct.unpack_from('<I', self.b, off + 24)[0]
            if not class_data_off: return out
            p = class_data_off
            sf, p = self.uleb(p); inf, p = self.uleb(p)
            dm, p = self.uleb(p); vm, p = self.uleb(p)
            for _ in range(sf):
                _, p = self.uleb(p); _, p = self.uleb(p)
            for _ in range(inf):
                _, p = self.uleb(p); _, p = self.uleb(p)
            for kind, n in (('static', dm), ('instance', vm)):
                fidx = 0
                for _ in range(n):
                    diff, p = self.uleb(p); fidx += diff
                    _, p = self.uleb(p)
                    fc, ft, fn = self.field(fidx)
                    out.append((kind, self.string(fn), self.type_name(ft)))
            return out
        return out

def load(apk, dexname):
    z = zipfile.ZipFile(apk)
    return Dex(z.read(dexname))

if __name__ == '__main__':
    apk = sys.argv[1]
    dexname = sys.argv[2]
    target = sys.argv[3]
    d = load(apk, dexname)
    hits = d.find_methods_by_name(target)
    print(f'== 方法 {target} 命中 {len(hits)} 处')
    for cls, proto in hits:
        print(f'   {cls}  {proto}')
