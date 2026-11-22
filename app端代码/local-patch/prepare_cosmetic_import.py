"""从已核验安装包生成缺失称号、时装的去重增量及 App 离线持有目录。"""
import argparse
import copy
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
EXPECTED = {
 'origin.apk':'f010a675dcb9ccacf899a2954e7e497be7f04c3ea94214140fa3434c817a7c45',
 '喵星联盟_001.apk.1':'fa0e3709c212817ab60ed10a3f944f6c449dd587cdea4d03c4e6d806f4d49b8c',
 '天天驯兽师_001.apk.1':'a8aded8d963ef0c44a803e2a739e3f1589604a346897cddd321d3c2cef869a37',
 '天天驯兽师_002.apk.1':'ae2a996f56f1a19e1be92becedc2d4c2b5a53d6bf84026aa083bbc7fc642628e',
 '天天驯兽师_003.apk.1':'1607bea776b2cb41130c27163629f903b2193e1538678867661e790cc31fae88',
 '天天驯兽师_004.apk.1':'675b455c9bd89aca5e76778499edaef2ca945c65de1ab77d791b52d0713a26ef',
 '天天驯兽师_005.apk.1':'ee1f522c65caa8e7690dbfc20fe5d0cf7c4e5e0c89a24a717e69143034e27a10',
 '天天驯兽师_006.apk.1':'d0f9e53a2476c93c99e9234bb16d9db360ff392e22d10efb9685a3e3103d1836',
 '疯狂宝贝_001.apk.1':'cd3de13e95d6af02b67f090caf14a70701edd443715f46592623c2234e5ce116',
}


