local popup, item = ...
local sid = tonumber(item.sid)
local function owned()
    for _, row in pairs(gbag_model.get_suitbag() or {}) do
        if tonumber(row.id) == sid then return row end
    end
end

local stack = owned()
if not stack or (stack.n or 0) < 1 then return end
local rule = data_mgr.get_one("suitname_proto", sid)
local unit = (rule.fenjie or 0) * 100
if unit < 1 then return end

local selector = utils.findcom(popup.go, "Btn/Path/Text", "UILanguageSelector")
if selector then selector.enabled = false end
popup.path:SetActive(true)
utils.findtext(popup.go, "Btn/Path/Text").text = "出售"
utils.add_click_event(popup.path, function()
    local current = owned()
    if not current or current.lock or (current.n or 0) < 1 then
        label_popup.show("请先解锁背包中的贴纸")
        return
    end
    local text = string.format("出售 1 张%s可获得 %d 金币。背包现有 %d 张。", rule.name, unit, current.n)
    msg_popup.show(text, "出售套装贴纸", 2, function(ok)
        if not ok then return end
        local latest = owned()
        if not latest or latest.lock or (latest.n or 0) < 1 then
            label_popup.show("背包已更新，请重新选择未锁定的贴纸")
            return
        end
        Network.Request("sellsuit_eq", {material=sid, num=1})
    end)
end)
if not local_suit_sale_listener then
    Network.AddListener("sellsuit_eq", function(reply)
        if reply and eparser.try(reply.errno) then
            Event.Brocast("CLOSR_SUIT_SHOW")
            label_popup.show("贴纸已售出，金币已到账")
        end
    end)
    local_suit_sale_listener = true
end
