package com.pokemon.local.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.local.battle.AdventureStage;
import com.pokemon.local.battle.BattleService;
import com.pokemon.local.battle.FirstBattleData;
import com.pokemon.local.elf.ElfGearService;
import com.pokemon.local.elf.PowerStoneRefinementService;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.ElfSkillBar;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.elf.api.entity.ElfGear;
import com.pokemon.local.hangup.HangupService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.quest.api.entity.QuestTask;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 三选一、编队与首副本的原客户端协议入口。
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:50
 */
@Service
class BattleGameFeature implements GameFeature {

    /** 原 xingge_proto 的直率性格，所有属性修正均为零。 */
    private static final int NEUTRAL_NATURE = 1006;

    private final Map<GameReply, String> connections = Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private final com.pokemon.local.role.RoleBattlePowerService power;
    private final BattleService battles;
    private final ElfService elfs;
    private final InventoryService inventory;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final HangupService hangup;
    private final ElfGearService gears;

    /**
     * 初始化战斗相关游戏入口。
     *
     * @param battles 单人战斗服务
     * @param elfs 初始精灵服务
     * @param inventory 普通背包服务
     * @param roles 本地角色服务
     * @param bags 背包推送
     * @param hangup 冒险挂机和精灵经验池
     * @param power 默认队伍实际战力
     * @param gears 携带品和 P 力石存档
     */
    BattleGameFeature(BattleService battles, ElfService elfs, InventoryService inventory, RoleService roles,
                      BagSnapshot bags, HangupService hangup, ElfGearService gears, com.pokemon.local.role.RoleBattlePowerService power) {
        this.power = power;
        this.battles = battles;
        this.elfs = elfs;
        this.inventory = inventory;
        this.roles = roles;
        this.bags = bags;
        this.hangup = hangup;
        this.gears = gears;
    }

