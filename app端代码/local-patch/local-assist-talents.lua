-- 助战只作为特性来源。Stage、胜负判断、攻击选目标和回放均只看原出战数组。
local function family(id)
    return math.floor(id / 100)
end

local function recordTalent(source, id)
    source.triggerHistory = source.triggerHistory or {}
    local row = source.triggerHistory[id] or {count=0}
    row.count = row.count + 1
    row.round = source.stage.round
    source.triggerHistory[id] = row
end

local groundTriggers = setmetatable({}, {__mode = "k"})
local function groundRule(id)
    return id >= 104500 and id <= 105000 and id % 100 == 0
end

local function teamAction(a)
    local op = a[1]
    return op == 35 or op == 82 or (op == 27 and a[8] ~= 2)
        or op == 23 or op == 24 or op == 29 or (op == 31 and a[3] == 2)
        or (op == 51 and a[3] == 2) or (op == 52 and a[4] == 2)
        or (op == 14 and a[2] == 2) or (op == 76 and a[3] == 2)
end

-- 特性表按编号分档。同一实例的事件记录保留编号，便于区分开场、常驻和本人行动。
local ruleIds = setmetatable({}, {__mode = "k"})
local loadAuras = triggers.load
function triggers.load(ids)
    local loaded = loadAuras(ids)
    local counts = {}
    for _, id in ipairs(ids or {}) do
        local event = asset.character_proto[id].event
        counts[event] = (counts[event] or 0) + 1
        local rule = loaded[event][counts[event]]
        rule.sourceId = id
        ruleIds[rule.action] = id
        -- 两个动作分别覆盖全队和电系附加，均按原面板的百分比计算。
        local group = family(id)
        if group == 10086 or group == 10087 then
            for index, a in ipairs(rule.action or {}) do
                if a[1] == 35 then
                    local effect = {unpack(a)}
                    effect[4], effect[5] = 0, a[4] / 1000
                    rule.action[index] = effect
                end
            end
        end
    end
    for index = #(loaded[26] or {}), 1, -1 do
        if groundRule(loaded[26][index].sourceId) then
            groundTriggers[loaded] = table.remove(loaded[26], index).sourceId
        end
    end
    return loaded
end

-- 同系人数属于编队条件。登场使用原始条件与动作，出战者在原回合刷新同一份光环。
local function addTypeAuras(sid, rules)
    local first
    if sid == 64100 then first = 303
    elseif sid == 64128 or sid == 64132 then first = 613
    else return end
    for _, rule in ipairs(rules[2] or {}) do
        local group = family(rule.sourceId)
        if group >= first and group < first + 18 then
            for index, action in ipairs(rule.action) do
                if action[1] == 35 then markAura({list=rule.action, index=index}) end
            end
            local opening = rules[1] or {}
            rules[1] = opening
            opening[#opening+1] = {sourceId=rule.sourceId, mode=rule.mode, condition=rule.condition,
                action=rule.action, count=1}
        end
    end
end

