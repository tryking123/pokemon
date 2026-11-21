"""研究员局部供给按钮及原888石板邮件显示补丁。"""
from pathlib import Path
from collections import Counter
import re


def append_tablet_protocol(schema):
    """在已应用其他本地协议的客户端源模式中加入唯一10993号。"""
    assert b"local_tablet_unlock_supply" not in schema
    assert not re.search(rb"(?m)^\s*\w+\s+10993\s*\{", schema)
    return schema + b"\nlocal_tablet_unlock_supply 10993 {\n request {\n action 0 : integer\n }\n response {\n errno 0 : integer\n message 1 : string\n }\n}\n"


def patch_tablet_lua(source, mode, Reader, encode, constants, serial_constant, pack, ins):
    """复用主补丁的双架构字节码解析与序列化接口。"""
    cur = Reader(source)
    assert cur.raw(4) == b"\x1bLJ\x02"
    flags = cur.num()
    assert flags & 2 and not flags & 1
    argument = 2 if flags & 8 else 1
    output = bytearray(source[:cur.pos])
    count = 0
    index = 0
    while size := cur.num():
        proto = Reader(cur.raw(size))
        head = proto.raw(4)
        nconstants, nnumbers, nop = [proto.num() for _ in range(3)]
        code = proto.raw(nop * 4)
        variables = proto.raw(head[3] * 2)
        pool = constants(proto, nconstants)
        matched = (mode == "tablet_npc" and index == 25) or (
            mode == "tablet_helper" and pack(b"parse_reward") in pool and head[1] == 0
            and code[-4:] == ins("RET1", 0, 2))
        if matched:
            key = len(pool)
            filename = "tablet-supply-menu.lua" if mode == "tablet_npc" else "tablet-mail-helper.lua"
            script = Path(__file__).with_name(filename).read_text("utf-8-sig").encode()
            pool = [pack(script), pack(b"loadstring")] + pool
            base = 4 if mode == "tablet_npc" else head[2]
            target = base + argument
            injected = (ins("GGET", base, key) + ins("KSTR", target, key + 1)
                        + ins("CALL", base, 514) + ins("MOV", target, 0))
            if mode == "tablet_npc":
                assert nop == 37 and code[31 * 4:32 * 4] == ins("MOV", 4, 0)
                injected += ins("MOV", target + 1, 1) + ins("CALL", base, 259)
                code = code[:31 * 4] + injected + code[31 * 4:]
            else:
                injected += ins("CALL", base, 258)
                code = code[:-4] + injected + code[-4:]
            head = head[:2] + bytes([max(head[2], target + 2)]) + head[3:]
            count += 1
        body = (head + encode(len(pool)) + encode(nnumbers) + encode(len(code) // 4) + code + variables
                + b"".join(serial_constant(item) for item in pool) + proto.raw(len(proto.data) - proto.pos))
        output.extend(encode(len(body)) + body)
        index += 1
    assert count == 1, (mode, count)
    output.extend(b"\0")
    assert cur.pos == len(source)
    return bytes(output), Counter({mode: count})
