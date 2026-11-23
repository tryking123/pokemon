"""在实际 APK 的 64 位 LuaJIT 战斗引擎核验全队属性动作与开场触发。"""
import argparse
import hashlib
import json
import sys
from collections import Counter, defaultdict
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / path) for path in (
    ".tools/lua-qa", ".tools/offline-pylibs", "app端代码/local-patch")]
import UnityPy
from lupa.luajit21 import LuaRuntime
from apk_table_tools import ApkAssets

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--catalog",type=Path,default=ROOT/"全局数值规划统筹"/"全队天赋动作审计_20261122.json")
parser.add_argument("--overlay",type=Path,help="使用构建中的资源替换目录验证；输出中单独标记")
parser.add_argument("--apply-patch", action="store_true",help="在内存中应用待打包的团队属性补丁")
parser.add_argument("--compound",action="store_true",help="执行复合天赋专项")
parser.add_argument("--owners", action="store_true", help="核对所有精灵及规则的持有者和刷新归属")
args = parser.parse_args()
catalog = json.loads(args.catalog.read_text("utf8"))
kinds = {(r["id"],int(slot)):kind for r in catalog["rules"] for slot,kind in r["teamActionSlots"].items()}
apk = ApkAssets(args.apk,overlay=args.overlay)
runtime = LuaRuntime(encoding=None, unpack_returned_tuples=True)
scripts = {}
for bundle in ("lua64_module_battle.ab", "lua64_helper.ab"):
    for path, obj in UnityPy.load(apk.read(bundle)).container.items():
        if obj.type.name == "TextAsset":
            key = path.split("/lua64/")[1].removesuffix(".lua.bytes").encode()
            scripts[key] = obj.read().m_Script.encode("utf8", "surrogateescape")
if args.apply_patch:
    import importlib
    from patch_team_talents import patch_team_talents
    helpers = importlib.import_module("patch-unity-arena")
    key = b"module/battle/core/formation"
    scripts[key] = patch_team_talents(scripts[key],helpers)
tables = {}
for name in apk.index:
    if name.startswith("lua64_database_"):
        key = name.removeprefix("lua64_database_").removesuffix(".lua.bytes").encode()
        tables[key] = runtime.execute(apk.read(name))
for key, value in tables[b"character2_proto"].items():
    tables[b"character_proto"][key] = value
