import argparse
import copy
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


BASE_DIGEST = "f010a675dcb9ccacf899a2954e7e497be7f04c3ea94214140fa3434c817a7c45"
MANIFEST = "AndroidManifest.xml"
LOCAL_DEX = "classes9.dex"
UNITY_METADATA = "assets/bin/Data/Managed/Metadata/global-metadata.dat"
UNITY_LOCAL_METADATA_DIGEST = "73207ddb980fc5873e826b23480538bfaf949918cd4a5b63de3c4b0421510bca"
UNITY_LOCAL_NATIVE_DIGESTS = {
    "lib/arm64-v8a/libil2cpp.so": "c36d441fbd37a722d531519734582da551113fed85c51c25fa1cb6c11a24aaca",
    "lib/armeabi-v7a/libil2cpp.so": "628f1abd36ecb9084c71391bc419761ccd49c67a5357106431eb8a2bbb0d1724",
}
OFFLINE_BRIDGE_DIGESTS = {
    "lib/arm64-v8a/libpokemonoffline.so": "7532eba9f40e3ca1bf9e25d1cfd6d98ab9aaf4a63737d934f20bb16e42a85bb3",
    "lib/armeabi-v7a/libpokemonoffline.so": "f8691542403406916084b9df2cb17a774f0ec0d09a1038a52cd0171d9f360180",
}
SIGNATURE_ENTRY = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)", re.I)
NO_INDEX = 0xFFFFFFFF
ANDROID_NS = "http://schemas.android.com/apk/res/android"
COMPONENT_NAMES = {
    "com.qk.game.GameApplication": "android.app.Application",
    "com.qk.game.SplashActivity": "com.qk.local.LocalHomeActivity",
    "com.qk.game.MainActivity": "com.qk.local.LocalGameActivity",
}


def digest(stream):
    return hashlib.file_digest(stream, "sha256").hexdigest()


def hash_file(path):
    with path.open("rb") as stream:
        return digest(stream)


def parse_chunks(document):
    kind, header_size, size = struct.unpack_from("<HHI", document)
    if (kind, header_size, size) != (0x0003, 8, len(document)):
        raise ValueError("非法的 Android 二进制清单文件头")
    start = header_size
    while start < size:
        kind, header_size, chunk_size = struct.unpack_from("<HHI", document, start)
        if not 8 <= header_size <= chunk_size <= size - start:
            raise ValueError("Android 二进制清单字节块长度不正确")
        yield kind, document[start:start + chunk_size]
        start += chunk_size


