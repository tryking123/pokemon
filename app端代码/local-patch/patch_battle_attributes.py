"""在新战斗 Stage 完成准备及页面绑定后安装只读属性快照。"""
from pathlib import Path


def patch_battle_attributes(source, h):
    """保留原控制器，只在 view_start 的末尾接入一次。"""
    reader = h.Reader(source)
    assert reader.raw(4) == b'\x1bLJ\x02'
    flags = reader.num()
    assert flags & 2 and not flags & 1
    spacing = 2 if flags & 8 else 1
    out = bytearray(source[:reader.pos])
    script = Path(__file__).with_name('local-battle-attributes.lua').read_bytes()
    index = matched = 0
    while size := reader.num():
        raw = reader.raw(size)
        if index == 14:
            p = h.Reader(raw)
            head = bytearray(p.raw(4))
            nk, nn, count = (p.num() for _ in range(3))
            code = p.raw(count * 4)
            uv = p.raw(head[3] * 2)
            pool = h.constants(p, nk)
            numbers = p.data[p.pos:]
            slots = [i for i, v in enumerate(pool) if isinstance(v, bytes)
                     and b'local_battle_attributes' in v]
            if slots:
                assert len(slots) == 1
                pool[slots[0]] = h.pack(script)
            else:
                assert head[1] == 0 and head[3] == 3
                assert h.pack(b'prepare') in pool and h.pack(b'setdata') in pool
                assert code[-4:] == h.ins('RET0', 0, 1)
                register = head[2]
                first = register + spacing
                pool = [h.pack(script), h.pack(b'loadstring')] + pool
                code = code[:-4] + (h.ins('GGET', register, nk)
                    + h.ins('KSTR', first, nk + 1) + h.ins('CALL', register, 514)
                    + h.ins('UGET', first, 0) + h.ins('UGET', first + 1, 1)
                    + h.ins('CALL', register, 259) + h.ins('RET0', 0, 1))
                head[2] = first + 2
            raw = (head + h.encode(len(pool)) + h.encode(nn) + h.encode(len(code)//4)
                   + code + uv + b''.join(map(h.serial_constant, pool)) + numbers)
            matched += 1
        out += h.encode(len(raw)) + raw
        index += 1
    assert matched == 1 and reader.pos == len(source)
    return bytes(out) + b'\0'
