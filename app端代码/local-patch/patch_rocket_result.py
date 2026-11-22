"""把火箭队原战斗终局送回进程内业务。"""
from pathlib import Path


def patch_rocket_result(source, h):
    """仅替换 battle_ctrl 的 PEAKED 终局返回，保留其余跳转与寄存器。"""
    reader = h.Reader(source)
    assert reader.raw(4) == b'\x1bLJ\x02'
    arg = 2 if reader.num() & 8 else 1
    out = bytearray(source[:reader.pos])
    handler = Path(__file__).with_name("local-rocket-result.lua").read_bytes()
    changes = 0
    index = 0
    while size := reader.num():
        raw = reader.raw(size)
        if index == 13:
            p = h.Reader(raw)
            head = p.raw(4)
            nk, nn, count = (p.num() for _ in range(3))
            code = bytearray(p.raw(count * 4))
            up = p.raw(head[3] * 2)
            pool = h.constants(p, nk)
            numbers = p.data[p.pos:]
            assert head[1] == 0 and head[3] == 5
            assert h.pack(b"PEAKED") == pool[~5]
            payloads = [i for i, x in enumerate(pool) if isinstance(x, bytes) and b"local_rocket_result" in x]
            if payloads:
                assert len(payloads) == 1
                pool[payloads[0]] = h.pack(handler)
            else:
                at = 22
                assert code[at*4:at*4+4] == h.ins("RET0", 0, 1)
                assert code[(at-3)*4:(at-2)*4] == h.ins("TGETS", 1, 261)
                code[at*4:at*4+4] = h.ins("JMP", 0, 32768 + count - at - 1)
                pool = [h.pack(handler), h.pack(b"loadstring")] + pool
                code += (h.ins("GGET", 0, nk) + h.ins("KSTR", arg, nk + 1)
                         + h.ins("CALL", 0, 514) + h.ins("UGET", arg, 0)
                         + h.ins("UGET", arg + 1, 2) + h.ins("CALL", 0, 259)
                         + h.ins("RET0", 0, 1))
            raw = (head + h.encode(len(pool)) + h.encode(nn) + h.encode(len(code)//4) + code + up
                   + b''.join(map(h.serial_constant, pool)) + numbers)
            changes += 1
        out += h.encode(len(raw)) + raw
        index += 1
    assert changes == 1 and reader.pos == len(source)
    return bytes(out) + b'\0'
