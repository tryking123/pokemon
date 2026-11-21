local product, skipVoucher = ...
local item = data_mgr.get_one("chongzhi_proto", product)
-- 原 SDK 的第二参数表示已选择现金支付，必须跳过方式选择，避免循环打开弹窗。
if not skipVoucher and item.ifvoucher then
    rechagepopup.show(product)
    return
end
local function escape(value)
    return (tostring(value):gsub("([^%w%-_%.~])", function(c) return string.format("%%%02X", c:byte()) end))
end
local role, server = chr_model.get_chr(), chr_model.get_server()
local fields = {userid=sdk_mgr.get_userid(), serverid=server.id, uid=role.id,
    appname=AppConst.AppID, platformid=AppConst.AppChannel, channel=AppConst.AppChannel, product_id=product}
local form = {}
for _, key in ipairs({"userid", "serverid", "uid", "appname", "platformid", "channel", "product_id"}) do
    form[#form + 1] = key .. "=" .. escape(fields[key])
end
local ok, failure = pcall(function()
    local reply = PokemonOffline:Api("POST", "/getorderid", table.concat(form, "&"), nil,
        "application/x-www-form-urlencoded")
    local order = reply.status == 200 and reply.text:match("^0,(.+)$")
    assert(order, reply.text or "无法创建本地模拟订单")
    sdk_mgr.purchase(order, product)
end)
if not ok then
    load_popup.hide()
    label_popup.show(tostring(failure))
end
