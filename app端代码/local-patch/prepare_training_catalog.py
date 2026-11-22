"""按固定底包和已核对迁移表核查培养配置，恢复缺失的突破、觉醒档位与被动。"""
import argparse
import copy
import json
from pathlib import Path
from apk_table_tools import ApkAssets, decode_table, file_sha, sequence

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
RESOURCE = HERE / 'offline/resources/elf/training.json'
BASE_SHA = 'f010a675dcb9ccacf899a2954e7e497be7f04c3ea94214140fa3434c817a7c45'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def audit(origin, restore, report):
    """对照原表语义审计，保留玩家存档和所有已存在费用。"""
    if file_sha(origin) != BASE_SHA:
        raise ValueError('底包 SHA-256 不符')
    assets = ApkAssets(origin)
    tables = {}
    abi_equal = {}
    for name in ['jingling', 'jinhua', 'variable', 'break', 'skill']:
        a = decode_table(assets.read(f'lua64_database_{name}_proto.lua.bytes'))
        b = decode_table(assets.read(f'lua32_database_{name}_proto.lua.bytes'))
        abi_equal[name] = a == b
        if a != b:
            raise ValueError('双架构原表不一致：' + name)
        tables[name] = a
    patch = read(HERE / 'miao-import/species-patch.json')
    if patch['originSha256'] != BASE_SHA:
        raise ValueError('迁移表底包不符')
    for name in ['jingling', 'jinhua', 'skill']:
        tables[name].update(patch['tables'].get(name, {}))
    local = read(RESOURCE)
    skills = read(HERE / 'offline/resources/elf/skill-bar.json')
    triggers = set(read(HERE / 'offline/resources/elf/character.json')['validTriggers'])
    mismatches, references, repaired, missing_attributes = [], [], [], []
    empty_awaken, source_exclusions = [], []
    missing_passives = []

    def same(section, key, field, actual, expected):
        if actual != expected:
            mismatches.append({'section': section, 'id': key, 'field': field, 'actual': actual, 'expected': expected})

    scalar_fields = ['quality', 'assess', 'maxstar', 'maxawaken', 'all_up', 'attribute1', 'attribute2',
                     'stat_hp', 'stat_physical_attack', 'stat_special_attack', 'stat_physical_defense',
                     'stat_special_defense', 'stat_speed', 'risingstar', 'starsprint', 'charactername']
    per_species = []
    for sid, species in local['species'].items():
        source = tables['jingling'].get(sid)
        if source is None:
            references.append({'species': sid, 'field': 'source', 'target': sid})
            continue
        for field in scalar_fields:
            same('species', sid, field, species.get(field), source.get(field))
        for dest, src in [('skills', 'skill1'), ('loveitem', 'loveitem'), ('evolution', 'changeid')]:
            same('species', sid, dest, species.get(dest), sequence(source.get(src, {'1': 0})))
        awake_expected = sequence(source.get('skill2', {}))
        if sid not in skills['awaken'] and awake_expected in ([], [0]):
            empty_awaken.append(int(sid))
        else:
            same('awakenSkills', sid, 'awaken', skills['awaken'].get(sid), awake_expected)
        passive = sequence(source.get('skill3', {}))
        if passive == [0]:
            passive = []
        if len(passive) % 2 or any(trigger not in triggers for trigger in passive[1::2]):
            raise ValueError(f'物种 {sid} 的觉醒被动档位或触发编号无效：{passive}')
        if passive and 'passive' not in species:
            missing_passives.append(int(sid))
            if restore:
                species['passive'] = passive
        same('species', sid, 'passive', species.get('passive', []), passive)
        for field in ['risingstar', 'starsprint']:
            target = species.get(field, 0)
            if target and str(target) not in local['species']:
                references.append({'species': sid, 'field': field, 'target': target})
        for target in species.get('evolution', []):
            if target and str(target) not in local['species']:
                references.append({'species': sid, 'field': 'evolution', 'target': target})
        for field, rows in [('skills', species.get('skills', [])), ('awaken', skills['awaken'].get(sid, []))]:
            for skill in rows[1::2]:
                if str(skill) not in tables['skill']:
                    references.append({'species': sid, 'field': field, 'target': skill})
        maximum = species.get('maxstar', 0)
        for star in range(2, maximum + 1):
            if str(1019 + star) not in local['variable']:
                references.append({'species': sid, 'field': 'maxstarRule', 'target': 1019 + star})
        for step in range(1, species.get('maxawaken', 0) + 1):
            if str(2213 + step) not in local['variable']:
                references.append({'species': sid, 'field': 'maxawakenRule', 'target': 2213 + step})
        if sid not in local['growth']:
            record = {'species': sid, 'field': 'growth', 'target': sid}
            if sid not in tables['jinhua']:
                source_exclusions.append(record)
            else:
                references.append(record)
        per_species.append({'id': int(sid), 'quality': species.get('quality'), 'assess': species.get('assess'),
                            'maxstar': maximum, 'maxawaken': species.get('maxawaken'),
                            'evolution': species.get('evolution'), 'risingstar': species.get('risingstar'),
                            'starsprint': species.get('starsprint'), 'awakenSkills': skills['awaken'].get(sid)})
    for key, row in local['growth'].items():
        source = tables['jinhua'].get(key)
        if source is None:
            references.append({'growth': key, 'field': 'source', 'target': key})
            continue
        for field in ['needlv', 'needlove', 'upgrade_limit']:
            same('growth', key, field, row.get(field), source.get(field))
        same('growth', key, 'evolution', row.get('evolution'), sequence(source.get('changeid', {'1': 0})))
        expected_cost = [{'items': sequence(source.get('needitem' + str(i), {'1': 0})),
                          'coins': source.get('needmoney' + str(i), 0)} for i in range(1, 11)]
        same('growth', key, 'cost', row.get('cost'), expected_cost)
    missing_stage_variables = []
    for key in range(3810, 3822):
        if str(key) not in local['variable']:
            missing_stage_variables.append(key)
            if restore:
                local['variable'][str(key)] = {'data1': tables['variable'][str(key)]['data1']}
    for key, row in local['variable'].items():
        source = tables['variable'].get(key, {})
        for field, value in row.items():
            same('variable', key, field, value, source.get(field))
    for key, row in local['break'].items():
        source = tables['break'][key]
        for field, value in row.items():
            same('break', key, field, value, sequence(source[field]) if field == 'attributes' else source.get(field))
        if 'attributes' not in row:
            missing_attributes.append(int(key))
            if restore:
                row['attributes'] = sequence(source['attributes'])
                repaired.append(int(key))
    if restore and (repaired or missing_stage_variables or missing_passives):
        write(RESOURCE, local)
    result = {'version': 1, 'date': '2026-11-22', 'originSha256': BASE_SHA,
              'sources': ['固定底包', 'miao-import/species-patch.json'], 'abiEqual': abi_equal,
              'counts': {'species': len(local['species']), 'growth': len(local['growth']), 'break': len(local['break']),
                         'awakenSkills': len(skills['awaken'])},
              'sourceDifferences': mismatches, 'unresolvedReferences': references,
              'omittedEmptyAwakenSkills': empty_awaken, 'growthAbsentInSource': source_exclusions,
              'missingStageVariablesBefore': missing_stage_variables,
              'missingAwakenPassivesBefore': missing_passives,
              'stageVariablesMissingAfter': [k for k in range(3810,3822) if str(k) not in local['variable']],
              'missingBreakAttributesBefore': missing_attributes, 'restoredBreakAttributes': repaired,
              'breakAttributeMissingAfter': [int(k) for k, r in local['break'].items() if 'attributes' not in r],
              'species': per_species,
              'jirachi': {'species': local['species']['63006'], 'growth': local['growth']['63006'],
                          'target': 64058, 'targetGrowth': local['growth']['64058'],
                          'effectiveAdvanceGrowth': local['growth']['64058'],
                          'interpretation': '原UI取目标64058：9进阶、亲密40、61级；源63006的零门槛只提供索引，不能当实际免费进化规则'},
              'ditto': {'species': local['species']['64190'], 'awake4': local['variable']['2217'],
                        'substituteCard': 21090, 'sourceDifference': False},
              'limits': ['纯配置静态审计不等于所有Unity按钮与Android事务已回归',
                         '恢复attributes不改变Java执行路径；不得与Unity原计算重复增加']}
    write(report, result)
    print(json.dumps({k: result[k] for k in ['counts', 'abiEqual']}, ensure_ascii=False))
    print('source differences', len(mismatches), 'unresolved references', len(references),
          'restored attributes', len(repaired), 'remaining missing', len(result['breakAttributeMissingAfter']))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', type=Path, default=ROOT / 'app包/origin/origin.apk')
    parser.add_argument('--restore-missing', action='store_true')
    parser.add_argument('--report', type=Path, default=ROOT / '全局数值规划统筹/精灵培养全量配置审计_20261122.json')
    args = parser.parse_args()
    audit(args.origin, args.restore_missing, args.report)
