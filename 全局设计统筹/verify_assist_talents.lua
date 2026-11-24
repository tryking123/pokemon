local baseline = ...
data_mgr = {get = function(name) return assert(tables[name], name) end}
data_mgr.get_one = function(name, id) return assert(data_mgr.get(name)[id], tostring(id)) end
package.loaders[2] = function(name)
    local chunk = scripts[name:gsub('%.', '/')]
    if chunk then return assert(loadstring(chunk, name)) end
end
log = function() end
log_r, IS_NA = log, false
utils = {tabclone = function(t) local copy = {} for k,v in pairs(t) do copy[k] = v end return copy end}
table.len = function(t) local n = 0 for _ in pairs(t) do n = n + 1 end return n end

local Formation = require("module/battle/core/formation")
local Stage = require("module/battle/core/stage")
local _, _, _, triggers = Formation.interface()
local function elf(uid, sid, element, ids, slot)
    return {id=uid, sid=sid, sprite=sid, type1=element, type2=0, position=slot, quality=6,
        level=100, gender=1, power=100, hp_max=10000, speed=10000, physical_attack=10000,
        special_attack=10000, physical_defense=10000, special_defense=10000,
        skill_bar={10052}, trigger=ids, sp=100, add_skill_point_limit=0, element_provide=1000}
end

local function run(front, assists)
    local function team(side, members, bench)
        for _, p in ipairs(members) do p.parent = side end
        return Formation.new({identifer=side, sid=1, pokemon=members, assists=bench,
            preference=1, isnpc=false, power=1000}, function(lo) return lo end)
    end
    local left = team("L", front, assists)
    local right = team("R", {elf("R1",64186,5,{},1), elf("R2",63006,17,{},2), elf("R3",63006,17,{},3)})
    local stage = Stage.new(left, right, 0, 100, "L", false)
    stage:prepare(function() end)
    return stage, left
end

-- 小软的进化、属性与额外回合全部通过原生动作执行，分别检查本人和同伴。
local function softCase(number, old)
    local front = {}
    local ids = {105101,105201,105301,105401,105501,105601,105701,105801,105901}
    for i=1,6 do
        front[i] = elf("L"..i,i==1 and 64189 or 63006,i<=number and 9 or 1,i==1 and ids or {},i)
    end
    local stage, side = run(front, {elf("G1",63005,9,{},1)})
    local owner, other = side.pokemon[1], side.pokemon[2]
    for phase=1,3 do
        Formation.add_hp(owner, -math.floor(owner.hp * 0.65))
        triggers.dispatch(owner,22)
        assert(owner.sprite == 641890 + phase and owner.hp == 10000)
        for _, field in ipairs({"physical_defense", "special_defense"}) do
            assert(stage:get(owner,field) == 10000 + phase*1000)
            assert(stage:get(other,field) == 10000)
        end
        assert(stage:get(owner,"toughness") == 400 + phase*80)
        assert(stage:get(other,"toughness") == phase*80)
        assert(stage:get(owner,"rebound_damage_reduction") == 500)
        assert(stage:get(other,"rebound_damage_reduction") == 0)
        if phase == 1 then
            triggers.dispatch(owner,26)
            assert(owner.extraround == 0)
        elseif phase == 2 then
            for _, p in ipairs(side.pokemon) do triggers.dispatch(p,26) end
            assert(owner.extraround == (old and (7-number) or 1))
            if not old then triggers.dispatch(owner,26); assert(owner.extraround==1) end
            for i=2,6 do assert(side.pokemon[i].extraround == 0) end
        end
    end
end
for _, n in ipairs({1,3,6}) do softCase(n, baseline) end
if baseline then return "原包小软三阶进化，以及 1、3、6 地面系场景通过" end

local function sharedGround(roll)
    local ids = {105201,105301,105401,105501,105601}
    local s,l = run({elf("First",64189,9,ids,1),elf("Second",64189,9,ids,2),
        elf("Watcher",64186,5,{},3)}, {elf("Earth",63005,9,{},1)})
    local draws = 0
    for i=1,2 do
        local owner = l.pokemon[i]
        for j=1,2 do
            Formation.add_hp(owner,-math.floor(owner.hp*.65))
            triggers.dispatch(owner,22)
        end
        owner.random = function() draws=draws+1; return roll end
    end
    for _,owner in ipairs(l.pokemon) do triggers.dispatch(owner,26) end
    assert(draws==1 and l.pokemon[2].extraround==0 and l.pokemon[3].extraround==0)
    return l.pokemon[1].extraround
