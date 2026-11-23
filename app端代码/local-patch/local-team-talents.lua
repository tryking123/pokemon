local formation, names = ...
local _, buffs, stats, triggers = formation.interface()
local originalGet = stats.get
local asset = require("module/battle/core/asset")
local originalLoad = triggers.load
local auraOwner

-- 同一持有者的同一常驻效果可同时配置为登场和回合刷新。只配对相同文案、
-- 条件、次数及完整动作参数；混合天赋中的攻击动作和不同条件的额外加成各自独立。
local function auraSlots(ids, loaded, event)
    local slots, ordinal = {}, 0
    for _, id in ipairs(ids) do
        local row = asset.character_proto[id]
        if row.event == event then
            ordinal = ordinal + 1
            local rule = loaded[event][ordinal]
            -- 原数据管理器会把文案编号就地替换成中文文本，两种读取阶段均需支持。
            local description = row.description
            local hasDescription = (type(description) == "number" and description > 0)
                or (type(description) == "string" and description ~= "")
            for index, action in ipairs(rule.action or {}) do
                if hasDescription and
                    (action[1] == 35 or (action[1] == 27 and action[8] ~= 2)) then
                    local key = table.concat({description, row.mode or 0, row.repeated or 0,
                        table.concat(row.condition1 or {}, ","), table.concat(row.condition2 or {}, ","),
                        table.concat(row.condition3 or {}, ","), table.concat(row.condition4 or {}, ","),
                        table.concat(action, ",")}, "|")
                    slots[key] = slots[key] or {}
                    table.insert(slots[key], {list = rule.action, index = index})
                end
            end
        end
    end
    return slots
end

local function markAura(slot)
    local action = {unpack(slot.list[slot.index])}
    action.teamAura = true
    slot.list[slot.index] = action
end

function triggers.load(ids)
    local loaded = originalLoad(ids)
    if loaded[1] and loaded[2] then
        local starts = auraSlots(ids, loaded, 1)
        for key, refresh in pairs(auraSlots(ids, loaded, 2)) do
            local opening = starts[key]
            if opening then
                for i = 1, math.min(#opening, #refresh) do
                    markAura(opening[i])
                    markAura(refresh[i])
                end
            end
        end
    end
    return loaded
end

for _, opcode in ipairs({27, 35}) do
    local original = triggers.action[opcode]
    triggers.action[opcode] = function(caster, action, ...)
        local previous = auraOwner
        auraOwner = action.teamAura and caster
        original(caster, action, ...)
        auraOwner = previous
    end
end

local function clearAuras(owner)
    for _, entry in ipairs(owner.teamAuras or {}) do
        local list = entry.target.buffs[entry.kind]
        if list and list[entry.effect.id] == entry.effect then
            buffs.delete(entry.target, entry.kind, entry.effect.id)
        end
    end
    owner.teamAuras = nil
end

local dispatch = triggers.dispatch
function triggers.dispatch(owner, event, ...)
    if event == 2 and owner.teamAuras then clearAuras(owner) end
    return dispatch(owner, event, ...)
end

local function removeAuraOnExit(stage)
    if stage.teamAuraExit then return end
    local elfdead = stage.elfdead
    function stage:elfdead(actor)
        if actor.teamAuras then clearAuras(actor) end
        return elfdead(self, actor)
    end
    stage.teamAuraExit = true
end

-- 由原引擎负责过滤目标、免疫、驱散及到期；只标记实际挂上的友军属性效果。
function buffs.attachTeam(actor, kind, ...)
    local effect = buffs.attach(actor, kind, ...)
    if effect then
        effect.teamAttribute = names[effect.prop] or effect.prop
        if auraOwner then
            -- 持续到来源下次刷新或离场。由原驱散和免疫处理主动移除。
            removeAuraOnExit(auraOwner.stage)
            effect.round = 255
            auraOwner.teamAuras = auraOwner.teamAuras or {}
            table.insert(auraOwner.teamAuras, {target = actor, kind = kind, effect = effect})
        end
    end
    return effect
end

local function contributions(list, name)
    local extra, ratio = 0, 0
    if list then
        for _, effect in pairs(list) do
            if effect.teamAttribute == name then
                extra = extra + (effect.extra or 0)
                ratio = ratio + (effect.ratio or 0)
            end
        end
    end
    return extra, ratio
end

local function attribute(actor, property, sourceOnly)
    local name = names[property] or property
    local row = actor.att and actor.att[name]
    local active = actor.buffs
    if not row or not active or not (active[9] or active[14]) or name == "hp" or name == "hp_shield" then
        return originalGet(actor, property)
    end
    local flat, ratio = contributions(active[9], name)
    local shared = contributions(active[14], name)
    flat = flat + shared
    if (flat == 0 and ratio == 0) or (not sourceOnly and (flat == 0 or ratio == 0)) then
        return originalGet(actor, property)
    end
    local personalBase = row.base + ((row.extra or 0) - flat)
    local personalRatio = (row.ratio or 0) - ratio
    local value = personalBase * (1 + personalRatio)
    if not sourceOnly then
        value = value + flat * (1 + personalRatio) + personalBase * ratio
    end
    value = math.floor(value)
    local definition = stats[name]
    if definition and definition.affix then value = value + definition.affix(actor) end
    if row.min and value < row.min then return row.min end
    if row.max and value > row.max then return row.max end
    return value
end

function stats.teamSource(actor, property, selector)
    if selector == 2 then return stats.get(actor, property) end
    return attribute(actor, property, true)
end

function stats.get(actor, property)
    return attribute(actor, property, false)
end
