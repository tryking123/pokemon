local view, close = ...
-- 这些原标签会在 Start 时套用固定译文。先停用改作本地用途的四个标签，按钮副本也会继承停用状态。
for _, path in ipairs({"Enter/Text", "CarBon/BG/Image/Text", "Clean/BG/Image/Text", "Mask/Text"}) do
    local selector = view:findcom(path, "UILanguageSelector")
    if selector then selector.enabled = false end
end
local enter = view:findobj("Enter")
local parent = enter.transform.parent.gameObject
local controls = {}
local function make(name, caption, x, y, click)
    local button = utils.inst(parent, enter)
    button.name = name
    button:SetActive(true)
    button.transform.anchoredPosition = Vector2.New(x, y)
    local label = utils.findtext(button, "Text")
    label.fontSize = 26
    label.raycastTarget = false
    label.text = caption
    utils.add_click_event(button, click)
    controls[name] = {button = button, label = label, caption = caption}
end
local function eligible(state)
    for _, group in ipairs(state.sdids or {}) do
        if group == 100001 and view.data and view.data.sid == group then return true end
    end
    return false
end
local function action(call)
    local state = gdun_model.get_hook() or {}
    if eligible(state) then call(state) end
end
make("LocalSwitch", "开始挂机", -192, -450, function()
    action(function(state)
        Network.Request("ask_onhook", {sid = state.sid == 100001 and 0 or 100001})
    end)
end)
make("LocalClaim", "领取收益", 0, -450, function()
    action(function(state)
        if state.sid ~= 100001 then label_popup.show("请先切换到副本挂机") return end
        Network.Request("ask_onhook_award", {ntype = 0})
    end)
end)
make("LocalChest", "首通宝箱", -110, -280, function()
    action(function()
        msg_popup.show("领取首次通关宝箱的银钥匙×1？每个角色可领取一次。", "首通宝箱", 2, function(yes)
            if yes then Network.Request("ask_box", {uid = "dungeon-clear-100001"}) end
        end)
    end)
end)
make("LocalFast", "快速扫荡", 110, -280, function()
    action(function()
        msg_popup.show("需要战力9900。消耗本日1次普通副本扫荡次数，立即结算？", "快速扫荡", 2, function(yes)
            if yes then Network.Request("ask_fastbattle", {sid = 100001}) end
        end)
    end)
end)
enter.transform.anchoredPosition = Vector2.New(192, -450)
view:addclick("Enter", close)
view:findobj("CarBon/ScrollView"):SetActive(false)
view:findobj("Clean/ScrollView"):SetActive(false)
view:findobj("Fes"):SetActive(false)
for _, section in ipairs({"CarBon", "Clean"}) do
    view:findrect(section .. "/OutLay").anchoredPosition = Vector2.New(0, 128)
end
local function redraw()
    if not view.data then return end
    local state = gdun_model.get_hook() or {}
    local config = data_mgr.get_one("dungeons_proto", view.data.sid)
    local ready = eligible(state)
    view:findcom("Map", "RawImage").texture = res_mgr.load_texture("Hook", config.img .. ".jpg")
    view:findtext("Title").text = config.name .. (state.sid == 100001 and " · 挂机中" or " · 冒险中")
    view:findtext("CarBon/BG/Image/Text").text = "副本每秒收益"
    view:findtext("Clean/BG/Image/Text").text = "待领收益"
    view:findtext("Mask/Text").text = "有效累计 " .. tostring(state.last or 0) .. " 秒  ·  点击空白处返回"
    local reward = state.reward or {}
    local amounts = {reward.exp or 0, reward.exp1 or 0, reward.coins or 0}
    for i = 1, 3 do
        view:findtext("CarBon/OutLay/" .. i .. "/Text").text = config.autoreward[i] .. "/s"
        view:findtext("Clean/OutLay/" .. i .. "/Text").text = tostring(amounts[i])
    end
    controls.LocalSwitch.caption = state.sid == 100001 and "恢复冒险" or "开始挂机"
    for _, control in pairs(controls) do
        control.button:SetActive(ready)
        control.label.text = control.caption
    end
    view:findtext("Enter/Text").text = "返回"
end
view.localHookRedraw = redraw
Event.AddListener("UPDATE_HOOK", redraw)
