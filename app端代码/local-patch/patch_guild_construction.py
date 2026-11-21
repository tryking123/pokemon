"""按原公会建设界面字节码保留绘制，仅将限次文案改为不限次数。"""
from collections import Counter
import struct


def patch_guild_build(source, Reader, encode, constants, serial_constant, pack, ins):
    """复用主补丁序列化器，原数字格式化调用仅替换为常量文本。"""
    cur = Reader(source)
    assert cur.raw(4) == b'\x1bLJ\x02'
    flags = cur.num()
    assert flags & 2 and not flags & 1
    patched = bytearray(source[:cur.pos])
    count = 0
    index = 0
    while size := cur.num():
        proto = Reader(cur.raw(size))
        head = proto.raw(4)
        nconstants, nnumbers, nop = [proto.num() for _ in range(3)]
        code = proto.raw(nop * 4)
        uv = proto.raw(head[3] * 2)
        pool = constants(proto, nconstants)
        suffix = proto.raw(len(proto.data) - proto.pos)
        if index == 3:
            assert any(x == pack(b'bulid_number') for x in pool)
            assert any(x == pack(b'number') for x in pool)
            call, assign, ret = struct.unpack('<III', code[-12:])
            register = assign >> 8 & 255
            assert call & 255 == 66 and assign & 255 == 61 and ret & 255 == 75
            assert call >> 8 & 255 == register
            code = code[:-12] + ins('KSTR', register, len(pool)) + code[-8:]
            pool = [pack('不限次数'.encode())] + pool
            count += 1
        body = (head + encode(len(pool)) + encode(nnumbers) + encode(nop) + code + uv
                + b''.join(serial_constant(x) for x in pool) + suffix)
        patched += encode(len(body)) + body
        index += 1
    patched += b'\0'
    assert count == 1 and index == 8
    return bytes(patched), Counter(guild_unlimited_build=count)
