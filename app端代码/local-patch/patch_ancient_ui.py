"""为双架构超古代原材料入口接入自动选材。"""
from pathlib import Path
from patch_miao_battle import arena, body, parse


def patch_ancient_select(source, helpers=None):
    """每次打开原材料页均重新选材，保留原确认与升级协议。"""
    prefix, flags, protos = parse(source)
    start, root = protos[13], protos[16]
    lua = Path(__file__).with_name("local-ancient-select.lua").read_bytes()
    marker = b"local view, controls, cards, eyes"
    pool = arena.constants(arena.Reader(start["pool"]), start["ngc"])
    found = [i for i, value in enumerate(pool) if isinstance(value, bytes) and marker in value]
    if found:
        assert len(found) == 1
        base = len(pool) - 2 - found[0]
        pool[found[0]] = arena.pack(lua)
        start["pool"] = b"".join(map(arena.serial_constant, pool))
        start["code"] = start["code"][:114*4] + arena.ins("RET0", 0, 1)
    else:
        assert start["head"][3] == 7 and start["uv"][:6] == bytes.fromhex("0280038001c0")
        assert arena.pack(b"BG/Sure") in start["pool"]
        base = start["ngc"]
        start["pool"] = arena.pack(lua) + arena.pack(b"loadstring") + start["pool"]
        start["ngc"] += 2
    assert root["code"][34*4:37*4] == (arena.ins("FNEW", 20, 16)
        + arena.ins("FNEW", 21, 17) + arena.ins("FNEW", 22, 18))
    start["uv"] = start["uv"][:14] + bytes.fromhex("15c016c014c0")
    register = 14
    first = register + (2 if flags & 8 else 1)
    ins = arena.ins
    hook = (ins("GGET", register, base) + ins("KSTR", first, base + 1)
            + ins("CALL", register, 514) + ins("UGET", first, 2)
            + ins("UGET", first + 1, 0) + ins("UGET", first + 2, 7)
            + ins("UGET", first + 3, 8) + ins("UGET", first + 4, 9)
            + ins("CALL", register, 262))
    assert start["code"][-4:] == ins("RET0", 0, 1)
    start["code"] = start["code"][:-4] + hook + start["code"][-4:]
    start["head"] = start["head"][:2] + bytes([first + 5, 10])
    blocks = [body(proto) for proto in protos]
    return prefix + b"".join(arena.encode(len(block)) + block for block in blocks) + b"\0"
