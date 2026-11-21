local callbacks, labels = ...
callbacks[#callbacks + 1] = function()
    if not local_tablet_supply_listener then
        Network.AddListener("local_tablet_unlock_supply", function(response)
            label_popup.show(response.message or "已发送，请到邮箱领取后解锁超古代石盘")
        end)
        local_tablet_supply_listener = true
    end
    Network.Request("local_tablet_unlock_supply", {action = 1})
end
labels[#labels + 1] = "一键解锁超古代石盘"
