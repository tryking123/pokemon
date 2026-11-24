"""交付前核对已接入的助战和属性面板，阻止旧源码重建导致功能回退。"""
import argparse
import json
from pathlib import Path
import re
import sys
from zipfile import ZipFile


def verify(apk):
    """检查 APK 的业务、协议、运行配置及双 ABI 功能边界。"""
    import UnityPy
    from apk_table_tools import ApkAssets

    with ZipFile(apk) as archive:
        dex = archive.read('classes9.dex')
        for marker in (b'AssistBattleService', b'battleAssists', b'assist_slot_value'):
            if marker not in dex:
                raise ValueError('助战业务缺失，禁止交付旧源码重建包：' + marker.decode())
        components = archive.read('assets/pokemon-local/components.txt')
        if b'com.pokemon.offline.battle.AssistBattleService' not in components:
            raise ValueError('助战业务未注册')
        schema = archive.read('assets/pokemon-local/protocol/gateway.sproto.txt')
        for marker in (b'assists:8:*pokemon_t', b'assist_slot_type:112:integer',
                       b'assist_slot_percent:113:integer', b'assist_slot_value:114:integer'):
            if marker not in schema:
                raise ValueError('助战协议缺失：' + marker.decode())
        slots = json.loads(archive.read('assets/pokemon-local/battle/assist-slots.json'))['slots']
        if set(slots) != {str(i) for i in range(1001, 1025)}:
            raise ValueError('助战二十四槽配置不完整')
    assets = ApkAssets(apk)
    for abi in (32, 64):
        files = {}
        for family in ('module_battle', 'network'):
            bundle = UnityPy.load(assets.read(f'lua{abi}_{family}.ab'))
            for obj in bundle.objects:
                if obj.type.name == 'TextAsset':
                    item = obj.read()
                    files[item.m_Name.removesuffix('.bytes')] = item.m_Script.encode('utf8', 'surrogateescape')
        checks = {'formation.lua': (b'assistSources', b'slotBaseBonuses', b'assistEffectSource', b'target.isAssist', b'crownRound', b'function formation.allready', b'addTypeAuras', b'triggerHistory', b'effectTalent'),
                  'battle_ctrl.lua': (b'local_battle_attributes', b"button('Assists'", b"button('Talents'"),
                  'typedef.lua': (b'assist_slot_type', b'assist_slot_percent', b'assist_slot_value')}
        for name, markers in checks.items():
            for marker in markers:
                if marker not in files.get(name, b''):
                    raise ValueError(f'{abi} 位 {name} 功能缺失：{marker!r}')
        if not re.search(rb'assists\s+8\s*:\s*\*pokemon_t', files['typedef.lua']):
            raise ValueError(f'{abi} 位助战阵容协议缺失')
    print('助战业务、二十四槽配置、双 ABI 协议/战斗/属性面板交付检查通过')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--python-libs', type=Path)
    args = parser.parse_args()
    if args.python_libs:
        sys.path.insert(0, str(args.python_libs.resolve()))
    verify(args.apk)
