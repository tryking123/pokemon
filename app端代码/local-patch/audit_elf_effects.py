"""只读扫描APK精灵引用并执行64位原客户端基础预览；不代表完整战斗验收。"""
import sys,json
from pathlib import Path
sys.path[:0]=['.tools/offline-pylibs','.tools/lua-qa','app端代码/local-patch']
from apk_table_tools import ApkAssets,decode_table,lua_literal,file_sha
from lupa.luajit21 import LuaRuntime
import UnityPy
A=ApkAssets(Path(sys.argv[1]) if len(sys.argv)>1 else Path('.tools/opening-qa/installed.apk')); L=LuaRuntime(encoding=None,unpack_returned_tuples=True)
S={}
for n in ['lua64_module_battle.ab','lua64_helper.ab','lua64_module_sprite.ab']:
 for path,obj in UnityPy.load(A.read(n)).container.items():
  if obj.type.name=='TextAsset':S[path.split('/lua64/')[1].replace('.lua.bytes','')]=obj.read().m_Script.encode('utf8','surrogateescape')
D={}
for n in A.index:
 if n.startswith('lua64_database_'):
  D[n.removeprefix('lua64_database_').removesuffix('.lua.bytes')]=L.execute(A.read(n))
for k,v in D['character2_proto'].items():D['character_proto'][k]=v
L.globals()[b'tables']=L.table_from({k.encode():v for k,v in D.items()})
L.globals()[b'scripts']=L.table_from({k.encode():v for k,v in S.items()})
L.execute(b'''data_mgr={get=function(n)return assert(tables[n],n)end}; data_mgr.get_one=function(n,id)return assert(data_mgr.get(n)[id],n..':'..tostring(id))end
package.loaders[2]=function(name)local key=name:gsub('%.','/');local s=scripts[key];if s then return assert(loadstring(s,key))end end
log=function()end;log_r=function()end;IS_NA=false;utils={tabclone=function(t)local a={} for k,v in pairs(t)do a[k]=v end return a end}; table.len=function(t)local n=0 for k in pairs(t)do n=n+1 end return n end
gfossil_model=setmetatable({},{__index=function()return function()return {}end end});gemblem_model={get_suit_level=function()return 0,{badge={}} end,get_lv=function()return 0 end};core=require('module/battle/core/init')
''')
L.execute(b'''function run(sid,level,star,awaken)
local elf={id='1',sid=sid,level=level or 1,love=1,star=star or 1,awakelv=awaken or 0,veinlevel=0,position=1,nature=1001,individual={},effort={},badge={},skill_bar={},skill_lv={},character={}}
for _,k in ipairs({"hp_max","speed","physical_attack","physical_defense","special_attack","special_defense"})do elf.individual[k]=0;elf.effort[k]=0 end; local proto=tables.jingling_proto[sid];elf.character={};for k,v in pairs(proto.skill1)do if k%2==0 and proto.skill1[k-1]<=elf.level then elf.skill_bar[#elf.skill_bar+1]=v end end
local cb=setmetatable({level=1,vip=0,enum_battle_elf=function(fn)fn(elf)end,get_current_formation=function()return 101 end,get_pokemon_degree=function()return 1 end,enum_portable_item=function()end,enum_gold_portable=function()end},{__index=function()return function()end end})
return core.new('battle',cb)
end''')
# 隐藏技能面板会在培养页之前初始化，必须同时验证古代技能图标链路。
from patch_miao_battle import parse, arena
prefix, _, panelProtos = parse(S['module/sprite/panel/skill_panel'])
panelProto = panelProtos[15]
ancientPreview = L.eval(b'assert(loadstring(...))', prefix + arena.encode(len(panelProto['body'])) + panelProto['body'] + b'\0')
L.globals()[b'ancientPreview'] = ancientPreview
L.execute(b"""ancientState={anci_icon={SetNativeSize=function()end,gameObject={SetActive=function()end}},anci_text={gameObject={SetActive=function()end}},anci_level={}};res_mgr={load_sprite=function(group,icon)return group..':'..tostring(icon)end};debug.setupvalue(ancientPreview,1,data_mgr.get_one);debug.setupvalue(ancientPreview,2,ancientState)""")
from collections import Counter
import hashlib
modelIndex=json.loads(Path('问题/精灵模型索引_20261122.json').read_text(encoding='utf8'))
models={}
for g in ['pkm1','pkm2']:
 n='animations_'+g+'.ab';record=modelIndex['bundles'][n];_,file,offset,size=A.index[n]
 assert hashlib.sha256(A.physical(file)[int(offset):int(offset)+int(size)]).hexdigest()==record['encryptedSegmentSha256'], '模型段变化，须重新提取索引'
 models[g]={p.rsplit('/',1)[-1].split('.')[0] for p in record['paths']}

