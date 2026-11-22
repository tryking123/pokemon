"""保留原引擎天赋动作，仅替换团队属性取值和加成来源登记。"""
from pathlib import Path


def patch_team_talents(source, helpers):
    reader = helpers.Reader(source)
    assert reader.raw(4) == b'\x1bLJ\x02'
    flags = reader.num()
    assert flags & 2 and not flags & 1
    spacing = 2 if flags & 8 else 1
    output = bytearray(source[:reader.pos])
    script = Path(__file__).with_name("local-team-talents.lua").read_bytes()
    pack, ins = helpers.pack, helpers.ins
    index, matched = -1, set()
    while size := reader.num():
        index += 1
        raw = reader.raw(size)
        if index not in (188, 196, 265):
            output += helpers.encode(size) + raw
            continue
        proto = helpers.Reader(raw)
        head = bytearray(proto.raw(4))
        nk, nn, count = proto.num(), proto.num(), proto.num()
        code = bytearray(proto.raw(count * 4))
        variables = proto.raw(head[3] * 2)
        pool = helpers.constants(proto, nk)
        numbers = proto.data[proto.pos:]
        if index == 188 and pack(b'teamSource') not in pool:
            # 动作27先读取来源属性，随后区分自身与队友；只有队友分支标记共享。
            register = code[12 * 4 + 1]
            assert pool[-1] == pack(b'get') and pool[-4] == pack(b'attach')
            assert code[48:52] == ins('CALL', register, 515)
            assert code[248:252] == ins('TGETS', 16, (16 << 8) + 3)
            code[248:252] = ins('TGETS', 16, (16 << 8) + nk)
            code[48:52] = ins('MOV', register + spacing + 2, 8) + ins('CALL', register, 516)
            pool[-1] = pack(b'teamSource')
            pool.insert(0, pack(b'attachTeam'))
        elif index == 196 and pack(b'attachTeam') not in pool:
            # 动作35保留原阵营、属性和是否包含施放者的判断。
            assert pool[-10] == pack(b'attach')
            pool[-10] = pack(b'attachTeam')
        elif index == 265:
            previous = [i for i, item in enumerate(pool)
                        if isinstance(item, bytes) and b'function buffs.attachTeam' in item]
            if previous:
                assert len(previous) == 1
                pool[previous[0]] = pack(script)
            else:
                assert head[2] == 28 and code[-8:] == ins('UCLO', 0, 32768) + ins('RET1', 16, 2)
                register = head[2]
                first = register + spacing
                pool = [pack(script), pack(b'loadstring')] + pool
                code[-8:-8] = (ins('GGET', register, nk) + ins('KSTR', first, nk + 1)
                               + ins('CALL', register, 514) + ins('MOV', first, 16)
                               + ins('MOV', first + 1, 1) + ins('CALL', register, 259))
                head[2] = first + 2
        block = (head + helpers.encode(len(pool)) + helpers.encode(nn)
                 + helpers.encode(len(code) // 4) + code + variables
                 + b''.join(map(helpers.serial_constant, pool)) + numbers)
        output += helpers.encode(len(block)) + block
        matched.add(index)
    assert matched == {188, 196, 265} and reader.pos == len(source)
    return bytes(output) + b'\0'
