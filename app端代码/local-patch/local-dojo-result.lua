-- local_dojo_result：道馆原胜负页打开前，用当前挑战的种子和阵容编号确认终局。
local owner, winner = ...
if owner.model.get_type() ~= BATTLE_TYPE.TAOIST then return end
local battle = owner.view.data
local enemy = battle.up.identifer
if type(enemy) ~= "string" or not enemy:match("^dojo%-") then return end
-- 与原胜负页相同，只有己方阵容为胜方时才判胜。
local side = winner and winner.identifer == battle.down.identifer and battle.down.identifer or enemy
Network.Request("dungeon_sync", {sid = owner.model.get_data().seed, objs = {enemy, side}, flag = 4}, false)
