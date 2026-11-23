"""保护无可显示主线时的任务门槛和导航刷新，保留原任务比较和点击处理。"""
from pathlib import Path
from patch_miao_battle import arena, body, parse


def jump(pc, target):
    return arena.ins("JMP", 0, 32768 + target - pc - 1)


def assemble(prefix, prototypes):
    blocks = [body(proto) for proto in prototypes]
    return prefix + b"".join(arena.encode(len(block)) + block for block in blocks) + b"\0"


def patch_task_guides(source):
    """主线推送只刷新进度。主动点击时使用正常导航，不自动执行业务动作。"""
    prefix, flags, protos = parse(source)
    auto, track = protos[0], protos[17]
    ins = arena.ins
    entry = Path(__file__).with_name("local-quest-route.lua").read_bytes()
    # 原 S101 专用处理仅按固定的二十个主线编号打开 guide。
    assert arena.pack(b"guide") in auto["pool"] and arena.pack(b"isopen") in auto["pool"]
    auto["code"] = ins("RET0", 0, 1)
    marker = b"local quest, task_id = ..."
    if marker in track["pool"]:
        pool = arena.constants(arena.Reader(track["pool"]), track["ngc"])
        assert marker in pool[0]
        pool[0] = arena.pack(entry)
        track["pool"] = b"".join(map(arena.serial_constant, pool))
    else:
        # 原第16条指令已经从 mission_proto 取得目标配置，且可领取任务已经返回。
        assert track["code"][64:68] == ins("CALL", 2, 515)
        pool = list(reversed(arena.constants(arena.Reader(track["pool"]), track["ngc"])))
        type_key = pool.index(arena.pack(b"type"))
        first = 5 if flags & 8 else 4
        base = track["ngc"]
        track["pool"] = arena.pack(entry) + arena.pack(b"loadstring") + track["pool"]
        track["ngc"] += 2
        route = (ins("GGET", 3, base) + ins("KSTR", first, base+1)
                 + ins("CALL", 3, 514) + ins("MOV", first, 2) + ins("MOV", first+1, 0)
                 + ins("CALL", 3, 515) + ins("ISF", 0, 3)
                 + ins("JMP", 3, 32769) + ins("RET0", 0, 1))
        check = (ins("TGETS", 3, 512 + type_key) + ins("KSHORT", 4, 1)
                 + ins("ISNEV", 3, 4) + ins("JMP", 3, 32768 + len(route)//4))
        track["code"] = track["code"][:68] + check + route + track["code"][68:]
    return assemble(prefix, protos)


def patch_task_lock(source):
    """用同一角色最近一次成功提交的主线编号衔接领奖和下次推送。"""
    prefix, _, protos = parse(source)
    sync, submit, lock = (protos[i] for i in (4, 6, 21))
    marker = arena.pack(b"_offline_last_main_claim")
    if marker in lock["pool"]:
        assert marker in sync["pool"] and marker in submit["pool"]
        return source
    ins = arena.ins
    for p in (sync, submit, lock):
        p["pool"] = marker + p["pool"]
        p["ngc"] += 1

    # 完整快照重置水位，防止切换角色继承之前角色的状态。
    assert sync["uv"][12:14] == b"\x00\xc0"
    sync["code"] = (ins("UGET", 1, 6) + ins("KPRI", 2, 0)
                    + ins("TSETS", 2, 256 + sync["ngc"] - 1) + sync["code"])
    for p in (submit, lock):
        p["uv"] += b"\x00\xc0"
        p["head"] = p["head"][:3] + bytes([p["head"][3] + 1])

    # 删除任务前，只有主线集合中实际存在的条目才更新水位。
    code = submit["code"]
    assert code[24:40] == ins("UGET", 2, 0) + ins("TGETS", 3, 3) + ins("KPRI", 4, 0) + ins("TSETV", 4, 515)
    end = len(code) // 4
    submit["code"] = (code[:32] + jump(8, end) + code[36:]
                      + ins("TGETV", 4, 515) + ins("ISF", 0, 4) + jump(end+2, end+5)
                      + ins("UGET", 4, 3) + ins("TSETS", 3, 4*256 + submit["ngc"]-1)
                      + ins("KPRI", 4, 0) + jump(end+6, 9))

    # 原列表非空时仍严格比较 threshold < current.id。尚未同步时关闭入口；
    # 已同步且没有在途领奖的空主线是已结束的任务链。
    code = lock["code"]
    assert code[64:72] == ins("TGETB", 2, 257) + ins("TGETS", 2, 515)
    end = len(code) // 4
    lock["code"] = (code[:68] + jump(17, end) + code[72:]
                    + ins("ISF", 0, 2) + jump(end+1, end+4)
                    + ins("TGETS", 2, 515) + jump(end+3, 18)
                    + ins("UGET", 2, 0) + ins("ISF", 0, 2) + jump(end+6, 20)
                    + ins("UGET", 2, 1) + ins("TGETS", 2, 512 + lock["ngc"]-1)
                    + ins("ISF", 0, 2) + jump(end+10, 22)
                    + ins("ISLE", 0, 2) + jump(end+12, 22) + jump(end+13, 20))
    return assemble(prefix, protos)


def patch_task_navigation(source):
    """无任务时隐藏整个任务按钮，有后继任务时恢复显示和原点击绑定。"""
    prefix, flags, protos = parse(source)
    view = protos[26]
    ins = arena.ins
    code = view["code"]
    if code[48:52] == ins("UGET", 2, 1) and code[12:16] == ins("IST", 0, 0):
        assert code[60:64] == ins("TGETS", 3, 532)
        return source
    assert code[32:52] == (ins("TGETB", 1, 1) + ins("IST", 0, 1) + ins("JMP", 2, 32769)
                            + ins("TNEW", 1, 0) + ins("TGETS", 2, 260))
    first = 1 + bool(flags & 8)
    # 面板可早于 S100 创建。将未初始化的任务集合视为空集合。
    default = ins("IST", 0, 0) + ins("JMP", 1, 32769) + ins("TNEW", 0, 0)
    visibility = (ins("UGET", 2, 1) + ins("TGETS", 2, 518)
                  + ins("MOV", 3 + first, 2) + ins("TGETS", 3, 532)
                  + ins("KPRI", 4 + first, 1) + ins("ISF", 0, 1)
                  + ins("JMP", 3, 32769) + ins("KPRI", 4 + first, 2)
                  + ins("CALL", 3, 259) + ins("IST", 0, 1)
                  + ins("JMP", 2, 32769) + ins("RET0", 0, 1))
    patched = code[:12] + default + code[12:36] + visibility + code[48:]
    view["code"] = patched
    return assemble(prefix, protos)
