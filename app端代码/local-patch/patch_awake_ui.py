"""修复双架构精灵觉醒页面被稀疏技能表错误选档的问题。"""
from pathlib import Path


def patch_skill_slots(source, helpers):
    """按种族与觉醒类别分别刷新原技能页四个安装槽。"""
    reader = helpers.Reader(source)
    assert reader.raw(4) == b"\x1bLJ\x02"
    flags = reader.num()
    assert flags & 2 and not flags & 1
    argument = 2 if flags & 8 else 1
    result = bytearray(source[:reader.pos])
    marker = b"local category = kind == 2 and 2 or 1"
    lua = Path(__file__).with_name("local-skill-slots.lua").read_bytes()
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
        if index == 18:
            assert head[1] == 2 and head[3] == 5
            slots = [i for i, value in enumerate(pool) if isinstance(value, bytes) and marker in value]
            if slots:
                assert len(slots) == 1
                pool[slots[0]] = helpers.pack(lua)
            else:
                assert helpers.pack(b"install") in pool and helpers.pack(b"skill_ison") in pool
                key = len(pool)
                pool = [helpers.pack(lua), helpers.pack(b"loadstring")] + pool
                register = head[2]
                first = register + argument
                ins = helpers.ins
                code = (ins("GGET", register, key) + ins("KSTR", first, key + 1)
                        + ins("CALL", register, 514) + ins("MOV", first, 0) + ins("MOV", first + 1, 1)
                        + ins("UGET", first + 2, 0) + ins("UGET", first + 3, 2)
                        + ins("UGET", first + 4, 3) + ins("UGET", first + 5, 4)
                        + ins("CALL", register, 263) + ins("RET0", 0, 1))
                head = head[:2] + bytes([first + 6]) + head[3:]
            changed += 1
        block = (head + helpers.encode(len(pool)) + helpers.encode(nn)
                 + helpers.encode(len(code) // 4) + code + variables
                 + b"".join(map(helpers.serial_constant, pool)) + numbers)
        result += helpers.encode(len(block)) + block
        index += 1
    assert changed == 1 and reader.pos == len(source)
    result.append(0)
    return bytes(result)


def patch_character_catalog_ui(source, helpers):
    """在表读取模块统一提供被动扩展表回退，覆盖所有原技能与觉醒页面。"""
    reader = helpers.Reader(source)
    assert reader.raw(4) == b"\x1bLJ\x02"
    flags = reader.num()
    assert flags & 2 and not flags & 1
    argument = 2 if flags & 8 else 1
    result = bytearray(source[:reader.pos])
    marker = b"PokemonAwakeCharacterLookup"
    lua = Path(__file__).with_name("local-awake-character.lua").read_bytes()
    changed = 0
    while size := reader.num():
        proto = helpers.Reader(reader.raw(size))
        head = proto.raw(4)
        nk, nn, count = [proto.num() for _ in range(3)]
        code = proto.raw(count * 4)
        variables = proto.raw(head[3] * 2)
        pool = helpers.constants(proto, nk)
        numbers = proto.data[proto.pos:]
        if head[3] == 0 and helpers.pack(b"get_one") in pool and code[-4] == helpers.OP["RET1"]:
            slots = [i for i, value in enumerate(pool) if isinstance(value, bytes) and marker in value]
            if slots:
                assert len(slots) == 1
                pool[slots[0]] = helpers.pack(lua)
            else:
                module_register = code[-3]
                lookup = list(reversed(pool)).index(helpers.pack(b"get_one"))
                key = len(pool)
                pool = [helpers.pack(lua), helpers.pack(b"loadstring")] + pool
                register = head[2]
                first = register + argument
                ins = helpers.ins
                code = code[:-4] + (ins("GGET", register, key) + ins("KSTR", first, key + 1)
                        + ins("CALL", register, 514) + ins("TGETS", first, (module_register << 8) | lookup)
                        + ins("CALL", register, 514) + ins("TSETS", register, (module_register << 8) | lookup)) + code[-4:]
                head = head[:2] + bytes([first + 1]) + head[3:]
            changed += 1
        block = (head + helpers.encode(len(pool)) + helpers.encode(nn)
                 + helpers.encode(len(code) // 4) + code + variables
                 + b"".join(map(helpers.serial_constant, pool)) + numbers)
        result += helpers.encode(len(block)) + block
    assert changed == 1 and reader.pos == len(source)
    result.append(0)
    return bytes(result)


def patch_skill_ui(source, helpers):
    """使原被动技能节点兼容扩展被动表，不修改任何技能或显示字段。"""
    reader = helpers.Reader(source)
    assert reader.raw(4) == b"\x1bLJ\x02"
    flags = reader.num()
    assert flags & 2 and not flags & 1
    argument = 2 if flags & 8 else 1
    result = bytearray(source[:reader.pos])
    marker = b"PokemonAwakeCharacterLookup"
    lua = Path(__file__).with_name("local-awake-character.lua").read_bytes()
    changed = set()
    index = 0
    while size := reader.num():
        proto = helpers.Reader(reader.raw(size))
        head = proto.raw(4)
        nk, nn, count = [proto.num() for _ in range(3)]
        code = proto.raw(count * 4)
        variables = proto.raw(head[3] * 2)
        pool = helpers.constants(proto, nk)
        numbers = proto.data[proto.pos:]
        if index in (12, 13):
            assert helpers.pack(b"character_proto") in pool
            slots = [i for i, value in enumerate(pool) if isinstance(value, bytes) and marker in value]
            if slots:
                assert len(slots) == 1
                pool[slots[0]] = helpers.pack(lua)
            else:
                key = len(pool)
                pool = [helpers.pack(lua), helpers.pack(b"loadstring")] + pool
                register = head[2]
                first = register + argument
                ins = helpers.ins
                code = (ins("GGET", register, key) + ins("KSTR", first, key + 1)
                        + ins("CALL", register, 514) + ins("UGET", first, 0)
                        + ins("CALL", register, 514) + ins("USETV", 0, register)) + code
                head = head[:2] + bytes([first + 1]) + head[3:]
            changed.add(index)
        block = (head + helpers.encode(len(pool)) + helpers.encode(nn)
                 + helpers.encode(len(code) // 4) + code + variables
                 + b"".join(map(helpers.serial_constant, pool)) + numbers)
        result += helpers.encode(len(block)) + block
        index += 1
    assert changed == {12, 13} and reader.pos == len(source)
    result.append(0)
    return bytes(result)


def patch_awake_ui(source, helpers):
    """替换 elf_handle 的觉醒状态闭包，保留外部调用与技能配置。"""
    reader = helpers.Reader(source)
    assert reader.raw(4) == b"\x1bLJ\x02"
    flags = reader.num()
    assert flags & 2 and not flags & 1
    argument = 2 if flags & 8 else 1
    result = bytearray(source[:reader.pos])
    marker = b"pkm.maxawaken = maximum"
    lua = Path(__file__).with_name("local-awake-stages.lua").read_bytes()
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
        if index == 1:
            assert head[1] == 1 and head[3] == 2
            slots = [i for i, value in enumerate(pool) if isinstance(value, bytes) and marker in value]
            if slots:
                assert len(slots) == 1
                pool[slots[0]] = helpers.pack(lua)
            else:
                assert helpers.pack(b"parse_awaken") in pool and helpers.pack(b"nextindex") in pool
                key = len(pool)
                pool = [helpers.pack(lua), helpers.pack(b"loadstring")] + pool
                register = head[2]
                first = register + argument
                ins = helpers.ins
                code = (ins("GGET", register, key) + ins("KSTR", first, key + 1)
                        + ins("CALL", register, 514) + ins("MOV", first, 0)
                        + ins("UGET", first + 1, 0) + ins("UGET", first + 2, 1)
                        + ins("CALL", register, 516) + ins("RET1", register, 2))
                head = head[:2] + bytes([first + 3]) + head[3:]
            changed += 1
        block = (head + helpers.encode(len(pool)) + helpers.encode(nn)
                 + helpers.encode(len(code) // 4) + code + variables
                 + b"".join(map(helpers.serial_constant, pool)) + numbers)
        result += helpers.encode(len(block)) + block
        index += 1
    assert changed == 1 and reader.pos == len(source)
    result.append(0)
    return bytes(result)
