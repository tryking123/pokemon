"""将喵星联盟指定精灵及战斗闭包叠加到固定底包，保留旧 ID 和存档。"""
import argparse,copy,hashlib,importlib.util,json,sys
from pathlib import Path
from apk_table_tools import ApkAssets,decode_table,file_sha,stable,sequence,patch_table

HERE=Path(__file__).resolve().parent
ROOT=HERE.parent.parent
RES=HERE/'offline/resources'
IMPORT=HERE/'miao-import'
BASE_SHA='f010a675dcb9ccacf899a2954e7e497be7f04c3ea94214140fa3434c817a7c45'
DONOR_SHA='fa0e3709c212817ab60ed10a3f944f6c449dd587cdea4d03c4e6d806f4d49b8c'
SPECIES={64188:64190,64189:64189,641891:641891,641892:641892,641893:641893}
GROWTH={64188:64190,641880:641900,64189:64189,641890:641890}
NAMES={64188:'真·百变怪',64189:'小软',641891:'10%小软',641892:'50%小软',641893:'100%小软'}

def load_module(path,name):
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);sys.modules[name]=module;spec.loader.exec_module(module);return module
def read_json(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def write_json(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def create_plan(origin,donor):
    """复原指定表并为所有语义冲突分配独立编号，不覆盖旧行。"""
    assert file_sha(origin)==BASE_SHA and file_sha(donor)==DONOR_SHA
    a,b=ApkAssets(origin),ApkAssets(donor)
    names=['jingling','jinhua','character','character2','ditto','word1','skill']
    old={n:decode_table(a.read(f'lua64_database_{n}_proto.lua.bytes')) for n in names}
    source={n:decode_table(b.read(f'lua64_database_{n}_proto.lua.bytes')) for n in names}
    for n in names:
        assert decode_table(b.read(f'lua32_database_{n}_proto.lua.bytes'))==source[n],n
    additions={n:{} for n in names};wordmap={};nextword=6000000
    def word(id,fallback=None):
        nonlocal nextword
        if id is None:
            assert fallback;key=nextword;nextword+=1;additions['word1'][str(key)]={'data1':fallback};return key
        id=int(id)
        if id==0:return 0
        if id in wordmap:return wordmap[id]
        row=source['word1'].get(str(id));assert row is not None,('missing source text',id)
        existing=old['word1'].get(str(id))
        if existing is None:target=id
        elif existing.get('data1')==row.get('data1'):wordmap[id]=id;return id
        else:
            while str(nextword) in old['word1'] or str(nextword) in source['word1']:nextword+=1
            target=nextword;nextword+=1
        additions['word1'][str(target)]=copy.deepcopy(row);wordmap[id]=target;return target
    def textfields(row,fields):
        for key in fields:
            if key in row:row[key]=word(row[key])
    # 37400 在底包是旧精灵的另一套被动，另分能力编号保留原值。
    ability_map={37400:6001000}
    assert '6001000' not in old['character'] and '6001000' not in old['character2']
    for sid,target in SPECIES.items():
        assert str(target) not in old['jingling']
        row=copy.deepcopy(source['jingling'][str(sid)])
        # 供体的评价编号 8 在固定底包表示 S+；这些粉色精灵统一适配为底包 SSS。
        row['assess']=11
        row['name']=word(row.get('name'),NAMES[sid]);textfields(row,['charactername'])
        if 'word' in row:row['word']={k:word(v) for k,v in row['word'].items()}
        for key in ['jinhua2','changeid']:
            if key in row:row[key]={k:SPECIES.get(v,v) for k,v in row[key].items()}
        for key in ['risingstar','starsprint']:
            if key in row:row[key]=SPECIES.get(row[key],row[key])
        if 'skill3' in row:row['skill3']={k:ability_map.get(v,v) if int(k)%2==0 else v for k,v in row['skill3'].items()}
        additions['jingling'][str(target)]=row
    for sid,target in GROWTH.items():
        assert str(target) not in old['jinhua'];additions['jinhua'][str(target)]=copy.deepcopy(source['jinhua'][str(sid)])
    for id in [34400,36000,37400]:
        row=copy.deepcopy(source['character'][str(id)]);target=ability_map.get(id,id)
        if target==id and row==old['character'].get(str(id)):continue
        textfields(row,['name','description']);additions['character'][str(target)]=row
    for key,value in source['character2'].items():
        if not 104100<=int(key)<=105910:continue
        assert key not in old['character'] and key not in old['character2']
        row=copy.deepcopy(value);textfields(row,['name','description']);additions['character2'][key]=row
    # 可上阵精灵的每组星级特性均须带齐全部档位，包括最后一组。
    for sid in [64190,64189]:
        species=additions['jingling'][str(sid)]
        for base in sequence(species['character']):
            for rank in range(species['featureslv']+1):
                assert str(base+rank) in additions['character2'], (sid,base,rank)
    for key in ['1007','1008','1009']:
        assert key not in old['ditto'];additions['ditto'][key]=source['ditto'][key]
    # 隐藏技能面板也会读取古代技能的图标；缺行会中断整个精灵页刷新。
    for skill_id in [70010,70014]:
        key=str(skill_id)
        assert key not in old['skill'], ('古代技能编号冲突', skill_id)
        row=copy.deepcopy(source['skill'][key])
        textfields(row,['name','description','typedes','life','attack2'])
        additions['skill'][key]=row
    assert old['jingling']['64188']['bz']=='密勒顿'
    return {'version':1,'originSha256':BASE_SHA,'donorSha256':DONOR_SHA,'speciesIdMap':SPECIES,'growthIdMap':GROWTH,'textIdMap':wordmap,'abilityIdMap':ability_map,'tables':additions,'grantable':[64190,64189],'battleOnly':[641891,641892,641893]}

def prepare(origin,donor):
    """生成 App 物种、技能、成长和两种可发放精灵的权威离线配置。"""
    plan=create_plan(origin,donor);IMPORT.mkdir(exist_ok=True);write_json(IMPORT/'species-patch.json',plan)
    tables=plan['tables'];training=read_json(RES/'elf/training.json');bar=read_json(RES/'elf/skill-bar.json');release=read_json(RES/'elf/release-crystals.json');breed=read_json(RES/'home/breeding.json');dyna=read_json(RES/'elf/dynamax.json')
    old_miraidon=stable(training['species']['64188'])
    for key,row in tables['jingling'].items():
        species={k:copy.deepcopy(v) for k,v in row.items() if not isinstance(v,dict)}
        species.update({'skills':sequence(row.get('skill1',{})),'loveitem':sequence(row.get('loveitem',{})),'evolution':sequence(row.get('changeid',{'1':0})),'character':sequence(row.get('character',{})),'passive':sequence(row.get('skill3',{}))})
        training['species'][key]=species;bar['awaken'][key]=sequence(row.get('skill2',{}));release[key]=[row.get('redstone',0),row.get('bluestone',0)];breed['species'][key]=[row.get('male',500),row.get('egg',0),row.get('all_up',0),row.get('assess',0)]
        if row.get('dynamax'):dyna['routes'][key]=sequence(row['dynamax'])
    for key,row in tables['jinhua'].items():
        training['growth'][key]={k:row[k] for k in ['needlv','needlove','upgrade_limit']}
        training['growth'][key]['evolution']=sequence(row.get('changeid',{'1':0}))
        training['growth'][key]['cost']=[{'items':sequence(row.get('needitem'+str(i),{'1':0})),'coins':row.get('needmoney'+str(i),0)} for i in range(1,11)]
    assert stable(training['species']['64188'])==old_miraidon
    for path,data in [('elf/training.json',training),('elf/skill-bar.json',bar),('elf/release-crystals.json',release),('home/breeding.json',breed),('elf/dynamax.json',dyna)]:write_json(RES/path,data)
    catalog=read_json(RES/'catalog/grant-mail-items.json')
    grantables=set(plan['grantable']);internal=set(plan['battleOnly'])
    assert not any(x.get('category')=='精灵' and x.get('id') in internal for x in catalog['items'])
    catalog['items']=[x for x in catalog['items'] if not (x.get('category')=='精灵' and x.get('id') in grantables)]
    for sid in plan['grantable']:catalog['items'].append({'category':'精灵','id':sid,'name':NAMES[64188 if sid==64190 else sid],'type':2,'grantable':True})
    catalog['source']['miaoImport']={'sha256':DONOR_SHA,'grantableSpecies':plan['grantable'],'battleOnlySpecies':plan['battleOnly']}
    write_json(RES/'catalog/grant-mail-items.json',catalog)
    exchange=read_json(RES/'activity/score-exchange.json');previous=exchange['rebate'][:19]
    assert len(previous)==19 and not any(row['reward'][1] in [*grantables,*internal] for row in previous)
    exchange['rebate']=previous+[{'cost':100,'limit':0,'reward':[2,sid,1]} for sid in plan['grantable']]
    write_json(RES/'activity/score-exchange.json',exchange)
    print('PREPARED',len(tables['jingling']),'species;',sum(len(v) for v in tables.values()),'table rows; 2 grantable, 3 battle-only')

def apply(origin,donor,output):
    """叠加迁移数据、已核验战斗函数及资源，再重算版本与分段索引。"""
    assert file_sha(origin)==BASE_SHA and file_sha(donor)==DONOR_SHA
    import UnityPy
    helpers=load_module(HERE/'patch-unity-arena.py','miao_arena_helpers')
    combat=load_module(HERE/'patch_miao_battle.py','miao_combat_helpers')
    assets=ApkAssets(origin,output);source=ApkAssets(donor);plan=read_json(IMPORT/'species-patch.json');tables=copy.deepcopy(plan['tables'])
    cosmetic_file=IMPORT/'cosmetics-patch.json'
    if cosmetic_file.exists():
        for table,rows in read_json(cosmetic_file)['tables'].items():
            target=tables.setdefault(table,{})
            for key,row in rows.items():assert key not in target;target[key]=row
    from patch_team_catalog import corrected_team_rows
    proofs={}
    for abi in [32,64]:
        for table,rows in tables.items():
            if not rows:continue
            name=f'lua{abi}_database_{table}_proto.lua.bytes'
            raw=assets.read(name)
            if table=='character':
                corrected=corrected_team_rows(raw)
                assert not rows.keys() & corrected.keys()
                rows={**rows,**corrected}
            assets.replace(name,patch_table(raw,rows,helpers))
        name=f'lua{abi}_module_battle.ab';env=UnityPy.load(assets.read(name));donor_env=UnityPy.load(source.read(name));replaced={}
        for script,transform in [('formation.lua.bytes',combat.patch_formation),('stage.lua.bytes',combat.patch_stage)]:
            path,obj=next((p,o) for p,o in env.container.items() if p.endswith('/'+script));a=obj.read();d=next(o for p,o in donor_env.container.items() if p.endswith('/'+script)).read()
            data,proof=transform(a.m_Script.encode('utf-8','surrogateescape'),d.m_Script.encode('utf-8','surrogateescape'));a.m_Script=data.decode('utf-8','surrogateescape');a.save();replaced[script]=hashlib.sha256(data).hexdigest();proofs[f'{abi}/{script}']=proof
        assets.replace(name,env.file.save(packer='lz4'))
    from patch_imported_images import add_images
    image_proof={}
    # 79414 未被原包任何精灵引用；原路径为未启用的旧图，按迁入小软替换该单个 Sprite，不改其共享图集。
    payload,proof=add_images(assets.read('sprites_iconpkm.ab'),source.read('sprites_iconpkm.ab'),['assets/game/assets/sprites/iconpkm/79414.png'],UnityPy,allow_replacement=True)
    assets.replace('sprites_iconpkm.ab',payload);image_proof['small-soft-icon']=proof
    if cosmetic_file.exists():
        cosmetic=read_json(cosmetic_file);donors={donor.name:source};groups={}
        for item in cosmetic['sourceMap']:
            filename=item['sourceApk']
            if filename not in donors:
                path=ROOT/'app包/origin'/filename;assert file_sha(path)==cosmetic['proof']['sourceApkSha256'][filename];donors[filename]=ApkAssets(path)
            for asset in item['resource']['assets']:
                if not asset['bundle'].startswith('sprites'):continue
                if 'path' in asset:groups.setdefault((filename,asset['bundle']),[]).append(asset['path'])
                else:
                    prefix=asset.get('prefix',asset.get('directory'));assert prefix,asset
                    env=UnityPy.load(donors[filename].read(asset['bundle']))
                    paths=sorted(set(path for path in env.container if path.startswith(prefix)))
                    groups.setdefault((filename,asset['bundle']),[]).extend(paths)
        for (filename,bundle),paths in groups.items():
            # 这些展示编号不被原包的有效称号/皮肤目录引用，只更新新接入外观的单个图像，不动共享图集。
            payload,proof=add_images(assets.read(bundle),donors[filename].read(bundle),sorted(set(paths)),UnityPy,allow_replacement=True);assets.replace(bundle,payload);image_proof[filename+'/'+bundle]=proof
        from patch_title_prefabs import add_title_prefabs
        title_groups={}
        for item in cosmetic['sourceMap']:
            if item['kind']=='title':title_groups.setdefault(item['sourceApk'],[]).append(item['resource']['show'])
        for filename,shows in title_groups.items():
            before=assets.read('modules_titleshow.ab')
            payload,proof=add_title_prefabs(before,donors[filename].read('modules_titleshow.ab'),assets.read('sprites_icontitle.ab'),donors[filename].read('sprites_icontitle.ab'),UnityPy,shows)
            if payload!=before:assets.replace('modules_titleshow.ab',payload)
            image_proof[filename+'/title-prefabs']=proof
    hashes=assets.save();write_json(Path(output)/'miao-import-proof.json',{'sourceSha256':DONOR_SHA,'species':SPECIES,'combat':proofs,'images':image_proof,'entries':hashes})
    print('MIAO OVERLAY',len(hashes),'logical entries')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--origin',type=Path,default=ROOT/'app包/origin/origin.apk');p.add_argument('--donor',type=Path,default=ROOT/'app包/origin/喵星联盟_001.apk.1');p.add_argument('--output',type=Path);p.add_argument('--python-libs',type=Path);p.add_argument('--prepare-only',action='store_true');args=p.parse_args()
    if args.python_libs:sys.path.insert(0,str(args.python_libs.resolve()))
    if args.prepare_only:prepare(args.origin,args.donor)
    else:
        if args.output is None:p.error('资源叠加需要 --output')
        apply(args.origin,args.donor,args.output)
if __name__=='__main__':main()