    /**
     * 处理精灵和首关单人副本请求。
     *
     * @param role 已登录角色
     * @param request 客户端请求
     * @param output 发送通道
     * @return 是否已处理该命令
     * @throws IOException 发送应答或推送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        switch (request.type) {
            case 3201:
                choose(role, request, output);
                return true;
            case 1501:
                formation(role, request, output);
                return true;
            case 3012:
            case 3017:
                output.send(request, battles.skills(role.id, (String) request.body.get("onlyid"), request.type == 3012,
                        integer(request, "skillid", 0), integer(request, "rmskillid", 0)));
                return true;
            case 3015:
                boolean selected = battles.selectFormation(role.id, integer(request, "number", 0), integer(request, "type", 0));
                output.send(request, fields("errno", selected ? 0 : 100021));
                return true;
            case 3016:
                if (request.session > 0) {
                    String connection = connections.computeIfAbsent(output, ignored -> java.util.UUID.randomUUID().toString());
                    Map<String, Object> result = battles.upgradeFormation(role.id, integer(request, "type", 0),
                            request.body.get("list"), connection + ":" + request.session);
                    output.send(request, result);
                    if (Integer.valueOf(0).equals(result.get("errno"))) {
                        bags.push(role.id, output);
                    }
                }
                return true;
            case 3018:
                output.send(request, fields("errno", 0, "state", states(elfs.list(role.id))));
                return true;
            case 3023:
                boolean moved = battles.move(role.id, (String) request.body.get("onlyid"),
                        integer(request, "number", 0), integer(request, "newlocation", 0));
                output.send(request, fields("errno", moved ? 0 : 100021));
                if (moved) {
                    pushElfs(role.id, output);
                }
                return true;
            case 1502:
            case 3013:
            case 3014:
                arrange(role, request, output);
                return true;
            case 3025:
                boolean defaultSelected=battles.selectDefault(role.id,integer(request,"teamid",0));
                if(defaultSelected)output.push(3,fields("team",battles.defaultTeam(role.id),"battlepower",power.score(role.id)));
                output.send(request,fields("errno",defaultSelected?0:1));
                if(defaultSelected)pushElfs(role.id,output);
                return true;
            case 5015:
                if (request.session > 0) {
                    String connection = connections.computeIfAbsent(output, ignored -> java.util.UUID.randomUUID().toString());
                    int error = inventory.synthesizeElf(role.id, integer(request, "id", 0), connection + ":" + request.session);
                    if (error == 0) {
                        bags.push(role.id, output);
                        pushElfs(role.id, output);
                    }
                    output.send(request, fields("errno", error));
                }
                return true;
            case 3551:
                if (request.session > 0) {
                    int species = integer(request, "elfid", 0);
                    boolean read = elfs.readHandbook(role.id, species);
                    output.send(request, fields("errno", read ? 0 : 100021));
                    if (read) {
                        pushHandbook(role.id, output);
                    }
                }
                return true;
            case 5062:
                if (request.session > 0) {
                    String connection = connections.computeIfAbsent(output, ignored -> java.util.UUID.randomUUID().toString());
                    Map<String,Object> purchase = battles.buyDungeonRun(role.id, connection + ":" + request.session);
                    output.send(request, purchase);
                    if (Integer.valueOf(0).equals(purchase.get("errno"))) {
                        LocalRole saved = roles.findById(role.id).orElseThrow(IllegalStateException::new);
                        output.push(3, fields("diamonds", saved.diamonds));
                    }
                }
                return true;
            case 2204:
                if (request.session > 0) {
                    Map<String, Object> chest = battles.claimDungeonChest(role.id, (String) request.body.get("uid"));
                    output.send(request, fields("errno", chest.get("errno")));
                    if (chest.containsKey("item")) {
                        pushDungeonItem(role.id, chest, "dungeon.clear_box", output);
                    }
                }
                return true;
            case 2202:
            case 2206:
                if (request.session > 0) {
                    sweep(role, request, output);
                }
                return true;
            case 2207:
                dungeonInfo(role, request, output);
                return true;
            case 2211:
                if(roles.findById(role.id).orElse(role).sceneId!=FirstBattleData.SCENE)return false;
                Map<String,Object> health=battles.selectDungeon(role.id,integer(request,"tid",0));
                output.send(request,fields("errno",health==null?1:0));
                if(health!=null)output.push(379,fields("teamhp",health,"flag",0));
                return true;
            case 2203:
                output.send(request, battles.start(role.id, integer(request, "ntype", 0),
                        (String) request.body.get("muid"), integer(request, "elsteamid", 0)));
                return true;
            case 3042:
                if (request.session > 0) {
                    fastAdventure(role, request, output);
                }
                return true;
            case 3037:
                if (request.session > 0) {
                    output.send(request, battles.startAdventure(role.id));
                }
                return true;
            case 3001:
                Object targets = request.body.get("objs");
                List<?> targetList = targets instanceof List ? (List<?>) targets : Collections.emptyList();
                BattleService.Victory victory = battles.synchronize(
                        role.id, integer(request, "sid", 0), targetList, integer(request, "flag", -1));
                if (victory != null) {
                    sendVictory(role, output, victory);
                }
                return true;
            case 3002:
                battles.flee(role.id);
                return true;
            default:
                return false;
        }
    }

    /**
     * 回写超速闯关的原起止关号和累计附件，并同步最新进度与资产。
     * @param role 当前角色
     * @param request 原闯关请求
     * @param output 当前连接
     * @throws IOException 游戏连接发送失败
     */
    private void fastAdventure(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        String connection = connections.computeIfAbsent(output, ignored -> java.util.UUID.randomUUID().toString());
        BattleService.FastAdventure run = battles.fastAdventure(role.id, connection + ":" + request.session);
        if (run.error != 0) {
            output.send(request, fields("errno", run.error));
            return;
        }
        Map<Integer, Long> items = new LinkedHashMap<>();
        Map<Integer, Long> equipment = new LinkedHashMap<>();
        Map<Integer, QuestTask> tasks = new LinkedHashMap<>();
        boolean mailed = false;
        long coins = 0, experience = 0, elfExperience = 0;
        for (BattleService.Victory victory : run.cleared) {
            coins += victory.coins;
            experience += victory.exp;
            elfExperience += (long) AdventureStage.find(victory.encounter).elfExp * 60;
            mailed |= victory.mailed;
            victory.tasks.forEach(task -> tasks.put(task.id, task));
            victory.delivered.forEach((id, count) -> items.merge(id, count, Long::sum));
            List<Integer> rewards = AdventureStage.find(victory.encounter).rewards;
            for (int i = 0; i < rewards.size(); i += 3) {
                if (rewards.get(i) == AdventureStage.EQUIPMENT) {
                    equipment.merge(rewards.get(i + 1), rewards.get(i + 2).longValue(), Long::sum);
                }
            }
        }
        List<Map<String, Object>> adds = new ArrayList<>();
        items.forEach((id, count) -> adds.add(fields("id", id, "n", count)));
        equipment.forEach((id, count) -> adds.add(fields("id", id, "n", count, "type", AdventureStage.EQUIPMENT,
                "pos", AdventureStage.equipmentPosition(id), "level", 0)));
        int next = AdventureStage.find(run.cleared.get(run.cleared.size() - 1).encounter).next;
        output.send(request, fields("errno", 0, "start", run.cleared.get(0).encounter, "final", next,
                "adds", fields("items", adds, "coins", coins, "exp", experience, "exp1", elfExperience)));
        pushTasks(output, new ArrayList<>(tasks.values()));
        bags.push(role.id, output);
        if (mailed) {
            bags.pushMailCount(role.id, output);
        }
        LocalRole saved = roles.findById(role.id).orElseThrow(IllegalStateException::new);
        output.push(3, fields("diamonds", saved.diamonds, "coins", saved.coins, "level", saved.level,
                "exp", saved.exp, "exp1", hangup.elfPool(role.id)));
        HangupGameFeature.pushSnapshot(output, next, hangup, role.id, Collections.emptyList());
    }

