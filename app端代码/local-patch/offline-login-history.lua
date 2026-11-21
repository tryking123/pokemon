local accept = ...
local function escape(value)
    return (tostring(value):gsub("([^%w%-_%.~])", function(c) return string.format("%%%02X", c:byte()) end))
end
local account = PlayerPrefs.GetString("Account", "null")
local reply = PokemonOffline:Api("GET", "/get_my_plyer?account=" .. escape(account) .. "&channel=" .. escape(AppConst.AppChannel))
assert(reply.status == 200, "无法读取本地角色历史")
accept(reply.text)