local function readAssists(side, entries, random)
    local aids, uid, slots = {}, {}, {}
    for _, member in ipairs(side.pokemon) do uid[member.id] = true end
    for _, raw in ipairs(entries or {}) do
        local pos = raw.position
        if raw.id and raw.id ~= '' and pos and pos >= 1 and pos <= 24
            and not uid[raw.id] and not slots[pos] then
            slots[pos], uid[raw.id] = true, true
            local p = {id = raw.id, sid = raw.sid, sprite = raw.sprite or raw.sid,
                type1 = raw.type1, type2 = raw.type2, position = pos, parent = side.identifer,
                hp = raw.hp or raw.hp_max, att = stats.load(raw), buffs = {}, random = random,
                round = 1, assistSide = side, isAssist = true}
            p.slotAttribute = names[raw.assist_slot_type]
            p.slotPercent = raw.assist_slot_percent or 0
            p.slotValue = raw.assist_slot_value or 0
            -- 保留成对光环的标记后，仅取开场事件中的团队动作或明确允许的梦魇。
            local loaded = triggers.load(raw.trigger or {})
            addTypeAuras(raw.sid, loaded)
            local opening = loaded[1] or {}
            p.triggers = {[1] = opening}
            -- 保留紧张感原始施盾规则供登场和后续轮次共用。
            if raw.sid == 64182 then
                for _, rule in ipairs(loaded[2] or {}) do
                    if family(rule.sourceId) == 575 then
                        p.triggers[2] = {rule}
                        break
                    end
                end
            end
            for _, rule in ipairs(opening) do
                local actions = {}
                for _, a in ipairs(rule.action or {}) do
                    if teamAction(a) or (family(rule.sourceId) == 270 and a[1] == 20) then
                        actions[#actions + 1] = a
                    end
                end
                rule.action = actions
                ruleIds[actions] = rule.sourceId
            end
            aids[#aids + 1] = p
        end
    end
    table.sort(aids, function(a,b) return a.position < b.position end)
    return aids
end

local function eachSource(side, visit)
    for _, p in ipairs(side.activeSources) do visit(p) end
    for _, p in ipairs(side.assistSources) do visit(p) end
end

-- 阵容条件只在判定时读助战，原函数仍处理物种进化链、同系匹配与比较方向。
for _, code in ipairs({20, 21}) do
    local prior = triggers.condition[code]
    triggers.condition[code] = function(p, args, ...)
        local side = p.assistSide
        if not side then return prior(p, args, ...) end
        -- 该文案点名两种最终形态，不能把未进化的皮丘、普通皮卡丘当作替代品。
        if code == 21 and args[2] == 1 and args[3] == 11001 and args[4] == 41030 then
            local raichu, detective = false, false
            eachSource(side, function(member)
                raichu = raichu or member.sid == 54052
                detective = detective or member.sid == 51007
            end)
            return raichu and detective
        end
        local roster = {}
        eachSource(side, function(member)
            -- 机甲超梦的回能明确要求暗黑超梦存活。已阵亡的出战搭档不能借助战队列重新计入。
            if code ~= 21 or args[3] ~= 64114 or member.isAssist or member.hp > 0 then
                roster[#roster + 1] = member
            end
        end)
        local view = setmetatable({stage = {pokemon = roster}}, {__index = p})
        return prior(view, args, ...)
    end
end

-- 逐项核对一次性开场团队效果。持续 255 回合的登场词条和同样
-- 持续 255 回合的常驻词条并存，不能按回合数推断叠加方式。
local openingOnly = {[270]=true, [294]=true, [382]=true, [452]=true, [472]=true,
    [1014]=true, [10085]=true, [10086]=true, [10087]=true}

local function openingStrength(p, group, rule)
    if group == 270 then return p.sid == 64107 and 1 or 0 end
    if group == 10085 then return rule.sourceId % 100 end
    local total = 0
    for _, a in ipairs(rule.action or {}) do
        if a[1] == 27 then
            -- 同一来源的物防、特防分别换算、分别取整后，一起贡献生命上限。
            total = total + math.floor(stats.teamSource(p, a[3], a[8]) * a[4])
        elseif a[1] == 35 then
            total = total + a[4]
        end
    end
    return total
end

local function preferred(first, score, candidate, value)
    if not first or value > score then return true end
    if value ~= score then return false end
    if first.isAssist ~= candidate.isAssist then return not candidate.isAssist end
    return candidate.position < first.position
end

local function selectOpeners(side)
    local chosen, scores = {}, {}
    eachSource(side, function(p)
        for _, rule in ipairs(p.triggers[1] or {}) do
            local id = rule.sourceId
            if id and openingOnly[family(id)] then
                local key = family(id)
                if key >= 10085 and key <= 10087 then key = 10085 end
                local value = openingStrength(p, key, rule)
                if preferred(chosen[key], scores[key], p, value) then
                    chosen[key], scores[key] = p, value
                end
            end
        end
    end)
    eachSource(side, function(p)
        for event, rules in pairs(p.triggers) do
            for _, rule in ipairs(rules) do
                local group = family(rule.sourceId)
                if group >= 10084 and group <= 10087 then
                    if chosen[10085] and chosen[10085] ~= p then rule.count = 0 end
                elseif event == 1 and chosen[group] and chosen[group] ~= p then
                    -- 452xx 和 1014xx 的自身动作与友方动作合为一份全队增益。
                    -- 同一物种的独立个人特性位于其他规则行，保留原触发。
                    rule.count = 0
                end
            end
        end
    end)
end

local originalFormation = formation.new
-- 助战基数已由 App 独立冻结。只复制本场六个前排的原始属性，
-- 在 stats.load 和开场特性之前叠入槽位转换，后排和原协议快照均不改写。
local function withSlotBonuses(data)
    local totals, slots, ids, records = {}, {}, {}, {}
    for _, member in ipairs(data.pokemon or {}) do ids[member.id]=true end
    for _, aid in ipairs(data.assists or {}) do
        local pos = aid.position
        if aid.id and aid.id~='' and pos and pos>=1 and pos<=24 and not ids[aid.id] and not slots[pos] then
            ids[aid.id], slots[pos] = true, true
            local key = names[aid.assist_slot_type]
            local amount = aid.assist_slot_value or 0
            if key and amount>0 then
                totals[key]=(totals[key] or 0)+amount
                records[#records+1]={slot=pos, id=aid.id, sprite=aid.sprite or aid.sid,
                    property=key, percent=aid.assist_slot_percent or 0, value=amount}
            end
        end
    end
    local copy = {}; for k,v in pairs(data) do copy[k]=v end
    copy.pokemon = {}
    for i, member in ipairs(data.pokemon or {}) do
        local prepared = {}; for k,v in pairs(member) do prepared[k]=v end
        for key,value in pairs(totals) do
            prepared[key] = math.min(2147483647,(member[key] or 0)+value)
        end
        if member.hp and member.hp_max and member.hp>=member.hp_max and totals.hp_max then
            prepared.hp=prepared.hp_max
        end
        copy.pokemon[i]=prepared
    end
    return copy, records
end
function formation.new(data, random, ...)
    local prepared, slotBonuses = withSlotBonuses(data)
    local side = originalFormation(prepared, random, ...)
    side.slotBaseBonuses = slotBonuses
    side.activeSources = {}
    for _, p in ipairs(side.pokemon) do
        side.activeSources[#side.activeSources + 1] = p
        p.assistSide = side
        addTypeAuras(p.sid, p.triggers)
    end
    side.assistSources = readAssists(side, data.assists, random)
    selectOpeners(side)
    return side
end

local function bestActive(side, event, group)
    local best, score
    local function consider(p)
        if p.hp > 0 then
            for _, rule in ipairs(p.triggers[event] or {}) do
                if rule.sourceId and family(rule.sourceId) == group then
                    local value = rule.sourceId % 100
                    if group == 575 then value = p.att.hp_max.base
                    elseif group == 271 and p.sid == 64107 then value = value + 100 end
                    if preferred(best, score, p, value) then best, score = p, value end
                end
            end
        end
    end
    for _, p in ipairs(side.activeSources) do consider(p) end
    if group == 575 then
        for _, p in ipairs(side.assistSources) do consider(p) end
    end
    return best
end

-- 动态获得的大地神力原本注册六个条件。统一登记一次团队机制，
-- 实际二阶获得之前不能增加回合；不为同一大回合重复掷骰。
local newTrigger = triggers.new
function triggers.new(list, id)
    if groundRule(id) then
        groundTriggers[list] = id
        return
    end
    newTrigger(list, id)
    local rules = list[asset.character_proto[id].event]
    local rule = rules[#rules]
    rule.sourceId = id
    ruleIds[rule.action] = id
end

local function groundTurn(p, side)
    if not groundTriggers[p.triggers] or (p.sprite ~= 641892 and p.sprite ~= 641893)
        or p.hp <= 0 or side.groundRound == p.stage.round then return end
    side.groundRound = p.stage.round
    local count = 0
    eachSource(side, function(member)
        if member.type1 == 9 or member.type2 == 9 then count = count + 1 end
    end)
    if count > 0 and p.random(1, 100) <= math.min(100, count * 20) then
        recordTalent(p, groundTriggers[p.triggers])
        p.extraround = p.extraround + 1
        p.log('newturn', p.id, p.extraround)
    end
end

local dispatchAuras = triggers.dispatch
local function applyAssistCrown(side, source, round)
    local rule = source.triggers[2][1]
    local before = rule.count
    dispatchAuras(source, 2)
    if rule.count < before then
        side.crownUses, side.crownRound = (side.crownUses or 0) + 1, round
    end
end

-- Stage.prepare 先完成双方所有登场特性，再对出战精灵调用 allready。
-- 此时后排来源已绑定战场，首盾能进入首次快照，并沿用首轮额度。
local allready = formation.allready
function formation.allready(p, ...)
    local side = p.assistSide
    if side and p == side.activeSources[1] and not side.crownUses and #side.assistSources > 0 then
        local chosen = bestActive(side, 2, 575)
        if chosen and chosen.isAssist then applyAssistCrown(side, chosen, p.stage.round) end
    end
    return allready(p, ...)
end

function triggers.dispatch(p, event, ...)
    local side = p.assistSide
    if not side or not p.stage then return dispatchAuras(p, event, ...) end
    if event == 1 and not side.assistsStarted then
        side.assistsStarted = true
        for _, aid in ipairs(side.assistSources) do
            aid.stage, aid.log = p.stage, p.log
            dispatchAuras(aid, 1)
        end
    end
    if event == 26 then groundTurn(p, side) end
    -- 保留其他回合动作以及各自的原次数，最高来源失去资格后再比较存活者。
    local group = event == 2 and 575 or event == 19 and 271
    if not group then return dispatchAuras(p, event, ...) end
    local chosen = bestActive(side, event, group)
    local round = p.stage.round
    local crownReady = group == 575 and side.crownRound ~= round and (side.crownUses or 0) < 4
    if crownReady and chosen and chosen.isAssist then
        applyAssistCrown(side, chosen, round)
        crownReady = side.crownRound ~= round
    end
    local saved, crown, before
    for _, rule in ipairs(p.triggers[event] or {}) do
        if rule.sourceId and family(rule.sourceId) == group then
            if chosen ~= p or (group == 575 and (not crownReady or crown)) then
                saved = saved or {}
                saved[#saved + 1] = {rule, rule.count}
                rule.count = 0
            elseif group == 575 then
                crown, before = rule, rule.count
            end
        end
    end
    dispatchAuras(p, event, ...)
    if crown and crown.count < before then
        side.crownUses, side.crownRound = (side.crownUses or 0) + 1, round
    end
    for _, entry in ipairs(saved or {}) do entry[1].count = entry[2] end
end
