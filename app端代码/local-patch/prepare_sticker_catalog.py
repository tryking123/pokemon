"""按固定底包补齐可发放贴纸目录，保留其他类型及同名的不同贴纸模板。"""
import argparse
import json
from pathlib import Path
from apk_table_tools import ApkAssets, decode_table, file_sha

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BASE_SHA = 'f010a675dcb9ccacf899a2954e7e497be7f04c3ea94214140fa3434c817a7c45'


def prepare(origin, catalog):
    """核对双架构模板与当前离线规则，再合并原 type12 目录。"""
    assert file_sha(origin) == BASE_SHA, '底包摘要不符'
    assets = ApkAssets(origin)
    tables = {}
    for name in ['suitname', 'word1']:
        versions = [decode_table(assets.read(f'lua{abi}_database_{name}_proto.lua.bytes')) for abi in [32, 64]]
        assert versions[0] == versions[1], f'{name} 双架构不一致'
        tables[name] = versions[1]
    rules = json.loads((HERE/'offline/resources/catalog/trainer-runes.json').read_text(encoding='utf-8'))
    assert set(tables['suitname']) == set(rules['stickers']), '运行贴纸目录不完整'
    stickers = []
    for sid, row in sorted(tables['suitname'].items(), key=lambda pair: int(pair[0])):
        assert rules['stickers'][sid] == {'set': row['suitid'], 'level': row['needlv'], 'split': row['fenjie']}
        stickers.append({'id': int(sid), 'type': 12, 'name': tables['word1'][str(row['name'])]['data1'],
                         'category': '贴纸', 'quality': row['pinzhi'], 'stickerLevel': row['needlv'],
                         'setId': row['suitid'], 'grantable': True})
    data = json.loads(catalog.read_text(encoding='utf-8'))
    retained = [row for row in data['items'] if row['type'] != 12]
    data['items'] = retained + stickers
    data['version'] = max(3, data.get('version', 1))
    data.setdefault('source', {})['stickers'] = {'apkSha256': BASE_SHA, 'table': 'suitname_proto / word1_proto',
                                                'count': len(stickers), 'assetType': 12, 'sameNamePolicy': '保留不同模板编号'}
    assert len({(row['type'], row['id']) for row in data['items']}) == len(data['items']), '发放目录存在重复类型与编号'
    catalog.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'stickers': len(stickers), 'otherEntriesPreserved': len(retained)}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', type=Path, default=ROOT/'app包/origin/origin.apk')
    parser.add_argument('--catalog', type=Path, default=HERE/'offline/resources/catalog/grant-mail-items.json')
    args = parser.parse_args()
    prepare(args.origin, args.catalog)
