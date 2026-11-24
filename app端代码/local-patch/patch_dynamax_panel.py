"""修正双架构极巨化页面的满阶费用展示并接入原镶嵌窗口。"""
from pathlib import Path
from patch_miao_battle import arena, body, parse


def patch_dynamax_panel(source):
    """在原路线图标刷新后更新交互，满阶直接跳到属性刷新。"""
    prefix, flags, protos = parse(source)
    refresh = protos[5]
    script = Path(__file__).with_name("local-dynamax-panel.lua").read_bytes()
    if arena.pack(script) in refresh["pool"]:
        return source

    # 已核对底包：第 5 个子函数绘制费用，第 8 个子函数持有当前精灵。
    assert refresh["uv"].startswith(bytes.fromhex("0780")) and refresh["head"][3] == 7
    assert protos[8]["uv"][4:6] == bytes.fromhex("0880")
    ins = arena.ins
    code = refresh["code"]
    start, properties = 24, 89
    assert code[start*4:(start+1)*4] == ins("KPRI", 3, 0)
    assert code[properties*4:(properties+1)*4] == ins("UGET", 8, 6)
    register = refresh["head"][2]
    arg = register + (2 if flags & 8 else 1)
    base = refresh["ngc"]
    hook = (ins("GGET", register, base) + ins("KSTR", arg, base + 1)
            + ins("CALL", register, 514) + ins("UGET", arg, 0) + ins("UGET", arg + 1, 7)
            + ins("CALL", register, 515) + ins("IST", 0, register)
            + ins("JMP", register, 32768 + properties - start))
    refresh["code"] = code[:start*4] + hook + code[start*4:]
    refresh["uv"] += protos[8]["uv"][4:6]
    refresh["head"] = refresh["head"][:2] + bytes([arg + 2, 8])
    refresh["pool"] = arena.pack(script) + arena.pack(b"loadstring") + refresh["pool"]
    refresh["ngc"] += 2
    blocks = [body(proto) for proto in protos]
    return prefix + b"".join(arena.encode(len(block)) + block for block in blocks) + b"\0"
