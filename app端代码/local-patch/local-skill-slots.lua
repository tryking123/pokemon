-- 主动技能按原技能来源分槽展示，被动不进入安装槽。
local kind, rows, panel, spriteUtils, loadSprite, setSpriteType = ...
local elf = panel.pkm
local category = kind == 2 and 2 or 1
local selected = {}
local seen = {}
if kind ~= 3 then
    for _, row in ipairs(rows or {}) do
        local id = type(row) == "table" and row.sid or row
        local source = spriteUtils.skill_type(elf.sid, id)
        if not source then
            for _, previous in ipairs(elf.old_elfsid or {}) do
                source = spriteUtils.skill_type(previous, id)
                if source then break end
            end
        end
        if source == category and not seen[id] and panel.view.model.skill_ison(elf.id, id) then
            selected[#selected + 1] = {id = id, row = row}
            seen[id] = true
        end
    end
end
for index, slot in ipairs(panel.install) do
    local entry = selected[index]
    slot.data = entry and entry.row or nil
    slot.level.text = ""
    slot.text:SetActive(entry == nil)
    slot.icon:SetActive(entry ~= nil)
    if entry then
        local config = spriteUtils.get_skill_msg(entry.id)
        local image = slot.icon:GetComponent("Image")
        image.sprite = loadSprite("IconSkill", config.icon or 10015)
        setSpriteType(slot.type, config.type)
    end
end
