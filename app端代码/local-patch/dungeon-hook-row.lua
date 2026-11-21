local row, view = ...
local sid = row.data.sid
local function eligible()
    for _, group in ipairs((gdun_model.get_hook() or {}).sdids or {}) do
        if group == 100001 and group == row.data.sid then return true end
    end
    return false
end
local available = sid == 100001 and eligible()
if available and not row.hookButton then
    local parent = utils.findobj(row.go, "Btns")
    local button = utils.inst(parent, row.grade.gameObject)
    button.name = "LocalDungeonHook"
    row.hookButton = button
    button.transform.localScale = Vector3.one
    button.transform.sizeDelta = Vector2.New(178, 42)
    local text = utils.findtext(button, "Text")
    text.text = "副本挂机"
    text.raycastTarget = false
    utils.add_click_event(button, function()
        if eligible() then
            local panel = view:loadview("mopuphook_view")
            panel:open()
            panel:setdata({sid = row.data.sid})
        end
    end)
end
if row.hookButton then
    row.hookButton:SetActive(available)
    local height = available and 42 or 66
    row.battle.transform.sizeDelta = Vector2.New(178, height)
    row.clear.transform.sizeDelta = Vector2.New(178, height)
end
