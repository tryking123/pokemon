"""移除超速冒险、快速战斗原界面的次数门槛，保留真实计数与原费用尾档。"""
from collections import Counter


def patch_unlimited(source, mode, Reader, encode, constants, serial_constant, pack, ins):
    """按已核验指令定位双架构原函数，避免改写其他业务。"""
    cur = Reader(source)
    assert cur.raw(4) == b'\x1bLJ\x02'
    flags = cur.num()
    assert flags & 2 and not flags & 1
    argument = 2 if flags & 8 else 1
    output = bytearray(source[:cur.pos])
    index = hits = 0
    while size := cur.num():
        proto = Reader(cur.raw(size))
        head = proto.raw(4)
        nconstants, nnumbers, nop = [proto.num() for _ in range(3)]
        code = proto.raw(nop * 4)
        variables = proto.raw(head[3] * 2)
        pool = constants(proto, nconstants)
        if mode == 'unlimited_quicken_control' and index == 2:
            assert nop == 50 and pack(b'fightimes') in pool and pack(b'quicken_count') in pool
            script = b'''local c=...;local d=c.view.data;local chr=chr_model.get_chr();local prices=data_mgr.get_one("variable_proto",1090).data6;local cost=prices[math.min((d.quicken_count or 0)+1,#prices)];if chr.diamonds<cost then label_popup.show(lang_mgr.get_one("common",19));return end;c.model.do_sweep()'''
            key = len(pool)
            pool = [pack(script), pack(b'loadstring')] + pool
            code = (ins('GGET', 0, key) + ins('KSTR', argument, key+1)
                    + ins('CALL', 0, 514) + ins('UGET', argument, 0)
                    + ins('CALL', 0, 258) + ins('RET0', 0, 1))
            head = head[:2] + bytes([max(head[2], argument+1)]) + head[3:]
            hits += 1
        elif mode == 'unlimited_quicken_view' and index == 1:
            assert nop == 102 and code[62*4:63*4] == ins('SUBVV', 8, 8*256+9)
            # 不再灰显按钮；quicken_count 仍用于费用尾档和业务真实次数。
            code = code[:62*4] + ins('KSHORT', 8, 1) + code[63*4:]
            assert pool.count(pack(b'%d/%d')) == 1
            pool[pool.index(pack(b'%d/%d'))] = pack('不限次数'.encode())
            hits += 1
        elif mode == 'unlimited_fast_view' and index == 2:
            assert nop == 59 and code[30*4:31*4] == ins('SUBVV', 5, 5*256+4)
            assert code[43*4:44*4] == ins('CALL', 6, 516)
            code = code[:30*4] + ins('KSHORT', 5, 1) + code[31*4:]
            key = len(pool)
            pool = [pack('不限次数'.encode())] + pool
            code = code[:43*4] + ins('KSTR', 6, key) + code[44*4:]
            hits += 1
        body = (head + encode(len(pool)) + encode(nnumbers) + encode(len(code)//4)
                + code + variables + b''.join(serial_constant(item) for item in pool)
                + proto.raw(len(proto.data)-proto.pos))
        output.extend(encode(len(body)) + body)
        index += 1
    assert hits == 1, (mode, hits)
    assert cur.pos == len(source)
    output.extend(b'\0')
    return bytes(output), Counter({mode: hits})
