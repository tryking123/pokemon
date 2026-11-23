-- 逐星档验证真实注册表。动作执行及触发条件另由战斗专项验证。
local formation = require("module/battle/core/formation")
local _, _, _, triggers = formation.interface()
local rows = tables.character_proto
local function equalArgs(a, b)
    if #a ~= #b then return false end
    for i, v in ipairs(a) do if b[i] ~= v then return false end end
    return true
end

local function speciesIds(data, star, awaken)
    local offset = star < 10 and star or (data.featureslv or 0)
    local ids, added = {}, {}
    local function add(id)
        if rows[id] and not added[id] then ids[#ids + 1], added[id] = id, true end
    end
    for _, base in ipairs(data.character or {}) do if base > 0 then add(base + offset) end end
    local passive = data.skill3 or {}
    for i = 1, #passive - 1, 2 do if awaken >= passive[i] then add(passive[i + 1]) end end
    return ids
end

-- 两种养成档位各测单持有者和双持有者。用相同的攻击技能对照天赋，
-- 依次让持有者、普通队友和敌方出手，保留引擎实际事件和原条件。
local function battle(engine, sid, data, star, awaken, copies)
    local function team(side)
        local members = {}
        for i = 1, 6 do
            local owner = side == "L" and i <= copies
            local species = owner and sid or 64058
            local template = tables.jingling_proto[species]
            members[i] = {id = "EL00" .. side .. i, parent = side, position = i, sid = species,
                sprite = species, level = 100, gender = 1, quality = template.quality,
                type1 = template.attribute1, type2 = template.attribute2, hp_max = 9900000,
                power = 10000, physical_attack = 20000, special_attack = 20000,
                physical_defense = 10000, special_defense = 10000, speed = 100 + i,
                element_provide = 1000, hit_chance = 10000, sp = 100, add_skill_point_limit = 0, skill_bar = {10052},
                trigger = owner and speciesIds(data, star, awaken) or {}}
        end
        return formation.new({identifer = side, sid = 1, pokemon = members, preference = 1,
            power = 100000, isnpc = false}, function(lo) return lo or 1 end)
    end
    local left, right = team("L"), team("R")
    local stage = engine.stage.new(left, right, 0, 100, "L", false)
    stage:prepare(function() end)
    local count = 0
    local function strike(group, index, opponents)
        local actor = group.pokemon[index]
        if not actor or actor.hp <= 0 then return end
        for _, target in ipairs(opponents.pokemon) do
            if target.hp > 0 then
                stage:perform(actor, actor.skill_bar[1], {target})
                count = count + 1
                return
            end
        end
    end
    strike(left, 1, right)
    strike(left, 2, right)
    strike(left, 3, right)
    strike(right, 1, left)
    assert(count > 0)
    return count
end

local function newOwner(ids)
    local owner = {triggers = triggers.load(ids)}
    local position = {}
    for _, id in ipairs(ids) do
        local row = rows[id]
        local event = row.event
        position[event] = (position[event] or 0) + 1
        local registered = owner.triggers[event][position[event]]
        assert(registered and registered.mode == row.mode, id)
        assert(registered.count == (row.repeated and row.repeated > 0 and row.repeated or nil), id)
        for _, field in ipairs({"action", "condition"}) do
            local index = 0
            for slot = 1, 4 do
                local args = row[field .. slot]
                if args and args[1] and args[1] > 0 and (field ~= "condition" or args[1] ~= 1) then
                    index = index + 1
                    assert(equalArgs(args, registered[field][index]), id)
                end
            end
            assert(index == #(registered[field] or {}), id)
        end
    end
    for event, records in pairs(owner.triggers) do
        assert(#records == position[event], event)
    end
    return owner
end

local expectedOwner, nextRule, records
local function dispatch(owner, event)
    expectedOwner, nextRule, records = owner, 1, owner.triggers[event] or {}
    triggers.dispatch(owner, event)
    assert(nextRule == #records + 1, event)
end

local function check(ids, totals)
    local a, b, neutral = newOwner(ids), newOwner(ids), newOwner({})
    for event, entries in pairs(a.triggers) do
        dispatch(neutral, event)
        dispatch(a, event)
        local others = b.triggers[event]
        for index, rule in ipairs(entries) do
            local other = others[index]
            assert(rule ~= other and (not rule.count or rule.count + 1 == other.count))
            assert(not rule.action or rule.action ~= other.action)
            for _, action in ipairs(rule.action or {}) do
                if action.teamAura then
                    assert((event == 1 or event == 2) and
                        (action[1] == 35 or (action[1] == 27 and action[8] ~= 2)))
                    totals.pairedAuraSlots = totals.pairedAuraSlots + 1
                end
            end
        end
        dispatch(b, event)
        totals.dispatches = totals.dispatches + #entries * 2
    end
    totals.cases = totals.cases + 1
end

return function(engine)
    local species = {}
    for sid, data in pairs(tables.jingling_proto) do
        local counts = {cases = 0, dispatches = 0, pairedAuraSlots = 0, battles = 0, attacks = 0}
        if engine then
            for _, level in ipairs({{math.min(1, data.maxstar or 0), 0},
                    {data.maxstar or 0, data.maxawaken or 0}}) do
                for copies = 1, 2 do
                    local ok, result = pcall(battle, engine, sid, data, level[1], level[2], copies)
                    assert(ok, tostring(sid) .. "/" .. level[1] .. "/" .. copies .. " " .. tostring(result))
                    counts.battles = counts.battles + 1
                    counts.attacks = counts.attacks + result
                end
            end
        end
        species[sid] = counts
    end
    triggers.judge = function(owner)
        assert(owner == expectedOwner)
        return true
    end
    triggers.handle = function(owner, actions)
        assert(owner == expectedOwner and actions == records[nextRule].action)
        nextRule = nextRule + 1
    end
    local function counts() return {cases = 0, dispatches = 0, pairedAuraSlots = 0} end
    local catalog = counts()
    for id in pairs(rows) do check({id}, catalog) end
    for sid, data in pairs(tables.jingling_proto) do
        local result = species[sid]
        for star = 0, data.maxstar or 0 do
            for awaken = 0, data.maxawaken or 0 do
                check(speciesIds(data, star, awaken), result)
            end
        end
        species[sid] = result
    end
    return species, catalog
end
