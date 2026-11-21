local root, controls = ...
local buy = utils.findrect(root, "Button")
local exchange = utils.inst(root, controls.btn)
exchange.name = "StaminaExchange"
local rect = utils.findrect(root, "StaminaExchange")
local y = buy.anchoredPosition.y
buy.anchoredPosition = Vector2.New(-108, y)
rect.anchoredPosition = Vector2.New(108, y)
rect.sizeDelta = Vector2.New(212, buy.sizeDelta.y)
local text = utils.findtext(root, "StaminaExchange/Text")
for _, path in ipairs({"StaminaExchange/Text", "GameObject/Image/Timer"}) do
    local selector = utils.findcom(root, path, "UILanguageSelector")
    if selector then selector.enabled = false end
end
text.text = "兑换副本次数"
text.fontSize = 24
exchange:SetActive(true)
if not local_stamina_exchange_listener then
    Network.AddListener("local_stamina_exchange", function(response)
        if response and eparser.try(response.errno) then label_popup.show("今日普通副本次数 +1") end
    end)
    local_stamina_exchange_listener = true
end
utils.add_click_event(exchange, function()
    if (chr_model.get_chr().tiredpower or 0) < 200 then
        label_popup.show("需要额外体力100点，初始100点保留")
        return
    end
    msg_popup.show("消耗100体力，增加今日普通副本次数1次？次数将在北京时间零点重置。", "兑换副本次数", 2, function(ok)
        if ok then Network.Request("local_stamina_exchange", {}) end
    end)
end)
