-- 记录原引擎实际分派的动作；历史按来源和规则聚合，持续效果随 Buff 实例失效。
do
    local origins = setmetatable({}, {__mode = 'k'})
    local pending, current
    local handle = triggers.handle
    function triggers.handle(source, actions, ...)
        local previous = pending
        local id = ruleIds[actions]
        pending = id and {source=source, id=id} or nil
        handle(source, actions, ...)
        pending = previous
    end

    local attach = buffs.attach
    function buffs.attach(target, kind, ...)
        if target.isAssist then return nil end
        local effect = attach(target, kind, ...)
        if effect and current then origins[effect] = current end
        return effect
    end
    local add = stats.add
    function stats.add(target, ...)
        if target.isAssist then return end
        return add(target, ...)
    end

    for code, action in pairs(triggers.action) do
        if type(action) == 'function' then
            triggers.action[code] = function(source, ...)
                local previous = current
                current = pending and pending.source == source and pending or nil
                if current and not current.recorded then
                    recordTalent(source, current.id)
                    current.recorded = true
                end
                local function finish(...)
                    current = previous
                    return ...
                end
                return finish(action(source, ...))
            end
        end
    end
    function formation.effectTalent(effect)
        local origin = origins[effect]
        if origin then return origin.source, origin.id end
    end
    function formation.assistEffectSource(effect)
        local source = formation.effectTalent(effect)
        if source and source.isAssist then return source end
    end
end