def prepare(apk_dir, output, runtime_path):
    """只读解析源包，以原包优先、其他文件名顺序选取缺失名称的完整行。"""
    from apk_table_tools import ApkAssets, decode_table, sequence, stable, file_sha, sha
    ordered=['origin.apk']+sorted(name for name in EXPECTED if name!='origin.apk')
    source={}
    for filename in ordered:
        path=apk_dir/filename
        assert file_sha(path)==EXPECTED[filename],f'安装包哈希不符：{filename}'
        assets=ApkAssets(path)
        source[filename]={}
        for table in ['title','dress','dresschip','word1']:
            versions=[decode_table(assets.read(f'lua{abi}_database_{table}_proto.lua.bytes')) for abi in [32,64]]
            assert versions[0]==versions[1],(filename,table,'双架构不一致')
            source[filename][table]=versions[1]
    base=source['origin.apk']
    def label(t,row):return t['word1'].get(str(row.get('name')),{}).get('data1','').strip() or row.get('bz','').strip()
    selected=[];aliases=[]
    taken={table:set(base[table]) for table in ['title','dress']}
    names={table:{} for table in ['title','dress']}
    for table in names:
        for sid,row in sorted(base[table].items(),key=lambda item:int(item[0])):
            names[table].setdefault(label(base,row),{'sourceApk':'origin.apk','targetId':int(sid),'sourceId':int(sid)})
    next_id={'title':2000000,'dress':2000000}
    for filename in ordered[1:]:
        for kind in ['title','dress']:
            for sid,row in sorted(source[filename][kind].items(),key=lambda item:int(item[0])):
                name=label(source[filename],row)
                assert name,(filename,kind,sid,'缺少名称')
                if name in names[kind]:
                    aliases.append({'kind':kind,'sourceApk':filename,'sourceId':int(sid),'name':name,'kept':names[kind][name]})
                    continue
                tid=int(sid)
                if str(tid) in taken[kind]:
                    while str(next_id[kind]) in taken[kind]:next_id[kind]+=1
                    tid=next_id[kind];next_id[kind]+=1
                taken[kind].add(str(tid))
                item={'kind':kind,'sourceApk':filename,'sourceId':int(sid),'targetId':tid,'name':name,'raw':copy.deepcopy(row)}
                selected.append(item);names[kind][name]={k:item[k] for k in ['sourceApk','sourceId','targetId']}
    patch={'title':{},'dress':{},'dresschip':{},'word1':{}}
    text_mapping={};next_text=7000000
    def text_id(filename,value):
        nonlocal next_text
        sid=str(value)
        assert sid in source[filename]['word1'],(filename,sid,'文本未收录')
        row=source[filename]['word1'][sid]
        if base['word1'].get(sid)==row:return value
        key=(filename,sid)
        if key not in text_mapping:
            while str(next_text) in base['word1'] or str(next_text) in patch['word1']:next_text+=1
            text_mapping[key]=next_text;patch['word1'][str(next_text)]=copy.deepcopy(row);next_text+=1
        tid=text_mapping[key]
        return str(tid) if isinstance(value,str) else tid
    def remap_text(filename,row,fields):
        for field in fields:
            if field not in row:continue
            if isinstance(row[field],dict):row[field]={key:text_id(filename,value) for key,value in row[field].items()}
            else:row[field]=text_id(filename,row[field])
    def attributes(value):
        values=sequence(value);assert len(values)%2==0 or values==[0],values
        result={}
        for i in range(0,len(values)-1,2):
            key=str(values[i]);result[key]=result.get(key,0)+values[i+1]
        return result
    runtime=json.loads(runtime_path.read_text(encoding='utf-8'));before=copy.deepcopy(runtime)
    source_map=[];added_runtime={'titles':{},'dresses':{},'dressChips':{}}
    for item in selected:
        kind,filename,sid,tid=item['kind'],item['sourceApk'],str(item['sourceId']),str(item['targetId'])
        row=copy.deepcopy(item['raw']);remap_text(filename,row,['name','word']);patch[kind][tid]=row
        if kind=='title':
            added_runtime['titles'][tid]=attributes(item['raw'].get('own',{}))
            resource={'show':row['show'],'assets':[{'bundle':'sprites_icontitle.ab','path':f'assets/game/assets/sprites/icontitle/{row["show"]}.png'},{'bundle':'modules_titleshow.ab','path':f'assets/game/assets/modules/titleshow/{row["show"]}.prefab'}]}
        else:
            added_runtime['dresses'][tid]={'attributes':attributes(item['raw'].get('property',{})),'sex':row['sex']}
            appearance=str(row['sourse'])
            resource={'sourse':appearance,'assets':[{'bundle':'animations_character.ab','path':f'assets/game/assets/animations/character/{appearance}.prefab'},{'bundle':'spritesheet_character.ab','directory':f'assets/game/assets/spritesheet/character/{appearance}/'}]}
            if sid in source[filename]['dresschip']:
                chip=copy.deepcopy(source[filename]['dresschip'][sid]);remap_text(filename,chip,['name','describe']);patch['dresschip'][tid]=chip;added_runtime['dressChips'][tid]=int(tid)
        source_map.append({k:item[k] for k in ['kind','sourceApk','sourceId','targetId','name']}|{'resource':resource,'sourceRowSha256':sha(stable(item['raw']).encode())})
    for section,rows in added_runtime.items():
        for key,value in rows.items():
            assert key not in runtime[section] or runtime[section][key]==value,(section,key,'运行目录已有不同内容')
            runtime[section][key]=value
    # 原运行条目包括原包原生同名行，全部逐值保留；仅增加本次确定的缺失项。
    for section,rows in before.items():
        if isinstance(rows,dict):assert all(runtime[section][key]==value for key,value in rows.items())
    proof={'sourceApkSha256':EXPECTED,'sourcePriority':ordered,'baseCounts':{'title':len(base['title']),'dress':len(base['dress'])},'addedCounts':{'title':len(patch['title']),'dress':len(patch['dress'])},'runtimeCounts':{'title':len(runtime['titles']),'dress':len(runtime['dresses'])},'duplicatesSkipped':aliases,'runtimeAddedEntries':added_runtime,'assetValidation':'仅定位加载路径；资源实际迁移和设备展示由统一资源构建与验收确认。'}
    result={'tables':patch,'sourceMap':source_map,'proof':proof}
    output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    runtime_path.write_text(json.dumps(runtime,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'added':proof['addedCounts'],'owned':proof['runtimeCounts'],'newTexts':len(patch['word1']),'sourceMap':str(output)},ensure_ascii=False))


def main():
    """解析本地来源目录和依赖位置后生成可复核的增量。"""
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--apk-dir',type=Path,default=ROOT/'app包/origin')
    p.add_argument('--output',type=Path,default=HERE/'miao-import/cosmetics-patch.json')
    p.add_argument('--runtime',type=Path,default=HERE/'offline/resources/role/cosmetics.json')
    p.add_argument('--python-libs',type=Path)
    a=p.parse_args()
    if a.python_libs:sys.path.insert(0,str(a.python_libs.resolve()))
    prepare(a.apk_dir,a.output,a.runtime)


if __name__=='__main__':main()