runtime.globals().scripts = runtime.table_from(scripts)
runtime.globals().tables = runtime.table_from(tables)
engine = runtime.execute("""
data_mgr={get=function(name) return assert(tables[name],name) end}
data_mgr.get_one=function(name,id) return assert(data_mgr.get(name)[id],name..':'..tostring(id)) end
package.loaders[2]=function(name)
    local source=scripts[name:gsub('%.','/')]
    if source then return assert(loadstring(source,name)) end
end
log=function() end; log_r=log; IS_NA=false
utils={tabclone=function(t) local a={} for k,v in pairs(t) do a[k]=v end return a end}
table.len=function(t) local n=0 for _ in pairs(t) do n=n+1 end return n end
local Formation=require('module/battle/core/formation')
local Stage=require('module/battle/core/stage')
local typedef=require('module/battle/core/typedef')
local _,buffs,_,triggers=Formation.interface()
local function random(lo,hi) return lo or 1 end
local function team(name,n,one,two,skill)
    local members={}
    for i=1,n do
        local p={id=name..i,parent=name,sid=64058,sprite=64058,position=i,level=100,
            type1=17,type2=11,quality=6,gender=0,skill_bar={skill or 50001},trigger={},power=10000,sp=100}
        for _,field in pairs(typedef) do if p[field]==nil and field~='hp' then p[field]=0 end end
        for _,field in ipairs({'hp_max','physical_attack','special_attack','physical_defense','special_defense','speed'}) do
            p[field]=10000+i*1000
        end
        p.hp_shield=2500; p.dynatype=2; p.dynapath=1; p.element_provide=1000
        -- 同时放置属性5、属性17与双属性11的接收者，核对特定属性分支。
        if i==3 or i==5 then p.type1=5 end
        if i==1 and one>0 then p.trigger={one}
        elseif i==2 and two>0 then p.trigger={two} end
        members[i]=p
    end
    return Formation.new({identifer=name,sid=1,pokemon=members,preference=1,isnpc=false,power=1000},random)
end
function measure(op,field,id1,slot1,id2,slot2)
    local direct=op>0
    local l=team('L',6,direct and 0 or id1,direct and 0 or id2)
    local r=team('R',6,0,0)
    local s=Stage.new(l,r,0,100,'L',false)
    s:prepare(function()end)
    if op==-2 then
        -- Stage 的回合开始处理以当前出手者和事件 2 调用同一 dispatch。
        triggers.dispatch(l.pokemon[1],2)
        triggers.dispatch(l.pokemon[2],2)
    end
    if direct and field==-5 then
        for _,p in ipairs(s.pokemon) do buffs.attach(p,4,true,3,1,1,1) end
    end
    if direct and (field==-1 or field==-2) then
        for _,p in ipairs(s.pokemon) do
            if field==-1 then p.hp=math.floor(p.hp/4) else p.skill_point_bar=0 end
        end
    end
    if direct then
        for i,key in ipairs({id1,id2}) do
            if key>0 then
                local action=tables.character_proto[key]['action'..(i==1 and slot1 or slot2)]
                if action[1]==31 then
                    triggers.action[31](l.pokemon[i],action,r.pokemon[1],r.pokemon[1],3000)
                elseif action[1]==51 or action[1]==52 or action[1]==43 then
                    triggers.action[action[1]](l.pokemon[i],action,r.pokemon[1],r.pokemon[1])
                else
                    triggers.action[action[1]](l.pokemon[i],action,r.pokemon[1],1)
                end
            end
        end
    end
    local out={}
    for _,p in ipairs(s.pokemon) do
        if field==-1 then out[p.id]=p.hp
        elseif field==-2 then out[p.id]=p.skill_point_bar
        elseif field==-3 then out[p.id]=p.hp_shield or 0
        elseif field==-4 then out[p.id]=p.skill_point_limit
        elseif field==-5 then out[p.id]=p.total_debuff_number
        else out[p.id]=s:get(p,typedef[field]) end
    end
    return out
end
function lifecycle(dispel)
    local l,r=team('L',6,0,0),team('R',6,0,0)
    local s=Stage.new(l,r,0,100,'L',false)
    s:prepare(function()end)
    local recipient=l.pokemon[3]
    local result={}
    local function sample() result[#result+1]=s:get(recipient,'physical_attack') end
    sample()
    local flat=tables.character_proto[17400].action1
    local ratio=tables.character_proto[52300].action1
    triggers.action[27](l.pokemon[1],flat); sample()
    triggers.action[35](l.pokemon[2],ratio); sample()
    if dispel then
        buffs.dispel(recipient,1); sample()
        buffs.dispel(recipient,1); sample()
    else
        for i=1,3 do
            buffs.maintain(recipient)
            sample()
        end
    end
    return unpack(result)
end
function oncePerSource()
    local l,r=team('L',6,1370,1370),team('R',6,0,0)
    local s=Stage.new(l,r,0,100,'L',false)
    s:prepare(function()end)
    local a={}
    local function sample() a[#a+1]=s:get(l.pokemon[3],'physical_attack') end
    sample()
    triggers.dispatch(l.pokemon[1],17); sample()
    triggers.dispatch(l.pokemon[1],18); sample()
    triggers.dispatch(l.pokemon[1],18); sample()
    triggers.dispatch(l.pokemon[2],18); sample()
    return unpack(a)
end
return {formation=Formation, stage=Stage, names=typedef, buffs=buffs, triggers=triggers, team=team}
""".encode())
if args.owners:
    verify = runtime.execute(Path(__file__).with_name("verify_talent_owners.lua").read_bytes())
    species, catalog_counts = verify(engine)
    report = {"apk": args.apk.name, "sha256": hashlib.file_digest(args.apk.open("rb"), "sha256").hexdigest(),
              "inMemoryPatch": args.apply_patch, "overlay": args.overlay is not None,
              "scope": "每个物种在初始档和最高养成档各测单、双持有者，实际执行开场及双方攻击。另在全档注册与分派检查中用观察函数核对持有者和独立次数。"}
    report["species"] = [{"sid": sid, **{k.decode(): v for k, v in counts.items()}}
                         for sid, counts in sorted(species.items())]
    report["catalog"] = {k.decode(): v for k, v in catalog_counts.items()}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
    print(len(report["species"]), report["catalog"])
    sys.exit(0)
if args.compound:
    from verify_compound_talents import run
    run(runtime,engine,tables,catalog,args.apk,args.output,args.apply_patch)
    sys.exit(0)
measure = runtime.globals().measure
attributes = runtime.execute(b"return require('module/battle/core/typedef')")
rules = []
for trigger, row in sorted(tables[b"character_proto"].items()):
    for slot in range(1, 5):
        action = row[f"action{slot}".encode()]
        values = [action[i] for i in range(1,len(action)+1)] if action is not None else []
        if not values:
            continue
        opcode = values[0]
        if opcode in (27, 35):
            field = values[4] if opcode == 27 else values[2]
            if not attributes[field]:
                continue
        elif (trigger,slot) in kinds and opcode in (14, 23, 24, 29, 31, 43, 51, 52, 76):
            field = {14:-5, 23:-1, 24:-1, 29:-2, 31:-1, 43:-1, 51:-3, 52:-3, 76:-4}[opcode]
        else:
            continue
        rules.append((trigger, slot, opcode, field, row[b"event"], tuple(values)))

def snapshot(mode, field, first, second):
    values = measure(mode, field, *first, *second)
    return [values[key] for key in (b"L1",b"L2",b"L3",b"L4",b"L5",b"L6",b"R1",b"R2",b"R3",b"R4",b"R5",b"R6")]

