-- 主线新 attempt 到达时复用原异步销毁与重开流程；不修改快照血量和状态。
local data, kind, owner, model = ...
local function attempt_key(value)
    local monster = type(value) == 'table' and value.monster
    local identifier = type(monster) == 'table' and monster.identifer
    if type(identifier) ~= 'string' or identifier == '' then return nil end
    return identifier .. ':' .. tostring(value.seed or '')
end
if BATTLE_TYPE and kind == BATTLE_TYPE.ADVEN then
    local key = attempt_key(data)
    local previous = owner._offline_adven_attempt
    local view = owner.view
    if view and not view._offline_adven_reward then
        local loadview = view.loadview
        view.loadview = function(self, name, ...)
            local panel = loadview(self, name, ...)
            -- 原控制器只在引擎判定我方胜利时创建此面板，使用当前已渲染的敌队实例。
            local fought = self.data
            if name == 'battle_victory' and fought and fought.type == BATTLE_TYPE.ADVEN
                    and fought.up and type(fought.up.identifer) == 'string'
                    and fought.up.identifer:match('^local%-') then
                local open, enemy = panel.open, fought.up.identifer
                panel.open = function(result, ...)
                    result.open = open
                    open(result, ...)
                    Network.Request('dungeon_sync', {sid = 0, objs = {enemy}, flag = 2}, false)
                end
            end
            return panel
        end
        view._offline_adven_reward = true
    end
    if key and previous and key ~= previous and view and view.isopen then
        -- close 只销毁当前页面；原 baseview 的 isclosing/reopen 会排队重开，
        -- 不卸载模块，不替换 model，不提前调用新的 view_start。
        view:close()
    end
    owner._offline_adven_attempt = key
end
model.set_data(data)
model.set_type(kind)
owner:open(Layer.UIPopup)
