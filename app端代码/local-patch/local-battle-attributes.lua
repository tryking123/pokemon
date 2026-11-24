-- local_battle_attributes：只在新 Stage 绑定后及用户手动刷新时读取战斗对象。
local owner, stage = ...
local view = owner.view
if view.localAttributeStage == stage then return end
local formation = require('module/battle/core/formation')
local _, buffs, stats = formation.interface()
local names = require('module/battle/core/typedef')
local labels = {
    hp_max='生命上限', physical_attack='物攻', special_attack='特攻',
    physical_defense='物防', special_defense='特防', speed='速度',
    hit_chance='命中', dodge_chance='闪避', crit_chance='暴击', toughness='韧性',
    strength='力量', intellect='智力', reflect='反弹', reduced='减伤',
    damage_bonus='伤害加成', crit_damage_bonus='暴伤加成', damage_lv='伤害等级',
    hp='当前生命', hp_shield='护盾', physical_damage_bonus='物伤加成',
    special_damage_bonus='特伤加成', physical_damage_reduced='物伤减免',
    special_damage_reduced='特伤减免', real_damage_reduction='真实伤害减免',
    add_skill_point_limit='技能点上限加成', ultra_reduce='极巨减免',
    leech='寄生', poison='中毒', paralyze='麻痹', frozen='冰冻', sleep='睡眠',
    confusion='混乱', firing='灼烧', alter_prop='属性增减', alter_prop_by='关联属性增减',
    heal_bonus='治疗加成', curse='诅咒', resurrect='复活', taunt='嘲讽',
    force_sleep='强制睡眠', twining='缠绕', seal='封印', mirror='镜像',
    love='魅惑', trap='陷阱', ever_frost='永冻', SP='技能点', HP='生命',
    prop='属性', extra='固定加成', ratio='比例系数', round='剩余回合',
    teamAttribute='团队加成属性', value='数值', count='次数', sid='来源技能',
}
local elements = {water='水', fire='火', grass='草', dark='恶', fairy='妖精', dragon='龙',
    ice='冰', ghost='幽灵', normal='一般', bug='虫', poison='毒', electric='电', flying='飞行',
    ground='地面', fight='格斗', psychic='超能', rock='岩石', steel='钢'}
local function title(key)
    if labels[key] then return labels[key] end
    if type(key)=='string' then
        for suffix, caption in pairs({damage='伤害加成', reduce='伤害减免', power='威力加成'}) do
            local element = key:match('^(.-)_'..suffix..'$')
            if elements[element] then return elements[element]..caption end
        end
        local base = key:match('^(.-)_bonus$')
        if base and labels[base] then return labels[base]..'加成' end
    end
    return tostring(key)
end
local function number(v)
    if type(v) ~= 'number' then return tostring(v) end
    return string.format('%.3f', v):gsub('%.?0+$', '')
end
local core = {'hp_max','physical_attack','special_attack','physical_defense',
    'special_defense','speed','hit_chance','dodge_chance','crit_chance','toughness'}
