-- 保留原被动表；迁移的扩展被动按原编号从 character2 读取。
local lookup = ...
local cached = rawget(_G, "PokemonAwakeCharacterLookup")
if cached and cached.wrapper == lookup then return lookup end
if cached and cached.original == lookup then return cached.wrapper end
local function wrapped(kind, id, ...)
    local row = lookup(kind, id, ...)
    if row == nil and kind == "character_proto" then
        return lookup("character2_proto", id, ...)
    end
    return row
end
rawset(_G, "PokemonAwakeCharacterLookup", {original = lookup, wrapper = wrapped})
return wrapped