    /**
     * 在客户端进入场景时初始化装备，并还原精灵、编队和副本状态。
     *
     * @param role 已登录角色
     * @param output 推送通道
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        pushElfs(role.id, output, gears.debris(role.id));
        pushDungeon(role, output);
    }

    /**
     * 领取初始精灵并发送持久化后的背包和任务进度。
     *
     * @param role 已登录角色
     * @param request 客户端三选一请求
     * @param output 发送通道
     * @throws IOException 写入连接失败
     */
    private void choose(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        BattleService.Selection selection = battles.choose(role.id, integer(request, "elfid", 0));
        if (selection == null) {
            output.send(request, fields("errno", 1));
            return;
        }
        pushElfs(role.id, output);
        pushTasks(output, selection.tasks);
        output.send(request, fields("errno", 0, "elfid", selection.elf.configId));
    }

    /**
     * 处理第一支队伍的上阵位置请求。
     *
     * @param role 已登录角色
     * @param request 客户端编队请求
     * @param output 发送通道
     * @throws IOException 写入连接失败
     */
    private void formation(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        List<QuestTask> tasks = battles.setSlot(role.id, (String) request.body.get("id"),
                integer(request, "queue", 1), integer(request, "index", -1));
        if (tasks == null) {
            output.send(request, fields("errno", 1));
            return;
        }
        pushElfs(role.id, output);
        pushTasks(output, tasks);
        output.send(request, fields("errno", 0));
    }

    /**
     * 处理精灵页面的上阵、替换与下阵操作。
     *
     * @param role 已认证角色
     * @param request 精灵页面上阵或下阵请求
     * @param output 原协议输出
     * @throws IOException 写入请求应答失败
     */
    private void arrange(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        String elfId = (String) request.body.get(request.type == 1502 ? "id" : "onlyid");
        int team = integer(request, "number", 1);
        List<QuestTask> tasks;
        if (request.type == 3013) {
            BattleService.PartyChange change = battles.arrange(role.id, elfId,
                    (String) request.body.get("oldid"), team);
            if (change == null) {
                output.send(request, fields("errno", 100021));
                return;
            }
            output.send(request, fields("errno", 0, "position", change.slot));
            tasks = change.tasks;
        } else {
            tasks = battles.setSlot(role.id, elfId, team, 0);
            output.send(request, fields("errno", tasks == null ? 100021 : 0));
        }
        if (tasks != null) {
            pushElfs(role.id, output);
            pushTasks(output, tasks);
        }
    }

    /**
     * 查询已实现的首个单人副本。
     *
     * @param role 已登录角色
     * @param request 客户端副本查询请求
     * @param output 发送通道
     * @throws IOException 写入连接失败
     */
    private void dungeonInfo(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (integer(request, "sid", 0) != FirstBattleData.GROUP) {
            output.send(request, fields("errno", 1));
            return;
        }
        output.send(request, fields("errno", 0, "dupinfo", battles.dungeon(role.id)));
    }