class StringPool:
    def __init__(self, chunk):
        _, header_size, _, count, styles, flags, start, style_start = struct.unpack_from(
            "<HH6I", chunk
        )
        if (header_size, styles, flags, style_start) != (28, 0, 0, 0):
            raise ValueError("原始清单字符串池格式与已验证的底包不一致")
        if start != header_size + 4 * count:
            raise ValueError("原始清单字符串池的偏移表长度不正确")
        offsets = struct.unpack_from(f"<{count}I", chunk, header_size)
        self.values = []
        for offset in offsets:
            length, = struct.unpack_from("<H", chunk, start + offset)
            if length & 0x8000:
                raise ValueError("原始清单字符串超过支持长度")
            entry_start = start + offset + 2
            self.values.append(chunk[entry_start:entry_start + 2 * length].decode("utf-16le"))

    def __getitem__(self, index):
        return None if index == NO_INDEX else self.values[index]

    def index(self, value):
        if value not in self.values:
            self.values.append(value)
        return self.values.index(value)

    def build(self):
        offsets = []
        payload = bytearray()
        for value in self.values:
            offsets.append(len(payload))
            encoded = value.encode("utf-16le")
            payload.extend(struct.pack("<H", len(encoded) // 2))
            payload.extend(encoded)
            payload.extend(b"\0\0")
        payload.extend(b"\0" * (-len(payload) % 4))
        count = len(offsets)
        start = 28 + 4 * count
        return (
            struct.pack("<HH6I", 1, 28, start + len(payload), count, 0, 0, start, 0)
            + struct.pack(f"<{count}I", *offsets)
            + payload
        )


def manifest_attributes(chunk):
    offset, size, count, ids, classes, styles = struct.unpack_from("<6H", chunk, 24)
    if (offset, size, ids, classes, styles) != (20, 20, 0, 0, 0):
        raise ValueError("原始清单的 XML 属性与已验证的底包不一致")
    return [chunk[36 + i * size:36 + (i + 1) * size] for i in range(count)]


def update_component(chunk, attributes, pool, resource_ids, old_name, offline):
    fields = []
    exported = None
    android = pool.index(ANDROID_NS)
    for raw in attributes:
        namespace, name, value, size, unused, kind, data = struct.unpack("<IIIHBBI", raw)
        if namespace == android:
            attr = pool[name]
            if attr == "name":
                replacement = "com.qk.local.LocalApplication" if offline and old_name == "com.qk.game.GameApplication" else COMPONENT_NAMES[old_name]
                value = data = pool.index(replacement)
            elif old_name == "com.qk.game.GameApplication":
                if attr == "appComponentFactory":
                    continue
                if attr == "allowBackup":
                    value, data = NO_INDEX, 0
            elif attr == "exported":
                exported = name
        fields.append((namespace, name, value, size, unused, kind, data))
    if old_name == "com.qk.game.MainActivity":
        if exported is not None:
            raise ValueError("原始游戏入口已具有 exported 属性")
        fields.append((android, pool.index("exported"), NO_INDEX, 8, 0, 0x12, 0))
        fields.sort(key=lambda field: resource_ids[field[1]])
    length = 36 + 20 * len(fields)
    return (
        chunk[:4] + struct.pack("<I", length) + chunk[8:28]
        + struct.pack("<H", len(fields)) + chunk[30:36]
        + b"".join(struct.pack("<IIIHBBI", *field) for field in fields)
    )


def item_mail_activity(pool, resource_ids, host="item-mail", activity="com.qk.local.ProfileItemMailActivity"):
    """生成个人信息页的同应用 URI 弹窗节点，以及 scheme、host 的框架属性索引。"""
    for attribute, framework_id in (("scheme", 0x01010027), ("host", 0x01010028)):
        index = pool.index(attribute)
        resource_ids.extend([0] * max(0, index + 1 - len(resource_ids)))
        if resource_ids[index] not in (0, framework_id):
            raise ValueError("清单 URI 属性与 Android 框架不匹配")
        resource_ids[index] = framework_id

    def node(name, attributes=(), children=b""):
        element = pool.index(name)
        fields = []
        for attr, kind, value in attributes:
            key = pool.index(attr)
            raw = pool.index(value) if kind == 3 else NO_INDEX
            data = raw if kind == 3 else value
            fields.append((resource_ids[key], struct.pack("<IIIHBBI", pool.index(ANDROID_NS), key, raw, 8, 0, kind, data)))
        packed = b"".join(raw for _, raw in sorted(fields))
        start = struct.pack("<HHIIIII6H", 0x0102, 16, 36 + len(packed), 1, NO_INDEX, NO_INDEX,
                            element, 20, 20, len(fields), 0, 0, 0)
        end = struct.pack("<HHIIIII", 0x0103, 16, 24, 1, NO_INDEX, NO_INDEX, element)
        return start + packed + children + end

    intent_filter = node("intent-filter", children=(
        node("action", [("name", 3, "android.intent.action.VIEW")])
        + node("category", [("name", 3, "android.intent.category.DEFAULT")])
        + node("category", [("name", 3, "android.intent.category.BROWSABLE")])
        + node("data", [("scheme", 3, "pokemon-local"), ("host", 3, host)])
    ))
    return node("activity", [("name", 3, activity),
                             ("theme", 1, 0x01030010), ("exported", 0x12, 0)], intent_filter)


def local_manifest(document, offline=False, item_mail=False):
    chunks = list(parse_chunks(document))
    pool = StringPool(chunks[0][1])
    mapping = next(chunk for kind, chunk in chunks if kind == 0x0180)
    resource_ids = struct.unpack(f"<{(len(mapping) - 8) // 4}I", mapping[8:])
    resource_ids = list(resource_ids)
    mail_component = (item_mail_activity(pool, resource_ids)
                      + item_mail_activity(pool, resource_ids, "pet-customize", "com.qk.local.PetCustomizeActivity")) if item_mail else b""
    result = []
    elements = []
    changed = set()
    for kind, chunk in chunks[1:]:
        if kind == 0x0102:
            element = pool[struct.unpack_from("<I", chunk, 20)[0]]
            parent, dropped = elements[-1] if elements else (None, False)
            if not dropped and (element in {"application", "queries", "uses-permission"} or parent == "application"):
                attrs = manifest_attributes(chunk)
                names = [struct.unpack_from("<III", raw) for raw in attrs]
                name = next(
                    (pool[value] for ns, key, value in names if pool[ns] == ANDROID_NS and pool[key] == "name"),
                    None,
                )
                if name in COMPONENT_NAMES:
                    if name in changed:
                        raise ValueError(f"存在重复的应用组件：{name}")
                    changed.add(name)
                    chunk = update_component(chunk, attrs, pool, resource_ids, name, offline)
                elif parent == "application":
                    dropped = element != "uses-library" and not (
                        element == "meta-data" and name.startswith(("unity.", "unityplayer.", "notch."))
                    )
                elif element == "queries":
                    dropped = True
                elif element == "uses-permission":
                    dropped = name not in {
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE",
                    }
            if offline and element == "uses-sdk":
                updated = bytearray(chunk)
                for index, raw in enumerate(manifest_attributes(chunk)):
                    namespace, name = struct.unpack_from("<II", raw)
                    if pool[namespace] == ANDROID_NS and pool[name] == "minSdkVersion":
                        struct.pack_into("<I", updated, 36 + index * 20 + 16, 26)
                chunk = bytes(updated)
            elements.append((element, dropped))
        elif kind == 0x0103:
            element, dropped = elements.pop()
            if element == "application" and not dropped and mail_component:
                result.append(mail_component)
        else:
            dropped = bool(elements and elements[-1][1])
        if not dropped:
            if kind == 0x0180 and mail_component:
                chunk = struct.pack("<HHI", kind, 8, 8 + 4 * len(resource_ids)) + struct.pack(f"<{len(resource_ids)}I", *resource_ids)
            result.append(chunk)
    if elements or changed != COMPONENT_NAMES.keys():
        raise ValueError("未找到全部本地入口对应的原始应用组件")
    content = pool.build() + b"".join(result)
    return struct.pack("<HHI", 0x0003, 8, len(content) + 8) + content


def patch_entries(directory):
    entries = {p.relative_to(directory).as_posix(): p for p in directory.rglob("*") if p.is_file() and not p.relative_to(directory).as_posix().startswith(("_backup_dex/", "assets/assets/"))}
    if LOCAL_DEX not in entries:
        raise ValueError(f"缺少客户端补丁：{directory / LOCAL_DEX}")
    offline = {name for name in entries if name.startswith("assets/pokemon-local/")
               and Path(name).suffix in {".sql", ".txt", ".json", ".tsv", ".properties"}}
    offline.update(entries.keys() & {
        "assets/pokemon-local/catalog/adventure.csv",
        "assets/pokemon-local/catalog/gear-enhance.csv",
        "assets/pokemon-local/catalog/gear-melt.csv",
        "assets/pokemon-local/catalog/item-choice.csv",
        "assets/pokemon-local/catalog/item-random.json",
        "assets/pokemon-local/activity/daily-missions.json",
    })
    unknown = entries.keys() - {LOCAL_DEX, UNITY_METADATA} - UNITY_LOCAL_NATIVE_DIGESTS.keys() - OFFLINE_BRIDGE_DIGESTS.keys() - offline
    if unknown:
        raise ValueError(f"发现未经验证的 APK 替换项：{', '.join(sorted(unknown))}")
    with entries[LOCAL_DEX].open("rb") as dex:
        if not re.fullmatch(rb"dex\n[0-9]{3}\0", dex.read(8)):
            raise ValueError(f"DEX 文件头无效：{entries[LOCAL_DEX]}")
    if UNITY_METADATA not in entries:
        raise ValueError("缺少 Unity 离线入口，请先运行 patch-unity-bootstrap.py 生成补丁")
    if hash_file(entries[UNITY_METADATA]) != UNITY_LOCAL_METADATA_DIGEST:
        raise ValueError("Unity metadata 补丁哈希不符")
    native = entries.keys() & UNITY_LOCAL_NATIVE_DIGESTS.keys()
    if native and native != UNITY_LOCAL_NATIVE_DIGESTS.keys():
        raise ValueError("Unity 原生补丁必须同时提供两个 ABI")
    for name in native:
        if hash_file(entries[name]) != UNITY_LOCAL_NATIVE_DIGESTS[name]:
            raise ValueError(f"Unity 原生补丁哈希不符：{name}")
    if entries.keys() & OFFLINE_BRIDGE_DIGESTS.keys() != OFFLINE_BRIDGE_DIGESTS.keys():
        raise ValueError("纯离线包必须含双 ABI 直接调用桥")
    for name, expected in OFFLINE_BRIDGE_DIGESTS.items():
        if hash_file(entries[name]) != expected:
            raise ValueError(f"进程内桥哈希不符：{name}")
    return entries


def rewrite_apk(base, output, entries):
    remaining = dict(entries)
    expected_hashes = {}
    with zipfile.ZipFile(base) as original, zipfile.ZipFile(output, "w") as target:
        for info in original.infolist():
            name = info.filename
            if SIGNATURE_ENTRY.fullmatch(name):
                continue
            info = copy.copy(info)
            if name == MANIFEST:
                contents = local_manifest(original.read(name), "assets/pokemon-local/components.txt" in entries,
                                          "assets/pokemon-local/catalog/grant-mail-items.json" in entries)
                target.writestr(info, contents)
                expected_hashes[name] = hashlib.sha256(contents).hexdigest()
                continue
            replacement = remaining.pop(name, None)
            with replacement.open("rb") if replacement else original.open(name) as source:
                with target.open(info, "w") as file_out:
                    shutil.copyfileobj(source, file_out)
            if replacement:
                expected_hashes[name] = hash_file(replacement)
            else:
                with original.open(name) as source:
                    expected_hashes[name] = digest(source)
        for name, path in remaining.items():
            target.write(path, name, compress_type=zipfile.ZIP_DEFLATED)
            expected_hashes[name] = hash_file(path)
    return expected_hashes


def verify_entries(signed, expected_hashes, patched):
    with zipfile.ZipFile(signed) as archive:
        names = [name for name in archive.namelist() if not SIGNATURE_ENTRY.fullmatch(name)]
        if len(names) != len(expected_hashes) or set(names) != expected_hashes.keys():
            raise ValueError("签名包内容清单与预期不符")
        for name in names:
            with archive.open(name) as stream:
                actual = digest(stream)
            if actual != expected_hashes[name]:
                raise ValueError(f"签名包条目哈希不匹配：{name}")
    for name in (MANIFEST, *sorted(patched)):
        print(f"{name} SHA-256 {expected_hashes[name]}")


def build(args):
    root = Path(__file__).resolve().parent
    base = root / "origin" / "origin.apk"
    if not base.is_file():
        raise ValueError(f"找不到底包：{base}")
    if hash_file(base) != BASE_DIGEST:
        raise ValueError(f"底包 SHA-256 不匹配：{base}")
    entries = patch_entries(args.entries.resolve())
    if args.output.parent.resolve() != root / "new":
        raise ValueError("签名包输出路径必须位于 app包/new")
    if args.output.exists():
        raise FileExistsError(f"禁止覆盖已有 APK：{args.output}")
    zipalign = args.build_tools.resolve() / "zipalign.exe"
    if not zipalign.is_file():
        raise ValueError(f"找不到 zipalign：{zipalign}")
    apksigner_jar = args.build_tools.resolve() / "lib" / "apksigner.jar"
    if not apksigner_jar.is_file():
        raise ValueError(f"找不到 apksigner.jar：{apksigner_jar}")
    apksigner = [
        str(args.java.resolve()), "-jar", str(apksigner_jar)
    ]
    with tempfile.TemporaryDirectory(prefix="pokemon-apk-") as temp:
        work = Path(temp)
        unsigned = work / "unsigned.apk"
        aligned = work / "aligned.apk"
        signed = work / "signed.apk"
        patcher = root.parent / "app端代码" / "local-patch" / "patch-unity-arena.py"
        overlay = work / "unity"
        command = [sys.executable, str(patcher), "--origin", str(base), "--output", str(overlay)]
        if args.python_libs is not None:
            command.extend(["--python-libs", str(args.python_libs.resolve())])
        subprocess.run(command, check=True)
        migration = patcher.with_name("patch_miao_species.py")
        command = [sys.executable, str(migration), "--origin", str(base), "--output", str(overlay)]
        if args.python_libs is not None:
            command.extend(["--python-libs", str(args.python_libs.resolve())])
        subprocess.run(command, check=True)
        direct = patcher.with_name("patch-unity-offline.py")
        stickers = patcher.with_name("patch_trainer_stickers.py")
        command = [sys.executable, str(stickers), "--origin", str(base), "--output", str(overlay)]
        if args.python_libs is not None:
            command.extend(["--python-libs", str(args.python_libs.resolve())])
        subprocess.run(command, check=True)
        command = [sys.executable, str(direct), "--origin", str(base), "--output", str(overlay)]
        if args.python_libs is not None:
            command.extend(["--python-libs", str(args.python_libs.resolve())])
        subprocess.run(command, check=True)
        entries.update({path.relative_to(overlay).as_posix(): path for path in overlay.rglob("*.ab")})
        index_digest = "assets/pokemon-local/unity-index.txt"
        entries[index_digest] = overlay / index_digest
        expected_hashes = rewrite_apk(base, unsigned, entries)
        subprocess.run([zipalign, "-P", "16", "-f", "4", unsigned, aligned], check=True)
        subprocess.run(
            apksigner + ["sign", "--ks", str(args.keystore.resolve()), "--ks-key-alias", "androiddebugkey",
                         "--ks-pass", "pass:android", "--key-pass", "pass:android",
                         "--out", str(signed), str(aligned)],
            check=True,
        )
        subprocess.run(apksigner + ["verify", "--verbose", "--print-certs", str(signed)], check=True)
        subprocess.run([zipalign, "-c", "-P", "16", "4", signed], check=True)
        verify_entries(signed, expected_hashes, entries)
        with args.output.open("xb") as target, signed.open("rb") as source:
            shutil.copyfileobj(source, target)
    print(f"{args.output.resolve()} SHA-256 {hash_file(args.output)}")


def main():
    parser = argparse.ArgumentParser(description="生成已签名并校验的守护小精灵本地版 APK")
    parser.add_argument("--entries", type=Path, required=True, help="客户端 DEX 与已验证的 Unity 补丁目录")
    parser.add_argument("--build-tools", type=Path, required=True, help="支持 16 KiB 对齐的 Android Build Tools 目录")
    parser.add_argument("--java", type=Path, required=True, help="可运行 apksigner 的 java.exe")
    parser.add_argument("--python-libs", type=Path, help="已有 pycryptodome 与 UnityPy 的 Python 依赖目录")
    parser.add_argument("--keystore", type=Path, default=Path(os.environ["USERPROFILE"]) / ".android/debug.keystore")
    parser.add_argument("--output", type=Path, required=True, help="app包/new 内的新 APK 文件")
    build(parser.parse_args())


if __name__ == "__main__":
    main()