rows=[]
for sid,proto in sorted(D['jingling_proto'].items()):
 def seq(v):return [x for k,x in sorted(v.items())] if v else []
 skills=seq(proto[b'skill1']);awaken=seq(proto[b'skill2']);passive=seq(proto[b'skill3']);chars=seq(proto[b'character'])
 refs=skills[1::2]+awaken[1::2]+([proto[b'rage']] if proto[b'rage'] else [])
 missing=[]
 for x in refs:
  if x and D['skill_proto'][x] is None:missing.append('skill:'+str(x))
 for x in chars+passive[1::2]:
  if x and D['character_proto'][x] is None:missing.append('character:'+str(x))
 for g in models:
  if str(proto[b'ziyuan2']) not in models[g]:missing.append('model:'+g+':'+str(proto[b'ziyuan2']))
 try:
  team=L.globals()[b'run'](sid);unit=team[b'pokemon'][1];preview={'ok':True,'hp':unit[b'hp_max'],'power':team[b'power']}
 except Exception as e:preview={'ok':False,'error':str(e)}
 ancientCase=None
 if proto[b'rage']:
  try:
   ancientPreview(proto);ancientCase={'ok':True,'icon':L.globals()[b'ancientState'][b'anci_icon'][b'sprite'].decode()}
  except Exception as e:ancientCase={'ok':False,'error':str(e)}
 word=D['word1_proto'][proto[b'name']];name=word[b'data1'] if word else None
 nameSource='word1.data1' if name else 'missingChineseName'
 label=proto[b'bz'];label=label.decode('utf8',errors='replace') if isinstance(label,bytes) else label
 if isinstance(name,bytes):name=name.decode('utf8',errors='replace')
 rows.append({'id':sid,'name':name,'nameSource':nameSource,'nameTextId':proto[b'name'],'sourceRemark':label,'model':proto[b'ziyuan2'],'ancientSkill':proto[b'rage'],'ancientIconPreview':ancientCase,'skills':refs,'characters':chars,'awakeningPassives':passive,'missingReferences':sorted(set(missing)),'preview':preview,'previewScenarios':[],'battleOnly':sid in [641891,641892,641893],'conclusion':('仅战斗变形模板，不支持作为背包持有精灵初始化' if sid in [641891,641892,641893] else '引用和基础预览通过，触发时机待真实战斗回归' if not missing and preview['ok'] else '存在缺失引用或预览异常')})
for row in rows:
 if row['battleOnly']:continue
 for level,star,awaken in [(1,1,0),(35,10,3),(80,15,5)]:
  try:
   team=L.globals()[b'run'](row['id'],level,star,awaken);unit=team[b'pokemon'][1]
   case={'level':level,'star':star,'awaken':awaken,'ok':True,'hp':unit[b'hp_max'],'triggers':list(unit[b'trigger'].values())}
  except Exception as e:case={'level':level,'star':star,'awaken':awaken,'ok':False,'error':str(e)}
  row['previewScenarios'].append(case)
result={'apk':str(Path(sys.argv[1]) if len(sys.argv)>1 else Path('.tools/opening-qa/installed.apk')),'apkSha256':file_sha(Path(sys.argv[1]) if len(sys.argv)>1 else Path('.tools/opening-qa/installed.apk')),'scope':'实际APK64位Lua表、core.init基础预览及skill_panel古代技能图标；外部模块为空替身，不是完整设备战斗','speciesCount':len(rows),'previewPassed':sum(r['preview']['ok'] for r in rows),'referencesPassed':sum(not r['missingReferences'] for r in rows),'ancientIconCases':sum(r['ancientIconPreview'] is not None for r in rows),'ancientIconPassed':sum(bool(r['ancientIconPreview'] and r['ancientIconPreview']['ok']) for r in rows),'scenarioPassed':sum(c['ok'] for r in rows for c in r['previewScenarios']),'scenarioCount':sum(len(r['previewScenarios']) for r in rows),'missingChineseNames':sum(r['name'] is None for r in rows),'missingKinds':dict(Counter(x.split(':')[0] for r in rows for x in r['missingReferences'])),'rows':rows}
Path('问题/精灵及特性全量扫描_20261122.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print({k:v for k,v in result.items() if k!='rows'})
for r in rows:
 if r['missingReferences'] or not r['preview']['ok']:print(r['id'],r['missingReferences'],r['preview'].get('error','')[:100])
