local function send(action)
    if not local_fusion_supply_listener then
        Network.AddListener("local_fusion_supply", function(response)
            label_popup.show(response.message)
        end)
        local_fusion_supply_listener = true
    end
    Network.Request("local_fusion_supply", {action = action})
end

local function purchase()
    msg_popup.show("本次消耗5000钻石购买1个合体器。每角色每周可购买一次，北京时间周一零点刷新。是否购买？",
        "合体器周购", 2, function(confirmed) if confirmed then send(2) end end)
end

local eligibility = "同时拥有同一配方的两种80级材料精灵，可终身免费领取1个合体器。领取后仍保留两只材料。"
if freeOnly then
    msg_popup.show(eligibility .. "是否领取？", "免费合体器", 2, function(confirmed) if confirmed then send(1) end end)
else
    msg_popup.show(data_mgr.get_lang(5088) .. "\n\n" .. eligibility .. "\n确定：免费领取。取消：查看每周5000钻的购买确认。",
        "合体说明与合体器补给", 2, function(confirmed) if confirmed then send(1) else purchase() end end)
end
