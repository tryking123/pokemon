-- 极巨化页沿用原技能预览，并从标题进入当前精灵的技能书管理窗口。
local controls, elf = ...
local full = elf.isdynamax == true
controls.active:SetActive(not full)
controls.consume.gameObject:SetActive(not full)

if not controls.bookTitle then
    local title = utils.findtext(controls.aspect, "Image/Title3")
    controls.bookTitle = title
    controls.previewTitle = title.text
    utils.add_click_event(title.gameObject, function()
        local current = controls.bookElf
        if not current or not current.dynapath then return end
        if #glarge_model.get_list() == 0 and #glarge_model.get_chip() == 0 and not current.dynabook then
            label_popup.show(lang_mgr.get_one("sprite", 453))
            return
        end
        require("module.sprite.large.large_inlay").show(current)
    end)
end
controls.bookElf = elf
controls.bookTitle.text = elf.dynapath and "镶嵌技能书 ›" or controls.previewTitle
return full
