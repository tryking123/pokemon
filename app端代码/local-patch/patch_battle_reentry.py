"""修复主线不同战斗在已打开页面中重入而沿用旧 Stage 的问题。"""
from pathlib import Path


def patch_battle_reentry(source, helpers):
    """仅替换原 battle_module.start，沿用原异步页面生命周期。"""
    reader = helpers.Reader(source)
    assert reader.raw(4) == b'\x1bLJ\x02'
    flags = reader.num()
    assert flags & 2 and not flags & 1
    argument = 2 if flags & 8 else 1
    output = bytearray(source[:reader.pos])
    text = Path(__file__).with_name('local-adven-reentry.lua').read_bytes()
    changed = 0
    index = 0
    while size := reader.num():
        proto = helpers.Reader(reader.raw(size))
        head = proto.raw(4)
        nk, nn, count = [proto.num() for _ in range(3)]
        code = proto.raw(count * 4)
        variables = proto.raw(head[3] * 2)
        pool = helpers.constants(proto, nk)
        numbers = proto.data[proto.pos:]
        if index == 2:
            assert head[1] == 2 and head[3] == 2
            marker = b'owner._offline_adven_attempt'
            existing = [i for i, value in enumerate(pool) if isinstance(value, bytes) and marker in value]
            if existing:
                assert len(existing) == 1
                pool[existing[0]] = helpers.pack(text)
            else:
                assert helpers.pack(b'set_data') in pool and helpers.pack(b'set_type') in pool
                key = len(pool)
                pool = [helpers.pack(text), helpers.pack(b'loadstring')] + pool
                register = head[2]
                first = register + argument
                ins = helpers.ins
                code = (ins('GGET', register, key) + ins('KSTR', first, key + 1)
                        + ins('CALL', register, 514) + ins('MOV', first, 0)
                        + ins('MOV', first + 1, 1) + ins('UGET', first + 2, 1)
                        + ins('UGET', first + 3, 0) + ins('CALL', register, 261)
                        + ins('RET0', 0, 1))
                head = head[:2] + bytes([first + 4]) + head[3:]
            changed += 1
        block = (head + helpers.encode(len(pool)) + helpers.encode(nn)
                 + helpers.encode(len(code) // 4) + code + variables
                 + b''.join(map(helpers.serial_constant, pool)) + numbers)
        output += helpers.encode(len(block)) + block
        index += 1
    assert changed == 1 and reader.pos == len(source)
    output += b'\0'
    return bytes(output)
