local quest, task_id = ...

-- 首次高级扭蛋、上阵和升级教学只定位控件，沿用原高亮和进入页面的动作。
if task_id == 10001 or task_id == 10002 or task_id == 10004 then
    return false
end

-- 这里仅使用原 click_navi 的顶层入口。消耗、战斗和领取均由玩家操作。
local routes = {
    [1] = {"Gashapon", "扭蛋机 → 高级扭蛋"},
    [2] = {"Pokemon", "精灵 → 选择精灵并上阵"},
    [3] = {false, "在挂机主页点击下方的闯关按钮"},
    [4] = {"Gashapon", "扭蛋机 → 普通扭蛋"},
    [6] = {"Bag", "背包 → 选择并穿戴装备"},
    [8] = {"Pokemon", "精灵 → 选择精灵 → 进化"},
    [10] = {"Pandect", "冒险 → 日常 → 狩猎。进入普通场需 1 张普通狩猎券"},
    [11] = {"Pokemon", "精灵背包 → 放生。请自行选择精灵"},
    [12] = {"Pokemon", "精灵 → 选择精灵 → 升星"},
    [13] = {"Pandect", "冒险 → 竞技场"},
    [14] = {"Pokemon", "精灵 → 选择精灵 → 个体值洗练"},
    [15] = {"Bag", "背包 → 装备熔炼"},
    [16] = {"Bag", "背包 → 装备打造"},
    [17] = {"Pandect", "冒险 → 竞技场"},
    [18] = {"Pokemon", "精灵 → 选择精灵 → 性格洗练"},
    [19] = {false, "在挂机主页挑战关卡或领取挂机经验"},
    [20] = {"Pandect", "冒险 → 洞穴闯关"},
    [21] = {false, "在挂机主页点击快速战斗"},
    [23] = {false, "在挂机主页进入秘境挑战"},
    [24] = {"Pokemon", "精灵 → 选择精灵 → 升级"},
    [25] = {"Pokemon", "精灵 → 选择精灵 → 提升亲密度"},
    [26] = {"Pokemon", "精灵 → 选择精灵 → 提升努力值"},
    [27] = {false, "手动选择需要的商品或服务后消耗钻石"},
    [28] = {false, "回城 → 好友 → 赠送友情点"},
    [29] = {"Union", "公会 → 捐献"},
    [31] = {"Gashapon", "扭蛋机 → 选择蛋池"},
    [32] = {"Pandect", "冒险 → 挖矿"},
    [34] = {"Bag", "背包 → 选择装备 → 强化"},
    [35] = {false, "回城 → 图鉴"},
    [36] = {"Pokemon", "精灵 → 选择上阵精灵 → 装备携带品"},
    [37] = {"Pandect", "冒险 → 道馆"}
}
local route = routes[quest.finish]
if not route then return false end
local navi = module_mgr.get(modulename.navi)
if route[1] and navi then navi:vbrocast("click_navi", route[1]) end
label_popup.show(route[2])
return true
