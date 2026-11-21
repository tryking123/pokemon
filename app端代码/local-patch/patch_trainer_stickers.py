"""恢复训练家装备详情中原有的贴纸入口与刻印列表，保留原点击及刻印流程。"""
import argparse
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BASE_SHA = 'f010a675dcb9ccacf899a2954e7e497be7f04c3ea94214140fa3434c817a7c45'
TARGETS = {-7490504986454826: 'Mark', -4811381391263081092: 'SuitList'}


def patch(origin, output):
    """按固定预制体节点恢复显示，并回读核对保存后的控件状态。"""
    import UnityPy
    from apk_table_tools import ApkAssets, file_sha
    assert file_sha(origin) == BASE_SHA, '底包摘要不符'
    assets = ApkAssets(origin, output)
    bundle = UnityPy.load(assets.read('modules_bag.ab'))
    found = set()
    for obj in bundle.objects:
        if obj.path_id in TARGETS:
            assert obj.type.name == 'GameObject'
            node = obj.read()
            assert node.m_Name == TARGETS[obj.path_id]
            node.m_IsActive = True
            node.save()
            found.add(obj.path_id)
    assert found == set(TARGETS), '原刻印预制体节点不完整'
    encoded = bundle.file.save()
    check = UnityPy.load(encoded)
    assert all(obj.read().m_IsActive for obj in check.objects if obj.path_id in TARGETS)
    assets.replace('modules_bag.ab', encoded)
    print(assets.save())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', type=Path, default=ROOT/'app包/origin/origin.apk')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--python-libs', type=Path)
    args = parser.parse_args()
    if args.python_libs: sys.path.insert(0, str(args.python_libs))
    patch(args.origin, args.output)
