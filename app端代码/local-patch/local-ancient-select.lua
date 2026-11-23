-- 点击原材料入口后自动选择；每次打开重新计算，不创建额外按钮。
local view, controls, cards, eyes, elves = ...
local limit = math.max(0, tonumber(view.data and view.data[2]) or 0)
view.array = {{[1] = {}, [2] = {}, [3] = {}}, {}}
view.data[1] = view.array
local count = 0
local eyeList = eyes()
local cardList = cards()
for _, item in ipairs(eyeList) do
    if count >= limit then break end
    view.array[1][3][item.uid] = item
    count = count + 1
end
for _, item in ipairs(cardList) do
    if count >= limit then break end
    if item.type == 1 and (item.id == 21090 or item.id == 21091) then
        view.array[1][item.mold][item.uid] = item
        count = count + 1
    end
end
-- 仅使用原页面筛选出的可消耗精灵，排除当前培养目标及不合条件的精灵。
if count < limit then
    local candidates = elves()
    table.sort(candidates, function(a, b) return tostring(a.uid) < tostring(b.uid) end)
    for _, item in ipairs(candidates) do
        if count >= limit then break end
        view.array[2][item.uid] = item.uid
        count = count + 1
    end
end
local first = view.list and view.list[1]
if first and first.type == 1 and first.mold == 3 then view.list = eyeList end
view:commit(true)
if count < limit then label_popup.show("材料不足，已按黄金眼、道具、精灵顺序选择可用材料") end
