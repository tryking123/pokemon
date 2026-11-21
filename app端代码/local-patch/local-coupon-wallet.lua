local view = ...
local template = view:findobj("Btn_get")
local parent = template.transform.parent.gameObject
local ids = {101,102,103,104,105,106,107,201,202,203,204,205,206,207,1004}
local index, state = 1, nil
local buttons = {}
local function button(key, title, x, y, width, click)
    local obj = utils.inst(parent, template)
    obj.name = "LocalCoupon" .. key
    obj.transform.anchoredPosition = Vector2.New(x, y)
    obj.transform.sizeDelta = Vector2.New(width, 50)
    obj:SetActive(true)
    local label = utils.findtext(obj, "Text")
    label.fontSize = 23
    label.raycastTarget = false
    label.text = title
    buttons[key] = {obj = obj, label = label}
    if click then utils.add_click_event(obj, click) else utils.remove_click_event(obj) end
end
local function query(issue, day)
    Network.Request("local_coupon_wallet", {kind = ids[index], issue = issue or 0, day = day or -1})
end
local function page(direction)
    index = (index - 1 + direction) % #ids + 1
    state = nil
    query()
end
button("Prev","上一张",-227,-475,128,function() page(-1) end)
button("Title","本地券包",0,-475,310)
button("Next","下一张",227,-475,128,function() page(1) end)
button("Issue","领本日券",-183,-537,155,function()
    if state and state.status == 0 then query(1,state.day) end
end)
local function settle(sell)
    if not state or state.status ~= 1 then return end
    local shown, kind = state, state.kind
    local reward = sell and ((shown.coins or 0) > 0 and shown.coins .. "金币" or shown.diamonds .. "钻石") or shown.redeem .. "钻石"
    msg_popup.show("消耗" .. shown.name .. "，获得" .. reward .. "？", sell and "出售活动券" or "使用本地券", 2, function(yes)
        if not yes or not state or state.kind ~= kind or state.uid ~= shown.uid then return end
        if kind == 1004 then
            if not sell then Network.Request("voucher_recharge", {paysid = 5, n = 1}) end
        else
            Network.Request(sell and "voucher_sell" or "voucher_use", {sid = shown.uid})
        end
    end)
end
button("Use","兑换",0,-537,155,function() settle(false) end)
button("Sell","出售",183,-537,155,function() settle(true) end)
button("Info","正在读取本地券包",0,-598,620)
buttons.Info.label.fontSize = 21
local function update(response)
    if not response or response.kind ~= ids[index] then return end
    state = response
    buttons.Title.label.text = response.name .. "  " .. index .. "/" .. #ids
    buttons.Issue.obj:SetActive(response.status == 0)
    buttons.Use.obj:SetActive(response.status == 1)
    buttons.Sell.obj:SetActive(response.status == 1 and response.kind ~= 1004)
    local suffix = ({[0]="今日可领", [1]="已持有", [2]="已兑换", [3]="已出售"})[response.status] or "未知状态"
    local sale = response.kind == 1004 and "" or ((response.coins or 0) > 0 and " · 可售" .. response.coins .. "金币" or " · 可售" .. (response.diamonds or 0) .. "钻")
    buttons.Info.label.text = suffix .. " · 零点到期 · 可兑" .. (response.redeem or 0) .. "钻" .. sale
end
local function settled(response)
    if not response then return end
    if response.errno ~= 0 then label_popup.show("本地券已到期、已结算或尚未领取") end
    query()
end
for _, name in ipairs({"voucher_use","voucher_sell","voucher_recharge"}) do Network.AddListener(name, settled) end
Network.AddListener("local_coupon_wallet", update)
view.localCouponClose = function()
    for _, name in ipairs({"voucher_use","voucher_sell","voucher_recharge"}) do Network.RemoveListener(name, settled) end
    Network.RemoveListener("local_coupon_wallet",update)
end
for _, key in ipairs({"Issue","Use","Sell"}) do buttons[key].obj:SetActive(false) end
query()
