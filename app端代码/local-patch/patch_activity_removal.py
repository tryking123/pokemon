"""关闭用户指定运营活动的原 Lua 入口及红点，保留其他活动和玩家存档。"""
from collections import Counter

REMOVED = (10025, 10037, 10049, 10050, 10051, 10052, 10054, 10055,
           10057, 10067, 10072, 10073, 10080, 10082, 10089)


def patch_activity_removal(source, Reader, encode, constants, serial_constant, pack, ins):
    """在原活动状态及红点查询前增加下架判定，其他分支保留原实现。"""
    cur = Reader(source)
    assert cur.raw(4) == b"\x1bLJ\x02"
    flags = cur.num()
    assert flags & 2 and not flags & 1
    argument = 2 if flags & 8 else 1
    output = bytearray(source[:cur.pos])
    index = 0
    hits = Counter()
    while size := cur.num():
        proto = Reader(cur.raw(size))
        head = proto.raw(4)
        ng, nn, nop = proto.num(), proto.num(), proto.num()
        code = proto.raw(nop * 4)
        variables = proto.raw(head[3] * 2)
        pool = constants(proto, ng)
        if index in (33, 38):
            expected = b"get_operate_data" if index == 33 else b"redpoint"
            assert pack(expected) in pool and head[1] >= 1
            # 仅使用原函数最大寄存器后的临时区，不破坏原参数及捕获变量。
            slot = head[2]
            first = slot + argument
            script = ("local id=tonumber(...); local removed={" +
                      ",".join("["+str(i)+"]=true" for i in REMOVED) +
                      "}; return not removed[id]").encode()
            key = len(pool)
            pool = [pack(script), pack(b"loadstring")] + pool
            guard = (ins("GGET", slot, key) + ins("KSTR", first, key+1)
                     + ins("CALL", slot, 514) + ins("MOV", first, 0)
                     + ins("CALL", slot, 514) + ins("IST", 0, slot)
                     + ins("JMP", first, 0x8002) + ins("KPRI", slot, 1)
                     + ins("RET1", slot, 2))
            code = guard + code
            head = head[:2] + bytes([first+1]) + head[3:]
            hits["activity_state" if index == 33 else "activity_redpoint"] += 1
        body = (head + encode(len(pool)) + encode(nn) + encode(len(code)//4)
                + code + variables + b"".join(map(serial_constant, pool))
                + proto.raw(len(proto.data)-proto.pos))
        output.extend(encode(len(body)) + body)
        index += 1
    assert hits == {"activity_state": 1, "activity_redpoint": 1}
    assert cur.pos == len(source)
    output.append(0)
    return bytes(output), hits


def patch_activity_shortcuts(source, Reader, encode, constants, serial_constant, pack, ins):
    """阻止残留主城快捷跳转直接打开下架模块，普通商城等不受影响。"""
    cur = Reader(source)
    assert cur.raw(4) == b"\x1bLJ\x02"
    flags = cur.num()
    assert flags & 2 and not flags & 1
    argument = 2 if flags & 8 else 1
    output = bytearray(source[:cur.pos])
    index = hits = 0
    while size := cur.num():
        proto = Reader(cur.raw(size))
        head = proto.raw(4)
        ng, nn, nop = proto.num(), proto.num(), proto.num()
        code = proto.raw(nop*4)
        variables = proto.raw(head[3]*2)
        pool = constants(proto, ng)
        if index == 6:
            assert head[1] == 4 and pack(b"UIWindow") in pool and pack(b"open") in pool
            slot = head[2]
            first = slot + argument
            script = b"""local name=...;local modules=modulename or {};local keys={'fespumpkin','feschristmas','feskylin','fesinvest','fesdirectlygift','fesfirstcharge3','everydaypoint','fesclockin','feswishing','fessnapup','fesfirstcharge2','fesluxury','feslimitshop'};for _,key in ipairs(keys) do if modules[key] and modules[key]==name then return false end end;return true"""
            key = len(pool)
            pool = [pack(script),pack(b"loadstring")] + pool
            guard = (ins("GGET",slot,key)+ins("KSTR",first,key+1)+ins("CALL",slot,514)
                     +ins("MOV",first,0)+ins("CALL",slot,514)+ins("IST",0,slot)
                     +ins("JMP",first,0x8001)+ins("RET0",0,1))
            code = guard + code
            head = head[:2] + bytes([first+1]) + head[3:]
            hits += 1
        body = (head + encode(len(pool)) + encode(nn) + encode(len(code)//4)
                + code + variables + b"".join(map(serial_constant,pool))
                + proto.raw(len(proto.data)-proto.pos))
        output.extend(encode(len(body)) + body)
        index += 1
    assert hits == 1 and cur.pos == len(source)
    output.append(0)
    return bytes(output), Counter({"removed_activity_shortcuts": hits})
