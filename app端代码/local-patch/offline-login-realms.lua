local accept = ...
local reply = PokemonOffline:Api("GET", "/realmlist")
assert(reply.status == 200, "无法读取本地区服")
accept(reply.text)
