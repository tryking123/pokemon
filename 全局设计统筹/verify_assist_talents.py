"""在 APK 的真实 Formation/Stage 中回灌六名出战与二十四名助战。"""
import argparse
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def execute(bundle, libs, baseline):
    sys.path.insert(0, str(libs))
    from lupa.luajit21 import LuaRuntime

    lua = LuaRuntime(encoding=None, unpack_returned_tuples=True)
    with zipfile.ZipFile(bundle) as archive:
        scripts = {name[4:].encode(): archive.read(name) for name in archive.namelist()
                   if name.startswith("lua/")}
        tables = {name[5:].encode(): lua.execute(archive.read(name)) for name in archive.namelist()
                  if name.startswith("data/")}
    for key, row in tables[b"character2_proto"].items():
        tables[b"character_proto"][key] = row
    lua.globals().scripts = lua.table_from(scripts)
    lua.globals().tables = lua.table_from(tables)
    result = lua.execute(Path(__file__).with_suffix(".lua").read_bytes(), baseline)
    print(result.decode())


def verify(args):
    root = Path(__file__).resolve().parents[1]
    tooling = args.tools.resolve()
    sys.path[:0] = [str(tooling / "offline-pylibs"), str(root / "app端代码/local-patch")]
    import UnityPy
    from apk_table_tools import ApkAssets

    apk = ApkAssets(args.apk)
    runners = [(64, sys.executable)]
    if args.python32:
        runners.append((32, str(args.python32.resolve())))
    for abi, interpreter in runners:
        scripts = {}
        for family in ("module_battle", "helper"):
            bundle = UnityPy.load(apk.read(f"lua{abi}_{family}.ab"))
            for key, obj in bundle.container.items():
                if obj.type.name == "TextAsset":
                    short = key.split(f"/lua{abi}/")[1].removesuffix(".lua.bytes").encode()
                    scripts[short] = obj.read().m_Script.encode("utf8", "surrogateescape")
        if args.patch:
            from importlib import import_module
            from patch_team_talents import patch_team_talents

            key = b"module/battle/core/formation"
            scripts[key] = patch_team_talents(scripts[key], import_module("patch-unity-arena"))
        prefix = f"lua{abi}_database_"
        resources = {name.removeprefix(prefix).removesuffix(".lua.bytes").encode(): apk.read(name)
                     for name in apk.index if name.startswith(prefix)}
        with tempfile.TemporaryDirectory() as temp:
            snapshot = Path(temp) / "engine.zip"
            with zipfile.ZipFile(snapshot, "w") as archive:
                for key, data in scripts.items():
                    archive.writestr("lua/" + key.decode(), data)
                for key, data in resources.items():
                    archive.writestr("data/" + key.decode(), data)
            cmd = [interpreter, "-X", "utf8", str(Path(__file__).resolve()), "--worker", str(snapshot),
                   "--tools", str(tooling)]
            if args.baseline:
                cmd.append("--baseline")
            print(f"{abi} 位 {args.apk.name}", flush=True)
            subprocess.run(cmd, check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, nargs="?")
    parser.add_argument("--tools", type=Path, default=Path(__file__).resolve().parents[1] / ".tools")
    parser.add_argument("--python32", type=Path, help="带 32 位 lupa.luajit21 的 Python")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--baseline", action="store_true", help="验证未改包的小软个人与队友效果")
    mode.add_argument("--patch", action="store_true", help="对已有团队属性补丁的 APK 内存加载当前助战实现")
    parser.add_argument("--worker", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.worker:
        # 32 位嵌入式 Python 自带对应的 lupa，不能混入 64 位扩展。
        path = args.tools / "lua-qa" if sys.maxsize > 2**32 else Path(sys.executable).parent
        execute(args.worker, path, args.baseline)
    elif args.apk:
        verify(args)
    else:
        parser.error("请选择待验证的 APK")
