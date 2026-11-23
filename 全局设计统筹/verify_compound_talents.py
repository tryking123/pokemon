"""执行复合天赋专项；由 verify_team_attributes.py --compound 加载 APK 原战斗引擎。"""
import hashlib
import json

SCENARIO = b"""
local engine=...
local F,S=engine.formation,engine.stage
local function random(lo,hi) return lo or 1 end
local function team(parent, count, rank, skill, other, privacy)
    local team={identifer=parent,sid=1,pokemon={},preference=1,isnpc=false,power=100000}
    for i=1,6 do
        local source=parent=='L' and i<=count
        local sid=source and 64098 or 64058
        local p={id=parent..i,parent=parent,position=i,sid=sid,sprite=sid,level=100,gender=0,
            quality=6,power=10000,hp_max=9999000,physical_attack=30000,special_attack=30000,
            physical_defense=10000,special_defense=10000,speed=100+i,hit_chance=10000,
            skill_bar={skill},trigger={},sp=100,add_skill_point_limit=0,element_provide=1000}
        local species=tables.jingling_proto[sid]
        p.type1,p.type2=species.attribute1,species.attribute2
        if source then
            p.trigger=other or {32400+rank,32500+rank,32600+rank,22900+rank}
        end
        if privacy then
            -- Equal combatants isolate effect ownership across the three team layouts.
            p.type1,p.type2=17,11
        end
        team.pokemon[i]=p
    end
    return F.new(team,random)
end
return function(count,rank,actor,skill,burnA,burnT,roundRefresh,other)
    local l=team('L',count,rank,skill,other,true)
    local r=team('R',0,rank,skill,nil,true)
    local s=S.new(l,r,0,100,'L',false)
    local a,t=l.pokemon[actor],r.pokemon[1]
    local out={splashHits={},attackEvents=0,defenseMin=10000,specialDefenseMin=10000}
    local function capture(event,id,target,delta)
        if event=='splash' then out.splashHits[#out.splashHits+1]={caster=id,target=target,delta=delta} end
        if event=='damage' then out.attackEvents=out.attackEvents+1 end
        if a.stage then
            out.defenseMin=math.min(out.defenseMin,s:get(t,'physical_defense'))
            out.specialDefenseMin=math.min(out.specialDefenseMin,s:get(t,'special_defense'))
        end
    end
    s:prepare(capture)
    local function fireValues()
        local vals={}
        for _,p in ipairs(l.pokemon)do vals[#vals+1]=s:get(p,'fire_damage') end
        return vals
    end
    out.open=fireValues()
    if roundRefresh then
        for _,p in ipairs(l.pokemon)do engine.buffs.maintain(p) end
        out.expired=fireValues()
        for i=1,6 do engine.triggers.dispatch(l.pokemon[i],2) end
        out.refreshed=fireValues()
    end
    local function burn(p)
        engine.buffs.attach(p,13,true,3,0.01,0,2,1,p)
    end
    if burnT then burn(t) end
    if burnA then burn(a) end
    out.fireBeforeAttack=s:get(a,'fire_damage')
    local old=t.hp
    s:perform(a,a.skill_bar[1],{t})
    out.mainLoss=old-t.hp
    out.otherLoss={}
    for i=2,6 do out.otherLoss[#out.otherLoss+1]=9999000-r.pokemon[i].hp end
    out.targetDefAfter=s:get(t,'physical_defense')
    out.targetSpDefAfter=s:get(t,'special_defense')
    out.selfDef=s:get(a,'physical_defense')
    out.selfSpDef=s:get(a,'special_defense')
    out.partnerDef=s:get(l.pokemon[actor==2 and 1 or 2],'physical_defense')
    engine.buffs.maintain(a)
    out.selfDefNext=s:get(a,'physical_defense')
    engine.buffs.maintain(a)
    out.selfDefLater=s:get(a,'physical_defense')
    return out
end
"""

ROUNDS = b"""
local e=...
return function(count, rule, incoming)
    local l=e.team('L',6,count>=1 and rule or 0,count>=2 and rule or 0)
    local r=e.team('R',6,0,0,incoming)
    for i=1,count do
        if rule==32401 then l.pokemon[i].triggers=e.triggers.load({32401,32501,32601,22901}) end
    end
    if rule==63001 then
        for _,p in ipairs(l.pokemon)do p.type1=18; p.type2=0 end
    end
    if incoming then for _,p in ipairs(l.pokemon)do p.att.hp_max.base=9999000 end end
    local s=e.stage.new(l,r,0,100,'L',false)
    s:prepare(function()end)
    local out={}
    local members={} for i,p in ipairs(l.pokemon)do members[i]=p end
    local function sample(turn,phase)
        local v={turn=turn,phase=phase,fire={},fairyPower={},fairyReduce={}}
        for i,p in ipairs(members)do
            v.fire[i]=s:get(p,'fire_damage')
            v.fairyPower[i]=s:get(p,'fairy_power')
            v.fairyReduce[i]=s:get(p,'fairy_reduce')
        end
        out[#out+1]=v
    end
    sample(0,'open')
    if incoming then
        for i=1,count do e.triggers.dispatch(l.pokemon[i],2) end
        local target=l.pokemon[3]
        local old=target.hp
        s:perform(r.pokemon[1],r.pokemon[1].skill_bar[1],{target})
        return old-target.hp
    end
    for round=1,3 do
        for _,i in ipairs({1,3,2,4,5,6}) do
            e.triggers.dispatch(l.pokemon[i],2)
            sample(i,'turn'..round..'-dispatch')
            e.buffs.maintain(l.pokemon[i])
            sample(i,'turn'..round..'-maintain')
        end
    end
    if rule==32401 then
        for i=1,count do
            local exiting=members[i]
            e.formation.add_hp(exiting,-exiting.hp)
            s:handle_death(r.pokemon[1])
            sample(i,'exit')
        end
    end
    return out
end
"""