end
assert(sharedGround(60)==1 and sharedGround(61)==0)

local front = {elf("F1",64186,5,{},1), elf("F2",64182,9,{57501},2), elf("F3",64182,9,{57501},3),
    elf("F4",64103,16,{27001,27101},4), elf("F5",64132,1,{61409},5), elf("F6",64189,9,{},6)}
front[3].hp_max = 20000
local aids = {}
for i=1,14 do aids[i] = elf("J"..i,63006,17,{22601},i) end
for i=15,16 do aids[i] = elf("M"..i,64188,5,{1008501,1008601,1008701},i) end
for i=17,19 do aids[i] = elf("V"..i,64094,2,{32401,22901},i) end
aids[20] = elf("D",64107,16,{27001,27101},20)
aids[21] = elf("A",54003,2,{17401,22401},21)
aids[22] = elf("B",54003,2,{17401,22401},22)
aids[23] = elf("C",64126,5,{60601,60701},23)
aids[24] = elf("E",64182,9,{57501},24)
local stage, side = run(front,aids)
local target = side.pokemon[1]
assert(#side.pokemon == 6 and #side.assistSources == 24 and #stage.pokemon == 9)
assert(stage:get(target,"hp_max")==27500 and target.hp==27500)
assert(stage:get(target,"speed")==12000 and stage:get(target,"damage_bonus")==200)
assert(stage:get(target,"physical_attack")==13300 and stage:get(target,"fire_damage")==600)
assert(side.pokemon[4].triggers[1][1].count==0)
for _, p in ipairs(side.pokemon) do triggers.dispatch(p,2) end
assert(stage:get(target,"fire_reduce")==500)
assert(side.pokemon[2].hp_shield==8250 and side.pokemon[3].hp_shield==8250)

local function electric(raichu, detective)
    local s,l = run({elf("One",64186,5,{},1)}, {elf("Santa",64126,5,{60601,60701},1),
        elf("Friend1",raichu,5,{},2),elf("Friend2",detective,5,{},3)})
    return s:get(l.pokemon[1],"electric_damage")
end
assert(electric(54052,51007)==500 and electric(11001,51007)==0 and electric(54052,41030)==0)

-- 高档来源的面板较低时，以两项双防的实际贡献决定唯一开场效果。
local a, b = elf("Metal1",64039,17,{47201},1), elf("Metal2",64039,17,{47209},2)
a.physical_defense, a.special_defense = 30000,30000
local s, l = run({elf("Receiver",64186,5,{},1)},{a,b})
assert(s:get(l.pokemon[1],"hp_max")==13600)
local first, second = elf("Metal1",64039,17,{47201},1), elf("Metal2",64039,17,{47201},2)
s, l = run({first,second,elf("Receiver",64186,5,{},3)}, {})
assert(s:get(l.pokemon[3],"hp_max")==11200)
assert(l.pokemon[1].triggers[1][1].count~=0 and l.pokemon[2].triggers[1][1].count==0)
-- 来源记录必须只归属后排，不能把出战基拉祈的同种光环混进助战汇总。
if Formation.assistEffectSource then
    local st, team = run({elf('Target',64186,5,{},1),elf('ActiveJ',63006,17,{22601},2)},
        {elf('AssistJ',63006,17,{22601},1)})
    local total, count = 0, 0
    for _, list in pairs(team.pokemon[1].buffs) do
        for _, effect in pairs(list) do
            local source = Formation.assistEffectSource(effect)
            if source then
                assert(source.isAssist and source.id=='AssistJ' and source.position==1)
                if effect.prop==5 or effect.prop=='hp_max' then
                    total=total+(effect.ratio or 0);count=count+1
                end
            end
        end
    end
    assert(count==1 and math.abs(total-.125)<.000001)
    assert(st:get(team.pokemon[1],'hp_max')==12500)
    -- 槽位转换在前排基础属性装载前生效，助战基数不受转换和光环改变。
    local aid = elf('SlotJ',63006,17,{22601},1)
    aid.assist_slot_type,aid.assist_slot_percent,aid.assist_slot_value=5,20,2000
    local raw = elf('SlotTarget',64186,5,{},1)
    local battle, roster = run({raw},{aid})
    local source = roster.assistSources[1]
    assert(raw.hp_max==10000 and aid.hp_max==10000)
    assert(roster.pokemon[1].att.hp_max.base==12000)
    assert(battle:get(roster.pokemon[1],'hp_max')==13500)
    assert(source.att.hp_max.base==10000 and battle:get(source,'hp_max')==10000)
    assert(#roster.slotBaseBonuses==1 and roster.slotBaseBonuses[1].value==2000)
    local _, activeBuffs, activeStats = Formation.interface()
    activeStats.add(source,5,99999,.5)
    activeBuffs.attach(source,9,1,5,99999,.5)
    assert(battle:get(source,'hp_max')==10000 and next(source.buffs)==nil)
    -- 神·火焰鸟只以自身冻结双攻给前排加成，不受另一只助战或槽位转换放大。
    for _, rank in ipairs({1,9}) do
        local fire1=elf('Fire1',61003,2,{22400+rank,17400+rank},2)
        local fire2=elf('Fire2',61003,2,{22400+rank,17400+rank},8)
        fire1.assist_slot_type,fire1.assist_slot_percent,fire1.assist_slot_value=6,10,1000
        fire2.assist_slot_type,fire2.assist_slot_percent,fire2.assist_slot_value=6,20,2000
        local combat, members=run({elf('FireTarget',64186,5,{},1)},{fire1,fire2})
        local expected=rank==1 and 14300 or 16700
        assert(members.pokemon[1].att.physical_attack.base==13000)
        assert(combat:get(members.pokemon[1],'physical_attack')==expected)
        for _, helper in ipairs(members.assistSources) do
            assert(combat:get(helper,'physical_attack')==10000)
            assert(combat:get(helper,'special_attack')==10000 and next(helper.buffs)==nil)
        end
    end
end
-- 冒险的回合开始通知会逐个发给出战者。后排紧张感应按队计次。
local function crownCase(withFront)
    local receiver = elf('Receiver',64186,5,{},1)
    local rival = withFront and elf('FrontCrown',64182,9,{57501},2)
        or elf('Partner',64186,5,{},2)
    local aid = elf('BackCrown',64182,9,{57501},1)
    aid.hp_max = 20000
    local fire = elf('Fire',61003,2,{22401,17401},2)
    local st, team = run({receiver,rival},{aid,fire})
    local chosen, unused = team.assistSources[1], team.pokemon[2]
    local target = team.pokemon[1]
    assert(target.hp_shield==3000 and unused.hp_shield==3000,
        "登场准备完成即应有后排蕾冠王的 3000 护盾，实得 "..tostring(target.hp_shield))
    assert(chosen.triggerHistory[57501].count==1)
    for round=1,5 do
        st.round = round
        for _, member in ipairs(team.pokemon) do triggers.dispatch(member,2) end
        if round==1 then
            assert(target.hp_shield==3000 and unused.hp_shield==3000,
                "后排蕾冠王应以 20000 当前生命给两个前排各施加 3000 盾，实得 "..tostring(target.hp_shield))
        end
        local record = assert(chosen.triggerHistory[57501])
        assert(record.count==math.min(round,4))
        if round>=2 then assert(record.round==math.min(round,4)) end
        local shield = target.hp_shield
        for _, member in ipairs(team.pokemon) do triggers.dispatch(member,2) end
        assert(record.count==math.min(round,4) and target.hp_shield==shield)
    end
    assert(not (unused.triggerHistory or {})[57501])
    assert((chosen.hp_shield or 0)==0 and chosen.hp==20000)
    local fireSource = team.assistSources[2]
    assert(fireSource.triggerHistory[22401] and not fireSource.triggerHistory[17401])
    local persistent = false
    for _, effects in pairs(target.buffs) do
        for _, effect in pairs(effects) do
            local source, id = Formation.effectTalent(effect)
            if source==fireSource and id==22401 then persistent=true end
        end
    end
    assert(persistent)
end
crownCase(false)
crownCase(true)
-- 出战来源胜出时仍遵守其回合开始事件，未进入首轮时没有出战紧张感的盾。
for _, assists in ipairs({{}, {elf('SmallerCrown',64182,9,{57501},1)}}) do
    local owner = elf('ActiveCrown',64182,9,{57501},1)
    owner.hp_max = 20000
    local st, team = run({owner,elf('Receiver',64186,5,{},2)},assists)
    assert((team.pokemon[2].hp_shield or 0)==0)
    st.round=1
    for _, member in ipairs(team.pokemon) do triggers.dispatch(member,2) end
    assert(team.pokemon[2].hp_shield==3000)
    assert(team.pokemon[1].triggerHistory[57501].count==1)
end
-- 同系计数光环可由前排和助战提供，读取每档原动作对应的属性和数值。
local properties = require("module/battle/core/typedef")
local function elementCase(sid, element, rank, mode, qualified)
    local first = sid==64100 and 303 or 613
    local id = (first+element-1)*100+rank
    local action = tables.character_proto[id].action1
    local key = assert(properties[action[3]])
    local neutral = element==1 and 2 or 1
    local target = elf("Target",61003,element,{},1)
    local second = elf("Secondary",61003,neutral,{},2)
    second.type2 = element
    local front = {target,second}
    local bench = {elf("Third",61003,qualified and element or neutral,{},1)}
    local function holder(uid, slot)
        local ids = {}
        for _, base in ipairs(tables.jingling_proto[sid].character) do
            if tables.character_proto[base+rank] then ids[#ids+1]=base+rank end
        end
        return elf(uid,sid,neutral,ids,slot)
    end
    if mode~="assist" then front[3]=holder("Active",3) end
    if mode~="front" then bench[2]=holder("Helper",2) end
    local st, team = run(front, bench)
    local recipient = team.pokemon[1]
    local expectedCount = qualified and (mode=="mixed" and 2 or 1) or 0
    local function verify(phase)
        local count, sources = 0, {}
        for _, effects in pairs(recipient.buffs) do
            for _, effect in pairs(effects) do
                local source, trait = Formation.effectTalent(effect)
                if trait==id then
                    assert(not sources[source],"同一持有者的同系光环重复挂载")
                    sources[source], count = true, count+1
                end
            end
        end
        assert(count==expectedCount and st:get(recipient,key)==expectedCount*action[4],
            string.format("同系光环 sid=%d id=%d %s %s：实例 %d，属性 %d",
                sid,id,mode,phase,count,st:get(recipient,key)))
    end
    verify("登场")
    for round=1,3 do
        st.round = round
        for repeatEvent=1,2 do
            for _, member in ipairs(team.pokemon) do triggers.dispatch(member,2) end
            verify("回合"..round)
        end
    end
    if mode=="front" and qualified then
        -- 改变一名成员的副属性，分别检查失去门槛后的撤除和恢复门槛后的重挂。
        team.pokemon[2].type2 = 0
        triggers.dispatch(team.pokemon[3],2)
        expectedCount=0
        verify("失去门槛")
        team.pokemon[2].type2 = element
        triggers.dispatch(team.pokemon[3],2)
        expectedCount=1
        verify("恢复门槛")
    end
    if mode~="front" then
        local source = team.assistSources[2]
        assert(next(source.buffs)==nil and (source.triggerHistory or {})[30200+rank]==nil)
        if qualified then assert(source.triggerHistory[id].count==1) end
    end
end
for _,sid in ipairs({64100,64128,64132}) do
    for element=1,18 do
        elementCase(sid,element,1,"front",true)
        elementCase(sid,element,1,"assist",true)
    end
    elementCase(sid,2,1,"mixed",false)
    elementCase(sid,2,9,"mixed",true)
end
-- 阿尔宙斯另一条回合 2 搭档规则仍是本人效果，不借同系光环路径提前触发。
do
    local front = {elf("Arceus",64100,1,{30201,30401},1),elf("Partner",64061,2,{},2),
        elf("Fire2",61003,2,{},3),elf("Fire3",61003,2,{},4)}
    local _, team = run(front,{elf("AidArceus",64100,1,{30201,30401},1)})
    local active, aid = team.pokemon[1], team.assistSources[1]
    assert(not active.triggerHistory[30201] and not aid.triggerHistory[30201])
    triggers.dispatch(active,2)
    assert(active.triggerHistory[30201] and not aid.triggerHistory[30201])
end
return "6 出战、24 助战、蕾冠王首盾及三种精灵十八系光环回归通过"
