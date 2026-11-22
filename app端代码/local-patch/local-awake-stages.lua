-- 稀疏技能表只决定技能展示，觉醒步骤始终逐级推进。
local pkm, spriteUtils, elfModel = ...
local awake = spriteUtils.parse_awaken(pkm.elf_proto)
local current = (elfModel.get_awake_info(pkm.id) or {}).lv or 0
local maximum = pkm.elf_proto.maxawaken or 0
local nextLevel = math.min(current + 1, maximum)
pkm.maxawaken = maximum
pkm.maxnum = maximum
pkm.awake = awake
pkm.nowindex = current
pkm.isawakeMax = current >= maximum
pkm.nextindex = nextLevel
pkm.nextawake = {}
for _, skill in ipairs(awake) do
    if skill[1] == nextLevel then
        pkm.nextawake = skill
        break
    end
end
pkm.awakemax = pkm.isawakeMax
return pkm
