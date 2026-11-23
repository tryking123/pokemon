-- local_rocket_result：原 Stage 已产生胜方，回传该次冻结阵容及实际存活成员的生命。
local owner, winner = ...
if not winner then return end
local current = owner.view.data
local enemy = current.up.identifer
if type(enemy) ~= 'string' or not enemy:match('^npc:rocket:') then return end
local team = winner.identifer == enemy and current.up or current.down
if team.identifer ~= winner.identifer then return end
local health = {}
for _, member in pairs(winner.pokemon) do
    health[member.id] = math.floor(math.max(0, member.hp))
end
local args = {enemy, winner.identifer}
for _, slot in ipairs(team.pokemon) do
    args[#args + 1] = slot.id
    args[#args + 1] = tostring(math.min(slot.hp_max, health[slot.id] or 0))
end
Network.Request('dungeon_sync', {sid = owner.model.get_data().seed, objs = args, flag = 3}, false)
