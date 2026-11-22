-- 按权威剩余格数领取精灵，未领取的数量继续保留在银行。
local rows = ...
local count = elf_model.get_elfcount() or {}
local capacity = math.max(0, math.floor(tonumber(count.bagmax) or 0))
local used = tonumber(count.bagnum)
if not used then
    used = 0
    for _ in pairs(elf_model.get_elflist() or {}) do used = used + 1 end
end
local remaining = math.max(0, capacity - math.max(0, math.floor(used)))
if remaining == 0 then
    eparser.try(1008)
    return {}
end
local order, quantities = {}, {}
for index = 1, #rows, 2 do
    local sid, quantity = tonumber(rows[index]), tonumber(rows[index + 1])
    if sid and sid > 0 and sid == math.floor(sid) and quantity and quantity > 0 then
        if not quantities[sid] then order[#order + 1] = sid; quantities[sid] = 0 end
        quantities[sid] = quantities[sid] + math.floor(quantity)
    end
end
local result = {}
for _, sid in ipairs(order) do
    if remaining == 0 then break end
    local accepted = math.min(remaining, quantities[sid])
    if accepted > 0 then
        result[#result + 1] = sid
        result[#result + 1] = accepted
        remaining = remaining - accepted
    end
end
return result
