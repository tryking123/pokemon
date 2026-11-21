"""生成 Unity 本地配置入口与渠道角色统计补丁。"""

import argparse
import hashlib
import struct
from pathlib import Path
from zipfile import ZipFile


PATCH_DIR = Path(__file__).resolve().parent
ORIGIN = PATCH_DIR.parents[1] / "app包" / "origin" / "origin.apk"
METADATA_ENTRY = "assets/bin/Data/Managed/Metadata/global-metadata.dat"
ORIGIN_SHA256 = "f010a675dcb9ccacf899a2954e7e497be7f04c3ea94214140fa3434c817a7c45"
METADATA_SHA256 = "1e91f55802c661e6eea8f8cdef0509814360b0504a89abb7bc3989075adf4062"
ORIGINAL_URI = b"http://fkbb005cdn.17dreamfly.com/pkm2_bt_ds"
LOCAL_URI = b"http://127.0.0.1:8081"
CONFIG_URI_INDEX = 6438
STRING_USAGE = 0xA000324D

# 由各 ABI 的 LoadSetting.MoveNext 确认。含 GOT 文件偏移、指向的地址及字符串槽文件偏移。
ABI_CHECKS = {
    "arm64-v8a": (
        "2d049ac94229a128aaca4debae2415a90602561b1407d5ef1917d8d8c4891923",
        0x1B458C8, 0x1C2D388, 0x1C1D388, 8,
    ),
    "armeabi-v7a": (
        "e5cbb1ba20222bc538bef7a7fdbd55544d13aa7aff5171f6a944af0274006bff",
        0x18E0400, 0x18C33C0, 0x18C23C0, 4,
    ),
}

# SDKAdapter.Upload(string json, int type) 的首条指令，和相同 ABI 的立即返回指令。
ROLE_UPLOAD_PATCHES = {
    "arm64-v8a": (0x8C763C, bytes.fromhex("f90f1bf8"), bytes.fromhex("c0035fd6")),
    "armeabi-v7a": (0x6B64C4, bytes.fromhex("f04f2de9"), bytes.fromhex("1eff2fe1")),
}


def checksum(path):
    """分段读取 APK，避免把整个底包载入内存。"""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_abi(archive, abi, checks):
    """确认原生 ABI 仍通过已分析的槽引用同一个 metadata 字面量索引。"""
    digest, got, target, usage, word_size = checks
    library = archive.read(f"lib/{abi}/libil2cpp.so")
    got_target = int.from_bytes(library[got:got + word_size], "little")
    string_usage = int.from_bytes(library[usage:usage + word_size], "little")
    if (
        hashlib.sha256(library).hexdigest() != digest
        or library[:4] != b"\x7fELF"
        or got_target != target
        or string_usage != STRING_USAGE
        or ((string_usage & 0x1FFFFFFF) >> 1) != CONFIG_URI_INDEX
    ):
        raise ValueError(f"{abi} ConfigURI 引用与已核验底包不一致")
    print(f"{abi}: ConfigURI 字面量索引 {CONFIG_URI_INDEX}")
    return library


def patch_role_upload(abi, library):
    """跳过原渠道 SDK 的角色统计调用，保留 Unity 网关与本地角色逻辑。"""
    address, original, return_instruction = ROLE_UPLOAD_PATCHES[abi]
    end = address + len(original)
    if library[address:end] != original:
        raise ValueError(f"{abi} SDKAdapter.Upload 的首条指令不符")
    return library[:address] + return_instruction + library[end:]


def patch_metadata(data):
    """更新字面量字节和长度，保留字面量索引以及其余数据偏移。"""
    if hashlib.sha256(data).hexdigest() != METADATA_SHA256:
        raise ValueError("Unity metadata 与已核验底包不一致")
    magic, version, table, table_size, blob, blob_size = struct.unpack_from("<6I", data)
    if (
        (magic, version) != (0xFAB11BAF, 27)
        or table_size % 8
        or table + table_size != blob
        or blob + blob_size > len(data)
        or CONFIG_URI_INDEX >= table_size // 8
    ):
        raise ValueError("Unity metadata 字面量表结构不符")

    entry = table + CONFIG_URI_INDEX * 8
    length, offset = struct.unpack_from("<II", data, entry)
    address = blob + offset
    if (
        offset + length > blob_size
        or data[address:address + length] != ORIGINAL_URI
        or data.count(ORIGINAL_URI) != 1
        or len(LOCAL_URI) > length
    ):
        raise ValueError("底包 ConfigURI 字符串不符")
    for index, (other_length, other_offset) in enumerate(
        struct.iter_unpack("<II", data[table:blob])
    ):
        if index == CONFIG_URI_INDEX or other_length == 0:
            continue
        if other_offset < offset + length and offset < other_offset + other_length:
            raise ValueError("ConfigURI 字符串与其他字面量重叠")

    patched = bytearray(data)
    struct.pack_into("<I", patched, entry, len(LOCAL_URI))
    patched[address:address + length] = LOCAL_URI.ljust(length, b"\x00")
    return patched


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", type=Path, default=ORIGIN, help="原始 APK 路径")
    parser.add_argument(
        "--output-root", type=Path, default=PATCH_DIR / "apk-entries",
        help="APK 替换条目目录",
    )
    args = parser.parse_args()
    if checksum(args.origin) != ORIGIN_SHA256:
        raise ValueError("底包 APK 的 SHA-256 不符")
    with ZipFile(args.origin) as archive:
        entries = {}
        for abi, checks in ABI_CHECKS.items():
            original = verify_abi(archive, abi, checks)
            entries[f"lib/{abi}/libil2cpp.so"] = patch_role_upload(abi, original)
        entries[METADATA_ENTRY] = patch_metadata(archive.read(METADATA_ENTRY))

    for entry, patched in entries.items():
        output = args.output_root / entry
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(patched)
        print(f"{hashlib.sha256(patched).hexdigest()}  {output}")


if __name__ == "__main__":
    main()