    /**
     * 读取精灵存档并生成新旧精灵消息与上阵位置。
     *
     * @param roleId 角色标识
     * @param output 推送通道
     * @throws IOException 推送失败
     */
    void pushElfs(String roleId, GameReply output) throws IOException {
        pushElfs(roleId, output, null);
    }

    /**
     * 读取精灵和装备快照，登录时同时初始化碎屑余额。
     *
     * @param roleId 角色编号
     * @param output 推送通道
     * @param debris 登录时的碎屑余额，普通背包更新时为空
     * @throws IOException 发送失败
     */
    private void pushElfs(String roleId, GameReply output, Long debris) throws IOException {
        List<Map<String, Object>> oldElfs = new ArrayList<>();
        List<Map<String, Object>> allElfs = new ArrayList<>();
        List<Map<String,Object>> queues=new ArrayList<>(), formations=new ArrayList<>(), oldTeams=new ArrayList<>();
        Map<Integer,List<Map<String,Object>>> members=new LinkedHashMap<>();
        Map<Integer,List<String>> ids=new LinkedHashMap<>();
        int maxTeam=Math.max(1,Math.min(3,roles.findById(roleId).get().stage+1));
        for(int number=1;number<=maxTeam;number++) {
            members.put(number,new ArrayList<>());
            ids.put(number,new ArrayList<>());
            formations.add(fields("sid",battles.formation(roleId,number),"num",number));
        }
        List<Map<String, Object>> advanced = new ArrayList<>();
        List<LocalElf> owned = elfs.list(roleId);
        List<Map<String, Object>> awaken = new ArrayList<>();
        List<ElfGear> equipment = gears.snapshot(roleId);
        for (LocalElf elf : owned) {
            FirstBattleData.Starter stats = FirstBattleData.starter(elf.configId);
            if (stats == null) {
                continue;
            }
            oldElfs.add(fields("id", elf.id, "config_id", elf.configId, "level", elf.level,
                    "exp", 0, "quality", stats.quality, "star", elf.training.path("star").asInt(1),
                    "nature", elf.training.path("nature").asInt(NEUTRAL_NATURE),
                    "life", stats.life, "speed", stats.speed,
                    "patk", stats.physicalAttack, "pdef", stats.physicalDefense,
                    "satk", stats.specialAttack, "sdef", stats.specialDefense));
            allElfs.add(equippedElfInfo(elf, equipment));
            advanced.add(fields("id", elf.id, "val", elf.training.path("advanced").asInt()));
            if (elf.training.path("awakenlv").asInt() > 0) {
                List<Map<String,Object>> history = new ArrayList<>();
                for (JsonNode step : elf.training.path("awakenHistory")) {
                    List<Integer> spent = new ArrayList<>();
                    step.path("consume_elf_num").forEach(material -> spent.add(material.asInt()));
                    history.add(fields("start_standard", step.path("start_standard").asInt(), "consume_elf_num", spent));
                }
                awaken.add(fields("id", elf.id, "lv", elf.training.path("awakenlv").asInt(), "start", history));
            }
            if (elf.slot != 0 && members.containsKey(elf.team)) {
                ids.get(elf.team).add(elf.id);
                members.get(elf.team).add(fields("id", elf.id, "sid", elf.configId,
                        "position", elf.position(), "prepareid", elf.slot));
            }
        }
        for(int number=1;number<=maxTeam;number++) {
            List<Map<String,Object>> team=members.get(number);
            queues.add(fields("data",team,"battle_num",team.size(),"battle_max",6,"battle_number",number));
            oldTeams.add(fields("queue",number,"elfs",ids.get(number)));
        }
        int elfCapacity = inventory.elfCapacity(roleId);
        output.push(13, fields("list", oldElfs, "size", elfCapacity, "exp", 0,
                "battle_list", oldTeams));
        output.push(1001, fields("elfcount", fields("bagmax", elfCapacity, "bagnum", allElfs.size()),
                "elflist", allElfs, "experience", hangup.elfPool(roleId), "advanced", advanced));
        output.push(1004, fields("state", states(owned)));
        output.push(1006, fields("elf_awaken_data", awaken));
        pushElfGear(equipment, output);
        pushStoneBag(equipment, debris, output);
        output.push(1002, fields("elf_info", queues, "battle_max", 6, "battle_list", battles.defaultTeam(roleId)));
        output.push(1003, fields("positionlist", battles.formationProgress(roleId),
                "setposition", formations));
        pushHandbook(roleId, output);
    }

