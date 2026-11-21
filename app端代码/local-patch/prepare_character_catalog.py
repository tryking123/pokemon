"""从固定底包及已核验迁移表恢复特性编号，文字编号不参与战斗。"""
import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--python-libs', type=Path)
    args = parser.parse_args()
    if args.python_libs:
        sys.path.insert(0, str(args.python_libs.resolve()))
    from apk_table_tools import ApkAssets, decode_table, file_sha, sequence
    origin = HERE.parents[1] / 'app包/origin/origin.apk'
    assert file_sha(origin) == 'f010a675dcb9ccacf899a2954e7e497be7f04c3ea94214140fa3434c817a7c45'
    assets = ApkAssets(origin)
    tables = {name: decode_table(assets.read('lua64_database_' + name + '_proto.lua.bytes'))
              for name in ['jingling', 'character', 'character2']}
    plan = json.loads((HERE / 'miao-import/species-patch.json').read_text(encoding='utf-8'))
    for name in tables:
        tables[name].update(plan['tables'].get(name, {}))
    path = HERE / 'offline/resources/elf/character.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    data['notes'] = '特性使用 jingling_proto.character 的实际被动编号；charactername 仅是文字编号。species 补齐旧培养表省略的特性数组，validTriggers 来自同版本 character/character2 表及已核验迁移表。只有存在的被动可进入开战 trigger；rules 保留明确配置的属性与触发映射。'
    data['sourceSha256'] = file_sha(origin)
    data['species'] = {key: sequence(row.get('character', {})) for key, row in tables['jingling'].items()}
    data['featuresLv'] = {key: row.get('featureslv', 0) for key, row in tables['jingling'].items()}
    data['validTriggers'] = sorted({int(key) for name in ['character', 'character2'] for key in tables[name]})
    assert all(value in data['validTriggers'] for row in data['species'].values() for value in row if value > 0)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('特性物种:', len(data['species']), '有效被动:', len(data['validTriggers']))


if __name__ == '__main__':
    main()