empty = (0, 0)
snapshot = lru_cache(maxsize=24000)(snapshot)

def exercise(mode, field, first, second):
    base = snapshot(mode, field, empty, empty)
    a = snapshot(mode, field, first, empty)
    b = snapshot(mode, field, empty, second)
    ab = snapshot(mode, field, first, second)
    delta = lambda x: [round(y-z,7) for y,z in zip(x,base)]
    da,db,both = delta(a),delta(b),delta(ab)
    # 两组单独测量已分别取整，允许反推出组合值时出现 1 点误差。
    add = all(abs(x-y-z)<=1.000001 for x,y,z in zip(both,da,db))
    return {"A":da,"B":db,"AB":both,"targetAdditive":abs(both[2]-da[2]-db[2])<=1.000001,
            "allTargetsAdditive":add,"receiverAffected":bool(da[2] or db[2]),
            "bothSourcesAffectReceiver":bool(da[2] and db[2]),
            "enemyAffected":any(da[6:]+db[6:]+both[6:])}

buckets = defaultdict(list)
for rule in rules:
    buckets[rule[3]].append(rule)

rows = []
for trigger, slot, opcode, field, event, values in rules:
    key = (trigger,slot)
    cases = {"directSame":(opcode,key)}
    if event == 1:
        cases["startupSame"]=(0,key)
    if event==2:
        cases["turnEventSame"]=(-2,key)
    # 选择同属性但另一基族，且优先相同动作与时机；记录实际 ID。
    others = [r for r in buckets[field] if r[0]//100 != trigger//100]
    if others:
        other = min(others,key=lambda r:(r[2]!=opcode,r[4]!=event,r[0],r[1]))
        cases["directMixed"]=(opcode,other[:2])
        if event==other[4]==1:
            cases["startupMixed"]=(0,other[:2])
        if event==other[4]==2:
            cases["turnEventMixed"]=(-2,other[:2])
    record = {"id":trigger,"slot":slot,"action":list(values),"event":event,
              "catalogKind":kinds.get(key,"control"),"attribute":(attributes[field].decode() if field>0 else {-1:"hp",-2:"sp",-3:"shield",-4:"spLimit",-5:"debuffCount"}[field]),"cases":{}}
    for label,(mode,other) in cases.items():
        try:
            result = exercise(mode,field,key,other)
        except Exception as error:
            result = {"error":str(error).splitlines()[0]}
        record["cases"][label]={"second":list(other),**result}
    rows.append(record)
summary = {}
for label in ("directSame","directMixed","startupSame","startupMixed","turnEventSame","turnEventMixed"):
    cases=[r["cases"][label] for r in rows if label in r["cases"]]
    summary[label] = dict(total=len(cases),error=sum("error" in c for c in cases),
        bothAffect=sum(c.get("bothSourcesAffectReceiver",False) for c in cases),
        nonAdditive=sum(c.get("bothSourcesAffectReceiver") and not c["targetAdditive"] or False for c in cases))
coverage = Counter()
for row in rows:
    if row["catalogKind"] != "control":
        coverage[row["catalogKind"]] += 1
missing = sorted(set(kinds) - {(r["id"],r["slot"]) for r in rows})
assert not missing, missing
once = runtime.globals().oncePerSource()
assert once[0]==once[1] and once[2]==once[3] and once[2]>once[1] and once[4]>once[3], once
expiry = runtime.globals().lifecycle(False)
dispel = runtime.globals().lifecycle(True)
patched = b"function buffs.attachTeam" in scripts[b"module/battle/core/formation"]
assert expiry[3:]==(13390,13390,13000) and dispel[-2:]==(13000,13000)
assert expiry[:3]==(13000,13550,13940 if patched else 13956), expiry
report = {"expiryAndDispel":{"oneRoundFlat":17400,"threeRoundRatio":52300,"expiry":expiry,"dispel":dispel},
    "repeatedOnce":{"rule":1370,"labels":["initial","wrong_event","first","first_again","second"],"attack":once},
    "catalog":args.catalog.name,"catalogCoverage":coverage,"overlay":args.overlay is not None,"inMemoryPatch":args.apply_patch,
    "engineSHA256":hashlib.sha256(scripts[b"module/battle/core/formation"]).hexdigest(),
    "apk":args.apk.name,"sha256":hashlib.file_digest(args.apk.open("rb"),"sha256").hexdigest(),
    "scope":"64位原包Formation/Stage；direct直接执行实际动作；startup执行实际条件与prepare；turnEvent针对事件2使用原引擎逐来源dispatch与条件，未演完整回合动画。L1/L2为来源，L3至L6为其他友方，R1至R6为敌方；L3及L5的第一属性为5，其他为17。负数值为扣减；回血先扣至25%、回SP先清空，按原生上下限执行。值为相对空白阵容的变化。",
    "ruleCount":len(rules),"triggerCount":len({r[0] for r in rules}),"summary":summary,"rows":rows}
args.output.write_text(json.dumps(report,ensure_ascii=False,separators=(",",":"))+"\n",encoding="utf8")
print(summary)
