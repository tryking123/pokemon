-- 将定制入口绑定到当前宠物详情，复用原生按钮与应用内编辑页。
local view = ...
if view.localPetCustomize then return end
local button = utils.inst(view:findobj("BG/PKM"), view:findobj("BG/Once"))
button.name = "LocalPetCustomize"
view.localPetCustomize = button
button:SetActive(true)
local rect = view:findrect("BG/PKM/LocalPetCustomize")
local aptitude = view:findrect("BG/PKM/Aptitude")
rect.anchorMin = aptitude.anchorMin
rect.anchorMax = aptitude.anchorMax
rect.pivot = Vector2.New(0.5, 0.5)
rect.localScale = Vector3.one
rect.sizeDelta = Vector2.New(130, 48)
rect.anchoredPosition = Vector2.New(aptitude.anchoredPosition.x, aptitude.anchoredPosition.y - 64)
local label = view:findtext("BG/PKM/LocalPetCustomize/Text")
label.text = "定制"
label.fontSize = 24
utils.add_click_event(button, function()
    local role = chr_model.get_chr()
    local pet = view.data
    if role and role.id and pet and pet.uid then
        UnityEngine.Application.OpenURL("pokemon-local://pet-customize?role=" .. tostring(role.id)
            .. "&pet=" .. tostring(pet.uid))
    end
end)

-- 原 S2047 只替换全局缓存；详情仍持有旧对象，需按当前 UID 重绑。
-- gpet_model 的全局 pet 监听先注册，原 Network.Notify 按注册顺序执行。
if _G.LocalPetCustomizeRefresh then
    Network.RemoveListener("pet", _G.LocalPetCustomizeRefresh)
end
local destroyed = false
local function refresh(response)
    if destroyed or view.isopen == false or not view.data then return end
    local uid = view.data.uid
    for _, pet in pairs((response and response.pet_info) or {}) do
        if pet.uid == uid then
            local current = gpet_model.get_pet(uid)
            if current then view:setdata(current) end
            return
        end
    end
end
_G.LocalPetCustomizeRefresh = refresh
Network.AddListener("pet", refresh)
local originalDestroy = view.destroy
view.destroy = function(self, ...)
    destroyed = true
    Network.RemoveListener("pet", refresh)
    if _G.LocalPetCustomizeRefresh == refresh then _G.LocalPetCustomizeRefresh = nil end
    if originalDestroy then return originalDestroy(self, ...) end
end
