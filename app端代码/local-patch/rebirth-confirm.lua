local model, uid, mode = ...
local title = mode == 1 and "完美重生" or "普通重生"
local stone = mode == 1 and "完美重生石" or "普通重生石"
local text = string.format("消耗1个%s，将这只精灵重置为1级、0星。个体值、性格和当前进化形态保留，极巨阶和已装极巨技能清除。\n\n请核对当前页面的返还预览。旧存档中未记录的觉醒素材无法返还。是否继续？", stone)
msg_popup.show(text, title, 2, function(confirmed)
    if confirmed then model.elf_reborn(uid, mode) end
end)
