"""保留双 ABI 原协议与界面，用 LuaJIT FFI 替换游戏套接字传输。"""
import argparse
import hashlib
import importlib.util
from pathlib import Path
import struct
import sys
import zipfile
from patch_awake_ui import patch_awake_ui, patch_character_catalog_ui, patch_skill_slots
from patch_battle_reentry import patch_battle_reentry
from patch_quest_ui import patch_task_guides, patch_task_lock, patch_task_navigation
from patch_rocket_result import patch_rocket_result
from patch_team_talents import patch_team_talents
from patch_dynamax_panel import patch_dynamax_panel
from patch_ancient_ui import patch_ancient_select

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("offline_arena_helpers", HERE / "patch-unity-arena.py")
helpers = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = helpers
spec.loader.exec_module(helpers)


def patch_network(source):
    """只替换已验证的网络边界，保持协议、会话和全部跳转位置。"""
    already_patched = b"pokemon_exchange" in source
    Reader, encode, pack, ins = helpers.Reader, helpers.encode, helpers.pack, helpers.ins
    cur = Reader(source)
    assert cur.raw(4) == b"\x1bLJ\x02"
    flags = cur.num()
    assert flags & 2 and not flags & 1
    argument = 2 if flags & 8 else 1
    result = bytearray(source[:cur.pos])
    index, changed = 0, set()
    while size := cur.num():
        proto = Reader(cur.raw(size))
        head = proto.raw(4)
        nk, nn, count = [proto.num() for _ in range(3)]
        code = proto.raw(count * 4)
        variables = proto.raw(head[3] * 2)
        pool = helpers.constants(proto, nk)
        numbers = proto.data[proto.pos:]
        if index == 2:
            if already_patched and pack(b"Close") in pool:
                changed.add(index)
                block = (head + encode(len(pool)) + encode(nn) + encode(len(code) // 4)
                         + code + variables + b"".join(map(helpers.serial_constant, pool)) + numbers)
                result += encode(len(block)) + block
                index += 1
                continue
            assert pool[-1] == pack(b"log") and head[3] == 3
            key = len(pool)
            pool = [pack(b"Close")] + pool
            # Destroy 独立捕获根函数的 R1 proxy，避免销毁旧模块时操作新模块的全局连接。
            variables += struct.pack("<H", 0xc001)
            head = head[:3] + bytes([4])
            code = (ins("UGET", 0, 3) + ins("MOV", argument, 0)
                    + ins("TGETS", 0, key) + ins("CALL", 0, 258) + code)
            head = head[:2] + bytes([max(head[2], argument + 1)]) + head[3:]
            changed.add(index)
        elif index in (18, 19):
            if already_patched:
                changed.add(index)
                block = (head + encode(len(pool)) + encode(nn) + encode(len(code) // 4)
                         + code + variables + b"".join(map(helpers.serial_constant, pool)) + numbers)
                result += encode(len(block)) + block
                index += 1
                continue
            start = 33 if index == 18 else 27
            key = len(pool)
            assert pool[-1] == pack(b"IsConnected")
            assert code[(start + 1) * 4:(start + 2) * 4] == ins("TGETS", 4, (4 << 8) | (9 if index == 18 else 7))
            pool = [pack(b"SendRaw")] + pool
            # 原 payload 位于 R3，直接传给 Lua proxy，跳过 ByteBuffer 和 C# SendMessage。
            replacement = (ins("UGET", 5, 0) + ins("MOV", 6 + argument - 1, 5)
                           + ins("TGETS", 5, (5 << 8) | key)
                           + ins("MOV", 7 + argument - 1, 3) + ins("CALL", 5, 259))
            replacement += ins("MOV", 5, 5) * 7
            assert len(replacement) == 48
            code = code[:start * 4] + replacement + code[(start + 12) * 4:]
            changed.add(index)
        elif index == 20:
            if already_patched:
                slots = [i for i, value in enumerate(pool) if isinstance(value, bytes) and b"pokemon_exchange" in value]
                assert len(slots) == 1
                pool[slots[0]] = pack((HERE / "offline-network.lua").read_bytes())
                changed.add(index)
                block = (head + encode(len(pool)) + encode(nn) + encode(len(code) // 4)
                         + code + variables + b"".join(map(helpers.serial_constant, pool)) + numbers)
                result += encode(len(block)) + block
                index += 1
                continue
            assert pool[-1] == pack(b"LuaHelper") and pool[-2] == pack(b"GetNetManager")
            assert code[4:16] == ins("GGET", 1, 0) + ins("TGETS", 1, 257) + ins("CALL", 1, 513)
            key = len(pool)
            pool = [pack((HERE / "offline-network.lua").read_bytes()), pack(b"loadstring")] + pool
            code = (code[:4] + ins("GGET", 1, key) + ins("KSTR", 1 + argument, key + 1)
                    + ins("CALL", 1, 514) + ins("CALL", 1, 513) + code[16:])
            changed.add(index)
        block = (head + encode(len(pool)) + encode(nn) + encode(len(code) // 4)
                 + code + variables + b"".join(map(helpers.serial_constant, pool)) + numbers)
        result += encode(len(block)) + block
        index += 1
    assert changed == {2, 18, 19, 20} and cur.pos == len(source)
    result.append(0)
    return bytes(result)


def patch_calls(source, mode):
    """把选服与 SDK 的 WWW 订单入口替换为 App 直接调用。"""
    Reader, encode, pack, ins = helpers.Reader, helpers.encode, helpers.pack, helpers.ins
    marker = b"ctrl.model.get_info()" if mode == "carnival" else b"PokemonOffline:Api"
    already_patched = marker in source
    cur = Reader(source)
    assert cur.raw(4) == b"\x1bLJ\x02"
    flags = cur.num()
    assert flags & 2 and not flags & 1
    argument = 2 if flags & 8 else 1
    result = bytearray(source[:cur.pos])
    changed, index = set(), 0
    selections = ({12: "offline-login-realms.lua", 13: "offline-login-history.lua"} if mode == "login"
                  else {2: "offline-notice.lua"} if mode == "notice"
                  else {3: "offline-carnival-refresh.lua"} if mode == "carnival" else {16: "offline-order.lua"})
    while size := cur.num():
        proto = Reader(cur.raw(size))
        head = proto.raw(4)
        nk, nn, count = [proto.num() for _ in range(3)]
        code = proto.raw(count * 4)
        variables = proto.raw(head[3] * 2)
        pool = helpers.constants(proto, nk)
        numbers = proto.data[proto.pos:]
        if index in selections:
            if already_patched:
                slots = [i for i, value in enumerate(pool) if isinstance(value, bytes) and marker in value]
                assert len(slots) == 1
                pool[slots[0]] = pack((HERE / selections[index]).read_bytes())
                changed.add(index)
                block = (head + encode(len(pool)) + encode(nn) + encode(len(code) // 4)
                         + code + variables + b"".join(map(helpers.serial_constant, pool)) + numbers)
                result += encode(len(block)) + block
                index += 1
                continue
            assert pack(b"finish_get" if mode == "carnival" else b"WWW" if mode in ("login", "notice") else b"Order") in pool
            key = len(pool)
            pool = [pack((HERE / selections[index]).read_bytes()), pack(b"loadstring")] + pool
            register = head[2]
            first = register + argument
            code = ins("GGET", register, key) + ins("KSTR", first, key + 1) + ins("CALL", register, 514)
            if mode == "carnival":
                code += ins("UGET", first, 0) + ins("CALL", register, 258)
                head = head[:2] + bytes([first + 1]) + head[3:]
            elif mode == "notice":
                assert head[3] == 2
                code += (ins("UGET", first, 0) + ins("UGET", first + 1, 1)
                         + ins("CALL", register, 515) + ins("USETV", 0, register))
                head = head[:2] + bytes([first + 2]) + head[3:]
            elif mode == "login":
                code += ins("UGET", first, 0) + ins("CALL", register, 258)
                head = head[:2] + bytes([first + 1]) + head[3:]
            else:
                code += ins("MOV", first, 0) + ins("MOV", first + 1, 1) + ins("CALL", register, 259)
                head = head[:2] + bytes([first + 2]) + head[3:]
            code += ins("RET0", 0, 1)
            changed.add(index)
        block = (head + encode(len(pool)) + encode(nn) + encode(len(code) // 4)
                 + code + variables + b"".join(map(helpers.serial_constant, pool)) + numbers)
        result += encode(len(block)) + block
        index += 1
    assert changed == set(selections) and cur.pos == len(source)
    result.append(0)
    return bytes(result)


def patch_gacha_preview(source):
    """三个世界阶段的高级预览共用离线抽取名单。"""
    from apk_table_tools import decode_table, patch_table
    catalog = HERE / "offline/resources/gacha/catalog.properties"
    properties = dict(line.split("=", 1) for line in catalog.read_text(encoding="utf8").splitlines()
                      if line and not line.startswith("#"))
    species = [int(value) for value in properties["advanced.elves"].split(",")]
    rewards = [value for species_id in species for value in (2, species_id)]
    stages = decode_table(source)
    for stage in stages.values():
        stage["reward2"] = rewards
    return patch_table(source, stages, helpers)


def show_dynamax_entry(payload, unity):
    """默认展示进化页的极巨化标签，保留 Lock2 的原点击区域及资格提示。"""
    bundle = unity.load(payload)
    entry_id = 1344037337806686134
    entry = next(obj.read() for obj in bundle.objects if obj.path_id == entry_id)
    transform = entry.m_Component[0].component.read()
    path = []
    while transform:
        path.append(transform.m_GameObject.read().m_Name)
        transform = transform.m_Father.read() if transform.m_Father.path_id else None
    assert path == ["2", "ToggleGroup", "Evolve", "Content", "SpriteHandle"]
    entry.m_IsActive = True
    entry.save()

    children = entry.m_Component[0].component.read().m_Children
    lock = next(child.read() for child in children if child.read().m_GameObject.read().m_Name == "Lock2")
    background = next(component.component for component in lock.m_GameObject.read().m_Component
                      if component.component.type.name == "MonoBehaviour"
                      and "m_RaycastTarget" in component.component.read_typetree())
    image = background.read_typetree()
    assert image["m_RaycastTarget"]
    image["m_Color"]["a"] = 0.0
    background.deref().save_typetree(image)

    icon, = lock.m_Children
    icon_rect = icon.read()
    assert icon_rect.m_GameObject.read().m_Name == "Image"
    # 将锁标放在原标签左侧图标位置，给名称留出完整宽度。
    icon_rect.m_AnchoredPosition.x = -72.7
    icon_rect.save()
    return bundle.file.save(packer="lz4")


def apply(origin, output):
    """在已有 APK 增量条目上叠加补丁，并同步两级资源清单。"""
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import pad, unpad
    import UnityPy
    with origin.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != helpers.ORIGIN_SHA256:
            raise ValueError("底包 SHA-256 不符")
    with zipfile.ZipFile(origin) as archive:
        def read(entry):
            path = output / entry
            return path.read_bytes() if path.is_file() else archive.read(entry)
        key = read(helpers.METADATA)[86192:86224]
        decrypt = lambda value: unpad(AES.new(key, AES.MODE_ECB).decrypt(value), 16)
        encrypt = lambda value: AES.new(key, AES.MODE_ECB).encrypt(pad(value, 16))
        manifest = decrypt(read("assets/assets/manifest.ab"))
        records = [line.split("|") for line in manifest.decode().splitlines()]
        lookup = {row[0]: row for row in records}
        containers, changed = {}, {}
        def replace(name, transform):
            row = lookup[name]
            container = containers.setdefault(row[1], bytearray(read("assets/assets/" + row[1])))
            payload = decrypt(container[int(row[2]):int(row[2]) + int(row[3])])
            updated = transform(payload)
            encrypted = encrypt(updated)
            row[2:] = [str(len(container)), str(len(encrypted))]
            container.extend(encrypted)
            return updated
        changed["modules_sprite.ab"] = replace("modules_sprite.ab", lambda value: show_dynamax_entry(value, UnityPy))
        for abi in (32, 64):
          preview = f"lua{abi}_database_preview_proto.lua.bytes"
          changed[preview] = replace(preview, patch_gacha_preview)
          for bundle_name, filename, patch in (("network", "network.lua", patch_network),
                  ("module_login", "login_model.lua", lambda value: patch_calls(value, "login")),
                  ("manager", "sdk_mgr.lua", lambda value: patch_calls(value, "order")),
                  ("module_newlogin", "newlogin_model.lua", lambda value: patch_calls(value, "login")),
                  ("module_notice", "notice_model.lua", lambda value: patch_calls(value, "notice")),
                  ("module_fesrevel", "fesrevel_ctrl.lua", lambda value: patch_calls(value, "carnival")),
                  ("module_sprite", "elf_handle.lua", lambda value: patch_awake_ui(value, helpers)),
                  ("module_sprite", "large_panel.lua", patch_dynamax_panel),
                  ("module_sprite", "skill_panel.lua", lambda value: patch_skill_slots(value, helpers)),
                  ("module_sprite", "spancient_consume.lua", lambda value: patch_ancient_select(value, helpers)),
                  ("manager", "data_mgr.lua", lambda value: patch_character_catalog_ui(value, helpers)),
                  ("module_battle", "battle_module.lua", lambda value: patch_battle_reentry(value, helpers)),
                  ("gmodel", "gtask_model.lua", lambda value: patch_task_guides(patch_task_lock(value))),
                  ("module_navi", "navi_view.lua", patch_task_navigation),
                  ("module_battle", "battle_ctrl.lua", lambda value: patch_rocket_result(value, helpers)),
                  ("module_battle", "formation.lua", lambda value: patch_team_talents(value, helpers))):
            name = f"lua{abi}_{bundle_name}.ab"
            def transform(value):
                bundle = UnityPy.load(value)
                matched = 0
                for obj in bundle.objects:
                    if obj.type.name != "TextAsset": continue
                    asset = obj.read()
                    if asset.m_Name not in (filename, filename + ".bytes"): continue
                    payload = asset.m_Script.encode("utf8", "surrogateescape")
                    asset.m_Script = patch(payload).decode("utf8", "surrogateescape")
                    asset.save()
                    matched += 1
                assert matched == 1
                return bundle.file.save(packer="lz4")
            changed[name] = replace(name, transform)
        found = set()
        def version(value):
            lines = [line.split(b"|") for line in value.split(b"\n")]
            for row in lines:
                name = row[0].decode("ascii")
                if name in changed:
                    payload = changed[name]
                    row[1:] = [hashlib.md5(payload).hexdigest().encode(), str(len(payload)).encode()]
                    found.add(name)
            return b"\n".join(b"|".join(row) for row in lines)
        replace("version.txt", version)
        replace("xversion.txt", version)
        assert found == set(changed)
        separator = b"\r\n" if b"\r\n" in manifest else b"\n"
        ending = manifest[len(manifest.rstrip(b"\r\n")):]
        packed_manifest = encrypt(separator.join("|".join(row).encode() for row in records) + ending)
        folder = output / "assets/assets"
        folder.mkdir(parents=True, exist_ok=True)
        for name, payload in containers.items(): (folder / name).write_bytes(payload)
        (folder / "manifest.ab").write_bytes(packed_manifest)
        digest = output / "assets/pokemon-local/unity-index.txt"
        digest.parent.mkdir(parents=True, exist_ok=True)
        digest.write_text(hashlib.sha256(packed_manifest).hexdigest(), encoding="ascii")
        print("已替换双 ABI 原网络边界；资源清单已同步。")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--python-libs", type=Path)
    args = parser.parse_args()
    if args.python_libs: sys.path.insert(0, str(args.python_libs.resolve()))
    apply(args.origin, args.output)


if __name__ == "__main__": main()
