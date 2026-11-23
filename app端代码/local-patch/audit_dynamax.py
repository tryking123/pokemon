"""对照固定底包与迁移源审计全精灵极巨路线、费用、被动和全部整书技能。"""
import argparse,json
from pathlib import Path
from apk_table_tools import ApkAssets,decode_table,file_sha,sequence

HERE=Path(__file__).resolve().parent
ROOT=HERE.parent.parent
BASE='f010a675dcb9ccacf899a2954e7e497be7f04c3ea94214140fa3434c817a7c45'

def normal(value):
    return sequence(value) if isinstance(value,dict) else value

def audit(origin,output):
    if file_sha(origin)!=BASE:raise ValueError('底包SHA-256不符')
    a=ApkAssets(origin);raw={};abis={};problems=[]
    for name in ['dynamax','dynamaxlevel','dynamaxskills','dynlevelmax','jingling','skill','character']:
        raw[name]=decode_table(a.read(f'lua64_database_{name}_proto.lua.bytes'))
        abis[name]=raw[name]==decode_table(a.read(f'lua32_database_{name}_proto.lua.bytes'))
        if not abis[name]:problems.append({'abi':name})
    plan=json.loads((HERE/'miao-import/species-patch.json').read_text(encoding='utf8'))
    for name in ['jingling','skill','character']:raw[name].update(plan['tables'].get(name,{}))
    local=json.loads((HERE/'offline/resources/elf/dynamax.json').read_text(encoding='utf8'))
    train=json.loads((HERE/'offline/resources/elf/training.json').read_text(encoding='utf8'))
    sections={'books':'dynamax','levels':'dynamaxlevel','bookLevels':'dynamaxskills'}
    for section,source in sections.items():
        for key,row in local[section].items():
            for field,value in row.items():
                expected=normal(raw[source].get(key,{}).get(field))
                if value!=expected:problems.append({'section':section,'id':key,'field':field,'actual':value,'expected':expected})
    caps=[row for _,row in sorted(raw['dynlevelmax'].items(),key=lambda pair:int(pair[0]))]
    if local['caps']!=caps:problems.append({'section':'caps'})
    expected_routes={key:sequence(row['dynamax']) for key,row in raw['jingling'].items() if row.get('dynamax') and sequence(row.get('changeid',{'1':0}))[0]==0}
    if local['routes']!=expected_routes:problems.append({'section':'routes','missing':list(set(expected_routes)-set(local['routes'])),'extra':list(set(local['routes'])-set(expected_routes))})
    states=[]
    for key,row in train['species'].items():
        routes=local['routes'].get(key,[]);cap=next((r['level'] for r in local['caps'] if row['assess']<=r['quality']),0) if routes else 0
        states.append({'species':int(key),'terminal':row['evolution'][0]==0,'paths':routes,'cap':cap})
    combos=[];skills=set()
    for book,row in local['books'].items():
        for path in ['attack','control','auxiliary']:
            values=row[path]
            if len(values)%2 or sum(values[1::2])<=0:problems.append({'book':book,'path':path,'invalidWeights':values})
            for skill,weight in zip(values[::2],values[1::2]):
                skills.add(skill);r=raw['skill'].get(str(skill))
                if r is None:problems.append({'book':book,'path':path,'missingSkill':skill})
                combos.append({'book':int(book),'path':path,'skill':skill,'weight':weight,'rule':r})
    passives=[]
    for lv,row in local['levels'].items():
        for path in ['attack','control','auxiliary']:
            values=row.get(path+'_nature',[])
            if len(values)%2 or any(code not in range(30,34) for code in values[::2]):problems.append({'level':lv,'path':path,'nature':values})
            for passive in row.get(path+'_skill',[]):
                if str(passive) not in raw['character']:problems.append({'level':lv,'path':path,'missingPassive':passive})
                passives.append({'level':int(lv),'path':path,'passive':passive})
    result={'date':'2026-11-22','originSha256':BASE,'abiEqual':abis,'status':'PASS' if not problems else 'FAIL',
            'counts':{'species':len(states),'eligibleSpecies':len(expected_routes),'books':len(local['books']),'levels':len(local['levels']),'bookLevels':len(local['bookLevels']),'weightedEntries':len(combos),'uniqueSkills':len(skills)},
            'problems':problems,'species':states,'weightedSkills':combos,'passives':passives,
            'scope':'原表、缺行与引用全量核查，不替代全部UI及设备事务回归'}
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8');print(result['status'],result['counts']);print('problems',problems[:10])

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--origin',type=Path,default=ROOT/'app包/origin/origin.apk');p.add_argument('--output',type=Path,default=ROOT/'全局数值规划统筹/极巨化全量配置审计_20261122.json');args=p.parse_args();audit(args.origin,args.output)