    /**
     * 生成与全量同步一致的穿卸回执，包含当前精灵的所有力石。
     * @param roleId 角色编号
     * @param elf 已持久化的精灵
     * @return 精灵协议快照
     */
    Map<String, Object> equippedElfInfo(String roleId, LocalElf elf) {
        return equippedElfInfo(elf, gears.snapshot(roleId));
    }

    /**
     * 合并精灵与已经读出的背包装备存档。
     * @param elf 当前精灵
     * @param equipment 当前角色的装备
     * @return 完整精灵协议快照
     */
    private static Map<String, Object> equippedElfInfo(LocalElf elf, List<ElfGear> equipment) {
        Map<String, Object> info = elfInfo(elf);
        List<Map<String, Object>> stones = new ArrayList<>();
        equipment.stream().filter(gear -> gear.kind == ElfGearService.POWERSTONE && elf.id.equals(gear.elfId))
                .forEach(gear -> stones.add(powerStone(gear)));
        info.put("pstone", stones);
        return info;
    }

    /**
     * 将存档中的保护锁与当前主队状态转换为原协议的标记列表。
     *
     * @param owned 角色拥有的精灵
     * @return 原客户端可识别的非空状态集合
     */
    private List<Map<String, Object>> states(List<LocalElf> owned) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalElf elf : owned) {
            List<Integer> flags = new ArrayList<>(2);
            if (elf.training.path("lock").asInt() != 0) {
                flags.add(8);
            }
            if (elf.slot > 0) {
                flags.add(2);
            }
            if (!flags.isEmpty()) {
                result.add(fields("id", elf.id, "state", flags));
            }
        }
        return result;
    }

    /**
     * 推送角色曾取得的精灵模板和每个模板的未读状态。
     *
     * @param roleId 当前角色编号
     * @param output 原图鉴信息推送通道
     * @throws IOException 写入连接失败
     */
    private void pushHandbook(String roleId, GameReply output) throws IOException {
        List<Map<String, Object>> map = new ArrayList<>();
        elfs.handbook(roleId).forEach((id, unread) -> map.add(fields("id", id, "new", unread)));
        output.push(802, fields("map_info", map));
    }

    /**
     * 同步独立的携带品背包、装配清单和 P 力石背包。
     *
     * @param equipment 角色装备实例
     * @param output 原游戏推送出口
     * @throws IOException 写入游戏协议失败
     */
    void pushElfGear(List<ElfGear> equipment, GameReply output) throws IOException {
        List<Map<String, Object>> carries = new ArrayList<>();
        for (ElfGear gear : equipment) {
            if (gear.kind == ElfGearService.CARRY && gear.elfId == null) {
                carries.add(carryInfo(gear));
            }
        }
        output.push(1005, fields("carry_bagmax", ElfGearService.BAG_CAPACITY, "carry_bagnum", carries.size(),
                "carry_bag", carries, "elf_carry_id", equippedCarries(equipment, false),
                "gold_carry_id", equippedCarries(equipment, true),
                "times", Collections.emptyList()));
    }

    /**
     * 按精灵归集已装配的普通或金色携带品，供登录推送及名片共用。
     *
     * @param equipment 角色携带品及力石实例
     * @param gold 是否查询金色携带品
     * @return 含实例、星级及槽位的携带列表
     */
    static List<Map<String, Object>> equippedCarries(List<ElfGear> equipment, boolean gold) {
        Map<String, List<ElfGear>> equipped = new LinkedHashMap<>();
        for (ElfGear gear : equipment) {
            if (gear.kind == ElfGearService.CARRY && gear.elfId != null
                    && com.pokemon.local.elf.GoldCarryRules.isGold(gear.configId) == gold) {
                equipped.computeIfAbsent(gear.elfId, key -> new ArrayList<>()).add(gear);
            }
        }
        List<Map<String, Object>> slots = new ArrayList<>();
        equipped.forEach((id, items) -> slots.add(carryList(id, items)));
        return slots;
    }

    /**
     * 推送力石背包。原客户端将收到的碎屑累加到角色，只有登录初始化才附上该字段。
     *
     * @param equipment 角色当前的装备实例
     * @param debris 初始碎屑，后续背包更新时为空
     * @param output 推送通道
     * @throws IOException 发送失败
     */
    static void pushStoneBag(List<ElfGear> equipment, Long debris, GameReply output) throws IOException {
        List<Map<String, Object>> stones = new ArrayList<>();
        for (ElfGear gear : equipment) {
            if (gear.kind == ElfGearService.POWERSTONE && gear.elfId == null) {
                stones.add(powerStone(gear));
            }
        }
        Map<String, Object> snapshot = fields("bag", stones, "bagspace", stones.size(),
                "baglimit", ElfGearService.BAG_CAPACITY);
        if (debris != null) {
            snapshot.put("debris", debris);
        }
        output.push(808, snapshot);
    }

    /**
     * 转为精灵携带品列表结构。
     *
     * @param elfId 精灵编号
     * @param list 已装配的携带品
     * @return 原协议携带列表
     */
    static Map<String, Object> carryList(String elfId, List<ElfGear> list) {
        List<Map<String, Object>> entries = new ArrayList<>();
        list.forEach(gear -> entries.add(carryInfo(gear)));
        long protectedCount = list.stream().filter(gear -> gear.locked).count();
        return fields("elfid", elfId, "carrylist", entries, "carrynum", entries.size(), "protectnum", protectedCount);
    }

    /**
     * 转换一个携带品实例的原协议结构。
     *
     * @param gear 携带品实例
     * @return 原协议携带品字段
     */
    static Map<String, Object> carryInfo(ElfGear gear) {
        return fields("id", gear.id, "sid", gear.configId, "number", gear.position,
                "protect", gear.locked ? 1 : 0, "star", gear.star);
    }

    /**
     * 转换一个 P 力石实例的原协议结构。
     *
     * @param gear P 力石实例
     * @return 原协议物品字段
     */
    static Map<String, Object> powerStone(ElfGear gear) {
        return fields("id", gear.configId, "sid", gear.id, "pos", gear.position, "n", 1,
                "lock", gear.locked, "extra", PowerStoneRefinementService.properties(gear));
    }

    /**
     * 生成包含已存档培养属性的精灵展示结构。
     *
     * @param elf 精灵存档
     * @return 可直接编码为底包 elfinfo 的字段
     */
    static Map<String, Object> elfInfo(LocalElf elf) {
        JsonNode state = elf.training;
        List<Integer> skills = ElfSkillBar.equipped(elf);
        Map<String, Object> result = fields("id", elf.id, "sid", elf.configId, "level", elf.level,
                "nature", state.path("nature").asInt(NEUTRAL_NATURE), "love", state.path("love").asInt(1),
                "loveexp", state.path("loveexp").asLong(), "position", elf.position(), "prepareid", elf.slot,
                "star", state.path("star").asInt(1), "occupy", elf.slot == 0 ? 0 : 1, "gender", com.pokemon.local.home.BreedingData.gender(elf), "baptize", 0,
                "individual", elfAttributes(state.path("individual")), "effort", elfAttributes(state.path("effort")),
                "effort_monopoly_max", elfAttributes(state.path("effort_monopoly_max")), "skill_bar", skills,
                "old_elfsid", new ArrayList<Integer>(), "individualmax", state.path("individualmax").asInt(),
                "character", Collections.emptyList(), "advanced", state.path("advanced").asInt(),
                "awakenlv", state.path("awakenlv").asInt(), "veinlevel", state.path("veinlevel").asInt(),
                "skill_lv", com.pokemon.local.elf.ElfSkillUpgradeService.levels(elf),
                "skill_cost", com.pokemon.local.elf.ElfSkillUpgradeService.costs(elf));
        for (String field : new String[]{"dynalevel","dynapath","dynabook","dynatype"}) {
            if (state.has(field)) { result.put(field,state.path(field).asInt()); }
        }
        if (elf.stone.has("sid")) {
            Map<String, Object> stone = fields("id", elf.stone.path("id").asInt(), "sid", elf.stone.path("sid").asText(),
                    "n", 1, "level", 0, "lock", elf.stone.path("lock").asBoolean(), "elfequ_starexp", 0,
                    "elfequ_star", elf.stone.path("elfequ_star").asInt(), "lucky", elf.stone.path("lucky").asInt());
            List<Map<String, Object>> attributes = new ArrayList<>();
            elf.stone.path("elfequ_addattr").forEach(attr -> attributes.add(fields("attrid", attr.path("attrid").asInt(),
                    "attrnum", attr.path("attrnum").asInt())));
            stone.put("elfequ_addattr", attributes);
            result.put("Z_Stone", stone);
        }
        if (state.path("pcl").isArray()) {
            List<Map<String,Object>> research = new ArrayList<>();
            for (JsonNode slot : state.path("pcl")) {
                research.add(fields("index",slot.path("index").asInt(),"path",slot.path("path").asInt(),
                        "level",slot.path("level").asInt(),"num",slot.path("num").asInt()));
            }
            result.put("pcl", research);
            if (state.has("pcltype")) {
                result.put("pcltype",state.path("pcltype").asInt());
            }
        }
        List<Integer> oldSpecies = new ArrayList<>();
        state.path("old_elfsid").forEach(value -> oldSpecies.add(value.asInt()));
        result.put("old_elfsid", oldSpecies);
        List<Map<String, Object>> usedEffort = new ArrayList<>();
        state.path("effort_item").fields().forEachRemaining(value ->
                usedEffort.add(fields("id", Integer.parseInt(value.getKey()), "n", value.getValue().asInt())));
        result.put("effort_item", usedEffort);
        if (state.has("ancient")) {
            result.put("ancient", state.path("ancient").asInt());
        }
        return result;
    }

    /**
     * 转换标准的六项精灵属性，旧存档的默认值为零。
     *
     * @param state 已存档六项数值
     * @return 原协议的属性子结构
     */
    private static Map<String, Object> elfAttributes(JsonNode state) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : new String[]{"hp_max", "physical_attack", "special_attack", "physical_defense",
                "special_defense", "speed"}) {
            values.put(field, state.path(field).asInt());
        }
        return values;
    }
    /**
     * 结算一场原界面请求的普通副本扫荡，并同步实际交付的奖励。
     * @param role 角色
     * @param request 原客户端扫荡请求
     * @param output 发送通道
     * @throws IOException 发送失败
     */
    private void sweep(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        String connection = connections.computeIfAbsent(output, ignored -> java.util.UUID.randomUUID().toString());
        Map<String, Object> result = battles.sweep(role.id, integer(request, "sid", 0),
                integer(request, "ntype", 0), connection + ":" + request.session);
        Map<String, Object> response = fields("errno", result.get("errno"));
        if (request.type == 2202 && result.containsKey("dnum")) {
            response.put("dnum", result.get("dnum"));
        }
        output.send(request, response);
        if (request.type == 2206 && result.containsKey("dnum")) {
            output.push(373, fields("dnum", result.get("dnum")));
        }
        if (result.containsKey("item")) {
            pushDungeonItem(role.id, result, "duplicate.passDupAward", output);
            pushDungeon(role, output);
        }
    }

    /**
     * 向客户端同步已结算的普通副本道具或溢出邮件。
     * @param roleId 角色标识
     * @param result 含道具和背包发放结果的结算
     * @param flags 奖励推送来源
     * @param output 发送通道
     * @throws IOException 发送失败
     */
    private void pushDungeonItem(String roleId, Map<String, Object> result, String flags, GameReply output) throws IOException {
        if (Boolean.TRUE.equals(result.get("delivered"))) {
            int item = (Integer) result.get("item");
            Map<String, Object> award = fields("id", item, "n", 1);
            output.push(14, fields("flags", flags, "adds", fields("items", Collections.singletonList(award)), "loses", fields()));
            bags.push(roleId, output);
        } else {
            bags.pushMailCount(roleId, output);
        }
    }

    /**
     * 查询普通副本卡片的当日可用次数。
     * @param roleId 角色编号
     * @param now 当前 Unix 秒
     * @return 剩余挑战次数
     */
    int dungeonRuns(String roleId, long now) {
        return battles.remainingDungeonRuns(roleId, now);
    }

    /**
     * 推送副本内的怪物统计与关卡历史。
     *
     * @param role 已登录角色
     * @param output 推送通道
     * @throws IOException 推送失败
     */
    void pushDungeon(LocalRole role, GameReply output) throws IOException {
        Map<String, Object> dungeon = battles.dungeon(role.id);
        boolean passed = battles.clearedEncounters(role.id).size() == FirstBattleData.ENCOUNTERS.size();
        Map<String, Object> data = fields("history", passed ? Collections.singletonList(dungeon) : Collections.emptyList(),
                "dbags", fields("use", 0, "capacity", 100, "items", Collections.emptyList()),
                "teamhp", battles.dungeonHealth(role.id));
        data.putAll(battles.dungeonDegree(role.id));
        if (roles.findById(role.id).orElse(role).sceneId == FirstBattleData.SCENE) {
            data.put("dupinfo", dungeon);
        }
        output.push(371, data);
    }

    /**
     * 推送已结算战斗的场景、奖励和进度变化。
     *
     * @param role 已登录角色
     * @param output 推送通道
     * @param victory 已提交的结算结果
     * @throws IOException 推送失败
     */
    private void sendVictory(LocalRole role, GameReply output, BattleService.Victory victory) throws IOException {
        pushTasks(output, victory.tasks);
        if (victory.encounter == FirstBattleData.GUIDE) {
            return;
        }
        if (victory.mailed) {
            bags.pushMailCount(role.id, output);
        }
        AdventureStage adventure = AdventureStage.find(victory.encounter);
        if (adventure != null) {
            List<Map<String, Object>> direct = new ArrayList<>();
            for (int i = 0; i < adventure.rewards.size(); i += 3) {
                int type = adventure.rewards.get(i);
                int item = adventure.rewards.get(i + 1);
                int count = adventure.rewards.get(i + 2);
                if (type == AdventureStage.EQUIPMENT) {
                    direct.add(fields("id", item, "n", count, "type", type,
                            "pos", AdventureStage.equipmentPosition(item), "level", 0));
                } else if (victory.delivered.containsKey(item)) {
                    direct.add(fields("id", item, "n", victory.delivered.get(item)));
                }
            }
            HangupGameFeature.pushSnapshot(output, adventure.next, hangup, role.id, direct);
            LocalRole saved = roles.findById(role.id).orElse(role);
            output.push(3, fields("coins", saved.coins, "level", saved.level, "exp", saved.exp,
                    "exp1", hangup.elfPool(role.id)));
            output.push(14, fields("flags", "adventure.first_clear", "adds", fields("coins", victory.coins,
                    "exp", victory.exp, "exp1", (long) adventure.elfExp * 60)));
            bags.push(role.id, output);
            return;
        }
        if (victory.completed) {
            Map<String, Object> adds = fields("coins", victory.coins, "exp", victory.exp,
                    "items", victory.delivered.isEmpty() ? Collections.emptyList()
                            : Collections.singletonList(fields("id", victory.itemId, "n", victory.delivered.get(victory.itemId))));
            output.push(14, fields("flags", "duplicate.passDupAward", "adds", adds, "loses", fields()));
        }
        pushDungeon(role, output);
        output.push(12, fields("sid", FirstBattleData.SCENE,
                "uids", Collections.singletonList(FirstBattleData.monsterUid(victory.encounter))));
    }

    /**
     * 把提交后的任务变更发给客户端。
     *
     * @param output 推送通道
     * @param tasks 有变化的任务
     * @throws IOException 推送失败
     */
    private void pushTasks(GameReply output, List<QuestTask> tasks) throws IOException {
        for (QuestTask task : tasks) {
            output.push(101, fields("id", task.id, "state", task.state, "progress", task.progress));
        }
    }

    /**
     * 读取请求中的整数并校验其 Java 整数范围。
     *
     * @param request 对应客户端请求
     * @param key 协议参数名
     * @param fallback 缺值时使用的默认值
     * @return 合法整数，越界时为最小整数
     */
    private int integer(SprotoProtocol.Request request, String key, int fallback) {
        Number value = (Number) request.body.get(key);
        if (value == null) {
            return fallback;
        }
        long number = value.longValue();
        return number < Integer.MIN_VALUE || number > Integer.MAX_VALUE ? Integer.MIN_VALUE : (int) number;
    }

    /**
     * 构造协议的有序字段。
     *
     * @param keyValues 交替给出的字段名和值
     * @return 供编码器发送的字段表
     */
    private static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put((String) keyValues[i], keyValues[i + 1]);
        }
        return result;
    }
}