def plain(value):
    if isinstance(value,bytes):
        return value.decode()
    if hasattr(value,"items"):
        data={plain(k):plain(v) for k,v in value.items()}
        if set(data)==set(range(1,len(data)+1)):
            return [data[k] for k in range(1,len(data)+1)]
        return data
    return value

def run(runtime,engine,tables,catalog,apk,output,patched_in_memory=False):
    case=runtime.execute(SCENARIO,engine)
    rows=[]
    for rank in range(1,10):
        for count in (0,1,2):
            for actor in (1,2,3):
                for skill in (10001,10052):
                    for burnA,burnT in ((False,False),(False,True),(True,False),(True,True)):
                        result=plain(case(count,rank,actor,skill,burnA,burnT,True,None))
                        rows.append({"rank":rank,"count":count,"actor":actor,"skill":skill,"burnActor":burnA,
                                     "burnTarget":burnT,"result":result})
    with apk.open("rb") as stream:
        digest=hashlib.file_digest(stream,"sha256").hexdigest()
    rounds=runtime.execute(ROUNDS,engine)
    cross={count:plain(rounds(count,32401)) for count in range(3)}
    ar={count:plain(rounds(count,63001)) for count in range(3)}
    incoming={skill:[rounds(count,63001,skill) for count in range(3)] for skill in (10001,10238)}
    proto=tables[b"character_proto"]
    errors=[]
    def check(ok,message):
        if not ok:
            errors.append(message)
    for count,states in ar.items():
        check(all(v==0 for state in states for v in state["fairyPower"]),("AR power",count))
        check(max(max(st["fairyReduce"]) for st in states)==250*count,("AR reduce",count))
    check(incoming[10001][0]==incoming[10001][1]==incoming[10001][2],("AR normal",incoming[10001]))
    check(incoming[10238][0]>incoming[10238][1]>incoming[10238][2],("AR fairy",incoming[10238]))
    for count,states in cross.items():
        for sample in states:
            alive=list(range(1,count+1))
            if sample["phase"]=="exit":
                alive=[i for i in alive if i>sample["turn"]]
            for i,actual in enumerate(sample["fire"],start=1):
                if sample["phase"]!="exit" or i>sample["turn"]:
                    expected=sum(200 for source in alive if source!=i)
                    check(actual==expected,("round",count,sample["phase"],i,actual,expected))
    for x in rows:
        result=x["result"]
        rank,count,actor=x["rank"],x["count"],x["actor"]
        owner=actor<=count
        key=(rank,count,actor,x["skill"],x["burnActor"],x["burnTarget"])
        scale=proto[32400+rank][b"action1"][4]
        check(result["fireBeforeAttack"]==scale*(count-owner),("fire",key))
        splashes=result["splashHits"]
        check(len(splashes)==(3 if owner and x["burnTarget"] else 0),("splash-count",key))
        if splashes:
            base=result["mainLoss"]-sum(h["delta"] for h in splashes if h["target"]=="R1")
            ratio=proto[32500+rank][b"action1"][2]
            check(len({h["target"] for h in splashes})==3 and all(
                abs(h["delta"]-base*ratio)<=1 for h in splashes),("splash-damage",key))
        weak=owner and x["burnActor"]
        targetRatio=proto[32600+rank][b"action1"][5] if weak else 0
        selfRatio=proto[32600+rank][b"action3"][5] if weak else 0
        check(all(abs(result[k]-10000*(1+targetRatio))<=1 for k in ("defenseMin","specialDefenseMin")),("pierce",key))
        check(all(abs(result[k]-10000*(1+selfRatio))<=1 for k in ("selfDef","selfSpDef")),("self",key))
        check(result["partnerDef"]==10000,("partner",key))
        check(result["targetDefAfter"]==result["targetSpDefAfter"]==result["selfDefNext"]==result["selfDefLater"]==10000,("expiry",key))
    clean={(r["rank"],r["skill"],r["actor"],r["count"]):r["result"]["mainLoss"] for r in rows
           if not r["burnActor"] and not r["burnTarget"]}
    for (rank,skill,actor,count),damage in clean.items():
        base=clean[rank,skill,actor,0]
        fire=proto[32400+rank][b"action1"][4]/1000 if skill==10052 else 0
        expected=base*(1+fire*(count-(actor<=count)))
        check(abs(damage-expected)<4,("attack",rank,skill,actor,count,damage,expected))
    report={"arIncoming":incoming,"rounds":cross,"arSystem":ar,"patchInMemory":patched_in_memory,"errors":errors,
            "apk":apk.name,"sha256":digest,"rows":rows}
    output.write_text(json.dumps(report,ensure_ascii=False,separators=(",",":"))+"\n","utf8")
    print("Completed",len(rows),"battle scenarios,",len(errors),"discrepancies")