local function assistLines(group, actor, heading)
    local aids = group.assistSources or {}
    local lines = {heading, '后排助战 '..#aids..'/24（不参加行动）',
        '槽位基础属性（仅加出战精灵）'}
    local slotBonuses = group.slotBaseBonuses or {}
    if #slotBonuses==0 then lines[#lines+1]='无已激活的槽位基础属性转换' end
    for _, row in ipairs(slotBonuses) do
        lines[#lines+1]='助战'..row.slot..' '..title(row.property)..' +'..number(row.value)..'（'..number(row.percent)..'%）'
    end
    lines[#lines+1]='特性 / Buff 加成（固定量 / 比例系数）'
    lines[#lines+1]='仅统计实际生效的后排来源，不含出战光环。'
    local totals, effects = {}, {}
    for kind, list in pairs(actor.buffs or {}) do
        for _, effect in pairs(list) do
            local source = formation.assistEffectSource and formation.assistEffectSource(effect)
            if source and source.isAssist then
                local key = names[effect.prop] or effect.prop
                if key and (effect.extra or effect.ratio) then
                    local total = totals[key] or {0,0}; totals[key] = total
                    total[1] = total[1] + (effect.extra or 0)
                    total[2] = total[2] + (effect.ratio or 0)
                end
                local species = data_mgr.get_one('jingling_proto', source.sprite or source.sid)
                effects[#effects+1] = {slot=source.position or 0,
                    name=tostring(species and species.name or source.sprite or source.sid),
                    kind=kind, effect=effect, property=key}
            end
        end
    end
    local keys = {}; for key in pairs(totals) do keys[#keys+1]=key end
    table.sort(keys, function(a,b)return tostring(a)<tostring(b)end)
    if #keys==0 then lines[#lines+1]='采集时无已生效的助战属性加成' end
    for _, key in ipairs(keys) do
        local total = totals[key]
        lines[#lines+1]=title(key)..'：固定 '..number(total[1])..' / 比例 '..number(total[2])
    end
    lines[#lines+1]='实际生效的助战 Buff（当前受益精灵）'
    table.sort(effects,function(a,b)
        if a.slot~=b.slot then return a.slot<b.slot end
        if a.kind~=b.kind then return tostring(a.kind)<tostring(b.kind) end
        return (a.effect.id or 0)<(b.effect.id or 0)
    end)
    if #effects==0 then lines[#lines+1]='无；未触发或已结束的效果不计入' end
    for _, row in ipairs(effects) do
        local effect = row.effect
        lines[#lines+1]='助战'..row.slot..' '..row.name..' · '..title(effect.name or (buffs[row.kind] and buffs[row.kind].name) or row.kind)
        if row.property then lines[#lines+1]='  属性 '..title(row.property)..' 固定 '..number(effect.extra or 0)..' 比例 '..number(effect.ratio or 0) end
        if effect.round then lines[#lines+1]='  剩余回合 '..number(effect.round) end
    end
    lines[#lines+1]='助战来源基础六维（仅供核对施放基数）'
    for _, aid in ipairs(aids) do
        local species = data_mgr.get_one('jingling_proto', aid.sprite or aid.sid)
        lines[#lines+1]='助战'..tostring(aid.position)..' '..tostring(species and species.name or aid.sid)
        if aid.slotAttribute then
            lines[#lines+1]='  槽位转换 '..title(aid.slotAttribute)..' '..number(aid.slotPercent)..'% → '..number(aid.slotValue)
        end
        for i=1,6 do
            local key = core[i]; local value = aid.att and aid.att[key]
            lines[#lines+1]='  '..title(key)..' '..number(value and value.base or 0)
        end
    end
    return lines
end
-- 官方表在客户端可已本地化，离线原表则保留词条编号。
local function localized(value)
    if type(value)=='number' and value>0 then
        local word = data_mgr.get_one('word1_proto', value)
        value = word and word.data1
    end
    if type(value)=='string' and value~='' and value~='null' then return value end
end
local function talentLabel(source, id)
    local species = data_mgr.get_one('jingling_proto', source.sid or source.sprite)
    local trait = data_mgr.get_one('character_proto', id)
    local name = trait and localized(trait.name)
    if not name and species then
        for _, base in ipairs(species.character or {}) do
            if math.floor(id/100)==math.floor(base/100) then
                name = localized(species.charactername)
                break
            end
        end
    end
    return (source.isAssist and '助战' or '阵位')..source.position..' '..
        tostring(species and localized(species.name) or source.sid or source.sprite)..
        ' · '..tostring(name or '特性')..' #'..id
end
local function talentLines(group, actor, heading)
    local lines = {heading, '当前仍作用于该精灵的特性'}
    local active = {}
    for _, list in pairs(actor.buffs or {}) do
        for _, effect in pairs(list) do
            local source, id = formation.effectTalent(effect)
            if source then active[talentLabel(source,id)] = true end
        end
    end
    local labels = {}
    for label in pairs(active) do labels[#labels+1]=label end
    table.sort(labels)
    for _, label in ipairs(labels) do lines[#lines+1]=label end
    if #labels==0 then lines[#lines+1]='无持续中特性 Buff' end
    lines[#lines+1]='本场已触发（本精灵及本方助战）'
    local start = #lines
    local function history(source)
        local ids = {}
        for id in pairs(source.triggerHistory or {}) do ids[#ids+1]=id end
        table.sort(ids)
        for _, id in ipairs(ids) do
            local row = source.triggerHistory[id]
            lines[#lines+1]=talentLabel(source,id)
            lines[#lines+1]='  最近第'..row.round..'轮 · 共'..row.count..'次'
        end
    end
    history(actor)
    for _, aid in ipairs(group.assistSources or {}) do history(aid) end
    if #lines==start then lines[#lines+1]='尚无触发记录' end
    return lines
end
local snapshot, selected, page, sequence, capturedRound = {}, 1, 1, 0, 1
local section = 'attributes'
local function capture()
    local result = {}
    local function team(group, side)
        local actors = {}
        for _, actor in pairs(group.pokemon or {}) do actors[#actors+1] = actor end
        table.sort(actors, function(a,b)
            if a.position == b.position then return tostring(a.id)<tostring(b.id) end
            return (a.position or 0)<(b.position or 0)
        end)
        for _, actor in ipairs(actors) do
            local config = data_mgr.get_one('jingling_proto', actor.sprite)
            local lines = {side .. ' · ' .. tostring(config and config.name or actor.sprite)
                .. '  阵位' .. tostring(actor.position) .. '  Lv.' .. tostring(actor.level)
                .. ((actor.hp or 0)>0 and ' 存活' or ' 已倒下'),
                '生命 ' .. number(actor.hp) .. '  护盾 ' .. number(actor.hp_shield or 0),
                '技能点 ' .. number(actor.skill_point_bar or 0) .. '  怒气 ' .. number(actor.wrath or 0),
                '属性：基础值 → 当前生效值（引擎原值）'}
            local seen = {}
            local function stat(key)
                if seen[key] then return end
                seen[key] = true
                local row = actor.att and actor.att[key]
                lines[#lines+1] = title(key) .. '：' .. number(row and row.base or 0)
                    .. ' → ' .. number(stats.get(actor, key))
            end
            for _, key in ipairs(core) do stat(key) end
            for _, id in ipairs((function()
                local ids = {}; for id in pairs(names) do ids[#ids+1]=id end
                table.sort(ids); return ids
            end)()) do
                local key = names[id]
                if actor.att and actor.att[key] then stat(key) end
            end
            local buffStart = #lines+1
            lines[#lines+1] = 'Buff / Debuff（采集时状态）'
            local effects = {}
            for kind, list in pairs(actor.buffs or {}) do
                for _, effect in pairs(list) do
                    local fields = {}
                    for k,v in pairs(effect) do
                        if type(v)=='number' or type(v)=='string' or type(v)=='boolean' then
                            fields[#fields+1] = title(k)..'='..title(type(v)=='number' and k=='prop' and (names[v] or v) or v)
                        end
                    end
                    table.sort(fields)
                    effects[#effects+1] = title(effect.name or (buffs[kind] and buffs[kind].name) or kind)..' ['..tostring(kind)..']'
                    for _, field in ipairs(fields) do effects[#effects+1]='  '..field end
                end
            end
            if #effects==0 then lines[#lines+1]='无' end
            for _, line in ipairs(effects) do lines[#lines+1]=line end
            lines[#lines+1]='其他状态（true=生效 / false=未生效）'
            local flags = {}
            for k,v in pairs(actor) do
                if type(v)=='boolean' then flags[#flags+1]=title(k)..'='..tostring(v) end
            end
            table.sort(flags)
            for _, line in ipairs(flags) do lines[#lines+1]=line end
            -- 只缓存字符串，后续 Buff 删除或属性变化不会改写快照。
            local entry = {attributes={}, status={lines[1],lines[2],lines[3]},
                talents=talentLines(group,actor,lines[1]), assists=assistLines(group,actor,lines[1])}
            for i,line in ipairs(lines) do
                local list = i<buffStart and entry.attributes or entry.status
                list[#list+1] = line
            end
            result[#result+1] = entry
        end
    end
    -- 与原控制器一致：left 对应页面下方，right 对应上方，兼容交换阵营。
    team(stage.left, '我方'); team(stage.right, '敌方')
    snapshot = result
    capturedRound = stage.round or 1
    sequence = sequence + 1
    selected = math.max(1, math.min(selected, #snapshot))
    page = 1
end
capture()

-- 按钮和字体沿用原战斗控件，不加载额外资源。
local template = view:findobj('Top/Quit')
local root = template.transform.parent.parent.gameObject
-- 原按钮的语言组件不修改；仅停用副本里的组件。
local function clone(parent, name, caption, x, y, w, h)
    local go = utils.inst(parent, template)
    go.name = name
    -- 初始化完成前保持隐藏，避免失败时留下未绑定的可见按钮。
    go:SetActive(false)
    local rect = go:GetComponent('RectTransform')
    rect.anchorMin = Vector2.New(0.5, 0.5); rect.anchorMax = rect.anchorMin
    rect.pivot = Vector2.New(0.5, 0.5); rect.localScale = Vector3.one
    rect.sizeDelta = Vector2.New(w, h); rect.anchoredPosition = Vector2.New(x, y)
    local language = utils.findcom(go, 'Text', 'UILanguageSelector')
    if language then language.enabled = false end
    local label = utils.findtext(go, 'Text')
    label.text = caption; label.fontSize = 23; label.raycastTarget = false
    -- 原游戏通过 LuaListener.onClick 分派点击；不调用未导出的 UnityEvent 方法。
    return go, label
end
if view.localAttributeButton then UnityEngine.Object.Destroy(view.localAttributeButton) end
if view.localAttributePanel then UnityEngine.Object.Destroy(view.localAttributePanel) end
local entry = clone(root, 'LocalBattleAttributes', '战斗属性', 0, 0, 148, 50)
local entryRect = entry:GetComponent('RectTransform')
entryRect.anchorMin = Vector2.New(0,1); entryRect.anchorMax = entryRect.anchorMin
entryRect.anchoredPosition = Vector2.New(104,-250)
view.localAttributeButton = entry
local panel, panelLabel = clone(root, 'LocalBattleAttributePanel', '', 0, 0, 610, 910)
view.localAttributePanel = panel
panelLabel.gameObject:SetActive(false)
-- 原技能说明文字已为左上对齐，复用它，不引用本包未导出的 TextAnchor 枚举。
local bodyObject = utils.inst(panel, view:findobj('Bottom/Skill/Desc/Text'))
bodyObject.name = 'SnapshotText'
bodyObject:SetActive(true)
local body = bodyObject:GetComponent('Text')
local image = panel:GetComponent('Image')
if image then image.sprite = nil; image.color = Color.New(0.04,0.08,0.13,0.98) end
utils.add_click_event(panel, function() end)
local textRect = bodyObject:GetComponent('RectTransform')
textRect.anchorMin = Vector2.New(0.5,0.5); textRect.anchorMax = textRect.anchorMin
textRect.pivot = Vector2.New(0.5,0.5)
textRect.localScale = Vector3.one
textRect.sizeDelta = Vector2.New(566,612); textRect.anchoredPosition = Vector2.New(0,-50)
body.fontSize = 22; body.resizeTextForBestFit = false; body.supportRichText = false
body.raycastTarget = false
body.color = Color.white
local redraw
local function button(name, caption, x, y, click)
    local go = clone(panel, name, caption, x, y, 170, 48)
    utils.add_click_event(go, click)
    go:SetActive(true)
end
redraw = function()
    local lines = snapshot[selected] and snapshot[selected][section] or {'本次战斗没有可查看的精灵'}
    local pages = math.max(1, math.ceil(#lines/16))
    page = math.max(1, math.min(page, pages))
    local text = {'双方属性快照 #'..sequence..' · 第'..tostring(capturedRound)..'轮采集',
        '精灵 '..selected..'/'..#snapshot..'  详情 '..page..'/'..pages,
        '仅入场自动读取；点击刷新后才更新。', ''}
    -- 此处仅渲染缓存；不访问 actor 或调用 stats.get。
    for i=(page-1)*16+1, math.min(page*16,#lines) do text[#text+1]=lines[i] end
    body.text = table.concat(text,'\n')
end
button('PreviousElf','上一只',-190,410,function() selected=math.max(1,selected-1); page=1; redraw() end)
button('Refresh','刷新',0,410,function() capture(); redraw() end)
button('NextElf','下一只',190,410,function() selected=math.min(#snapshot,selected+1); page=1; redraw() end)
button('Attributes','基础 / 当前',-190,350,function() section='attributes'; page=1; redraw() end)
button('Status','Buff / 状态',0,350,function() section='status'; page=1; redraw() end)
button('Assists','助战属性',190,350,function() section='assists'; page=1; redraw() end)
button('Talents','生效特性',0,292,function() section='talents'; page=1; redraw() end)
button('PreviousPage','上一页',-190,-410,function() page=math.max(1,page-1); redraw() end)
button('Close','关闭',0,-410,function() panel:SetActive(false) end)
button('NextPage','下一页',190,-410,function() page=page+1; redraw() end)
utils.add_click_event(entry,function() redraw(); panel.transform:SetAsLastSibling(); panel:SetActive(true) end)
panel:SetActive(false)
view.localAttributeStage = stage
entry:SetActive(true)
if not view.localAttributeCleanup then
    local destroy = view.destroy
    view.destroy = function(self, ...)
        self.localAttributeStage = nil
        self.localAttributeButton = nil
        self.localAttributePanel = nil
        if destroy then return destroy(self, ...) end
    end
    view.localAttributeCleanup = true
end
