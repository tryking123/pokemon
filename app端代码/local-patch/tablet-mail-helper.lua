local helper = ...
if helper.local_tablet_mail_wrapped then return end
local original = helper.parse_reward
helper.parse_reward = function(reward, ...)
    local tablets = {}
    for _, item in ipairs(reward and reward.items or {}) do
        if item.type == 888 then tablets[#tablets + 1] = item end
    end
    if #tablets == 0 then return original(reward, ...) end
    local clean = {}
    for key, value in pairs(reward) do clean[key] = value end
    clean.items = {}
    for _, item in ipairs(reward.items or {}) do
        if item.type ~= 888 then clean.items[#clean.items + 1] = item end
    end
    local result = original(clean, ...)
    for _, item in ipairs(tablets) do
        result[#result + 1] = {type = 888, index = item.id, text = item.n}
    end
    return result
end
helper.local_tablet_mail_wrapped = true
