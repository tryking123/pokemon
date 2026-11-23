-- 公告与登录共用进程内桥，不创建 WWW 或网络请求。
local cached, model = ...
local text = cached
if not text then
    local reply = PokemonOffline:Api("GET", "/notice.txt")
    assert(reply.status == 200, "无法读取本地公告")
    text = reply.text
end
model:brocast("set_notice", text)
return text
