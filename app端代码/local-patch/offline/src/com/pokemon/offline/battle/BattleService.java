package com.pokemon.offline.battle;

import com.pokemon.offline.activitylevel.AdventureProgressService;
import com.pokemon.offline.activitylevel.api.entity.AdventureEquipment;
import com.pokemon.offline.battle.api.entity.BattleAttempt;
import com.pokemon.offline.elf.ElfCombatGrowth;
import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.elf.ElfSkillBar;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.hangup.HangupMap;
import com.pokemon.offline.hangup.HangupService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.payment.CardFundService;
import com.pokemon.offline.payment.GamePaymentService;
import com.pokemon.offline.quest.QuestService;
import com.pokemon.offline.quest.api.entity.QuestTask;
import com.pokemon.offline.role.RoleCosmeticService;
import com.pokemon.offline.role.RoleBattlePowerService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import com.pokemon.offline.scene.SceneService;
import com.pokemon.offline.scene.SceneEncounterState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 新手战斗与首关单人副本 Service
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:46
 */
@Service
public class BattleService implements SceneEncounterState {

    private static final int FIRST_CLEAR_COINS = 500;
    private static final int FIRST_CLEAR_EXP = 50;
    private static final int FIRST_CLEAR_ITEM = 21014;
    private static final int EXPIRE_SECONDS = 20 * 60;

    /** array_proto 中七种阵法共用的逐级经验。 */
    private static final int[] FORM_EXP = {0, 50, 120, 200, 350, 600, 1000, 1600, 2500, 4000, 7000, 11000, 18000, 24000, 35000};

    /** 原 vip_proto 各级每日可购买的副本机会。 */
    private static final int[] DUNGEON_BUY_LIMIT = {2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 6, 8, 8, 8, 8, 10};

    private final CardFundService cards;
    private final GamePaymentService payments;
    private final BattleMapper mapper;
    private final ElfService elfs;
    private final RoleService roles;
    private final QuestService quests;
    private final InventoryService inventory;
    private final AdventureProgressService adventure;
    private final HangupService hangup;
    private final RoleCosmeticService cosmetics;
    private final RoleBattlePowerService power;
    private final AssistBattleService assists;

    /**
     * 初始化单人副本服务。
     *
     * @param mapper 战斗存档与结算 Mapper
     * @param elfs 初始精灵服务
     * @param roles 本地角色服务
     * @param quests 教学和任务服务
     * @param inventory 普通背包服务
     * @param adventure 冒险关卡存档
     * @param hangup 冒险挂机收益服务
     * @param cosmetics 本地外观持有及穿戴属性服务
     * @param cards 超速闯关双卡权益
     * @param payments 角色 VIP 查询
     * @param power 当前主队战力
     * @param assists 已解锁的助战精灵快照
     */
    public BattleService(BattleMapper mapper, ElfService elfs, RoleService roles, QuestService quests,
                         InventoryService inventory, AdventureProgressService adventure, HangupService hangup,
                         RoleCosmeticService cosmetics, RoleBattlePowerService power, GamePaymentService payments, CardFundService cards,
                         AssistBattleService assists) {
        this.assists = assists;
        this.cards = cards;
        this.payments = payments;
        this.mapper = mapper;
        this.elfs = elfs;
        this.roles = roles;
        this.quests = quests;
        this.inventory = inventory;
        this.adventure = adventure;
        this.hangup = hangup;
        this.cosmetics = cosmetics;
        this.power = power;
    }

    /**
     * 查询当前主队的阵法类型。
     * @param roleId 本地角色标识
     * @return 已保存的阵法类型
     */
    public int formation(String roleId) {
        return mapper.teamFormation(roleId, defaultTeam(roleId));
    }

    /** 读取当前默认的普通编队。
     * @param roleId 角色编号
     * @return 默认编队
     */
    public int defaultTeam(String roleId) { return mapper.choice(roleId,"default"); }

    /** 读取指定普通编队的阵法。
     * @param roleId 角色编号
     * @param team 普通编队
     * @return 阵法类型
     */
    public int formation(String roleId,int team) { return mapper.teamFormation(roleId,team); }

    /** 将已解锁且拥有可战精灵的普通编队设为默认。
     * @param roleId 角色编号
     * @param team 普通编队
     * @return 是否成功
     */
    @Transactional
    public boolean selectDefault(String roleId,int team) {
        LocalRole role=roles.lockRole(roleId);
        if(team<1 || team>3 || team>role.stage+1 || isActive(mapper.findAttempt(roleId),Instant.now().getEpochSecond()) || party(roleId,team).isEmpty())return false;
        mapper.saveChoice(roleId,"default",team);
        return true;
    }

    /**
     * 读取七种阵法的登录快照。
     * @param roleId 本地角色标识
     * @return 各阵法的等级和经验
     */
    public List<Map<String, Object>> formationProgress(String roleId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int type = 1; type <= 7; type++) {
            int[] state = mapper.progress(roleId, type);
            rows.add(fields("lv", state[0], "type", type, "exp", state[1]));
        }
        return rows;
    }

    /**
     * 按原阵法书配置升级，消耗与升级回执在同一事务内保存。
     * @param roleId 角色标识
     * @param type 阵法类型
     * @param materials 原请求中的道具及数量
     * @param key 当前连接和请求序号
     * @return 错误码及升级后的等级与经验
     */
    @Transactional
    public Map<String, Object> upgradeFormation(String roleId, int type, Object materials, String key) {
        Map<String, Object> invalid = fields("errno", 100021);
        if (type < 1 || type > 7 || !(materials instanceof List) || ((List<?>) materials).size() != 1) {
            return invalid;
        }
        Object entry = ((List<?>) materials).get(0);
        if (!(entry instanceof Map)) {
            return invalid;
        }
        Object sid = ((Map<?, ?>) entry).get("sid");
        Object num = ((Map<?, ?>) entry).get("num");
        if (!(sid instanceof Number) || ((Number) sid).longValue() != 30000 + type || !(num instanceof Number)) {
            return invalid;
        }
        roles.lockRole(roleId);
        int[] receipt = mapper.upgradeReceipt(roleId, key);
        if (receipt != null) {
            return fields("errno", 0, "newlevel", receipt[0], "newexp", receipt[1]);
        }
        int[] state = mapper.progress(roleId, type);
        int level = state[0];
        long count = ((Number) num).longValue();
        if (isActive(mapper.findAttempt(roleId), Instant.now().getEpochSecond()) || level >= FORM_EXP.length ||
                count < 1 || count > (FORM_EXP[level] - state[1] + 99) / 100 ||
                !inventory.spendMaterials(roleId, 30000 + type, count, 0, 0)) {
            return invalid;
        }
        int exp = state[1] + (int) count * 100;
        while (level < FORM_EXP.length && exp >= FORM_EXP[level]) {
            exp -= FORM_EXP[level++];
        }
        if (level == FORM_EXP.length) {
            exp = 0;
        }
        mapper.saveUpgrade(roleId, type, level, exp, key);
        return fields("errno", 0, "newlevel", level, "newexp", exp);
    }

    /**
     * 为当前主队选择随包阵法。
     * @param roleId 本地角色标识
     * @param number 队伍编号
     * @param type 原阵法类型
     * @return 是否已选择
     */
    @Transactional
    public boolean selectFormation(String roleId, int number, int type) {
        if (number < 1 || number > 3 || type < 1 || type > 7) {
            return false;
        }
        if (number > roles.lockRole(roleId).stage + 1 || isActive(mapper.findAttempt(roleId), Instant.now().getEpochSecond())) {
            return false;
        }
        if (number == 1) mapper.saveFormation(roleId, type);
        else mapper.saveChoice(roleId, "formation" + number, type);
        return true;
    }
    /**
     * 原子持久化初始精灵、阵容和对应的任务变化。
     *
     * @param roleId 角色标识
     * @param configId 初始精灵编号
     * @return 生效精灵与任务变化，非法选择时为空
     */
    @Transactional
    public Selection choose(String roleId, int configId) {
        LocalRole current = roles.lockRole(roleId);
        Optional<LocalElf> existing = elfs.findStarter(roleId);
        if (!isGuideScene(current.sceneId) && !existing.isPresent()) {
            return null;
        }
        Optional<LocalElf> elf = elfs.choose(roleId, configId);
        if (!elf.isPresent()) {
            return null;
        }
        List<QuestTask> changes = new ArrayList<>(quests.recordStarterSelected(roleId));
        changes.addAll(quests.recordPartySize(roleId, party(roleId).size()));
        return new Selection(elf.get(), changes);
    }

    /**
     * 修改已拥有的精灵阵容并同步使用阵容人数的任务。
     *
     * @param roleId 角色标识
     * @param elfId 精灵实例标识
     * @param team 队伍编号
     * @param slot 队伍位置
     * @return 需要同步的任务变化，非法位置返回空引用
     */
    @Transactional
    public List<QuestTask> setSlot(String roleId, String elfId, int team, int slot) {
        roles.lockRole(roleId);
        if (isActive(mapper.findAttempt(roleId), Instant.now().getEpochSecond())
                || !elfs.setSlot(roleId, elfId, team, slot)) {
            return null;
        }
        return quests.recordPartySize(roleId, party(roleId).size());
    }

    /**
     * 在指定普通编队内移动精灵，目标已占用时交换两只精灵的战斗阵位。
     *
     * @param roleId 角色编号
     * @param elfId 上阵精灵实例编号
     * @param team 队伍编号
     * @param position 战斗阵位（一至六）
     * @return 是否已保存指定阵位
     */
    @Transactional
    public boolean move(String roleId, String elfId, int team, int position) {
        roles.lockRole(roleId);
        return !isActive(mapper.findAttempt(roleId), Instant.now().getEpochSecond())
                && elfs.move(roleId, elfId, team, position);
    }

    /**
     * 保存已校验的精灵技能栏。
     *
     * @param roleId 当前角色
     * @param elfId 所属精灵编号
     * @param equip 是否上阵技能
     * @param skill 本次操作的技能编号
     * @param replaced 将替换的技能编号，零为追加
     * @return 原协议错误码及生效的技能列表
     */
    @Transactional
    public Map<String, Object> skills(String roleId, String elfId, boolean equip, int skill, int replaced) {
        roles.lockRole(roleId);
        if (isActive(mapper.findAttempt(roleId), Instant.now().getEpochSecond())) {
            return fields("errno", 2011);
        }
        return elfs.skills(roleId, elfId, equip, skill, replaced);
    }

    /**
     * 为原精灵页面选择一个空位或替换指定上阵精灵。
     *
     * @param roleId 角色标识
     * @param elfId 要上阵的精灵
     * @param replacedId 指定替换的精灵，空值表示自动取空位
     * @param team 原客户端队伍编号
     * @return 实际上阵的位置和任务更新，操作无效时为空
     */
    @Transactional
    public PartyChange arrange(String roleId, String elfId, String replacedId, int team) {
        if (team < 1 || team > 3 || elfId == null) {
            return null;
        }
        roles.lockRole(roleId);
        List<LocalElf> all = elfs.list(roleId);
        int occupied = 0;
        int current = 0;
        int replaced = 0;
        for (LocalElf elf : all) {
            if (elf.team != team || elf.slot <= 0 || elf.slot > 6) {
                continue;
            }
            occupied |= 1 << (elf.slot - 1);
            if (elf.id.equals(elfId)) {
                current = elf.slot;
            }
            if (elf.id.equals(replacedId)) {
                replaced = elf.slot;
            }
        }
        int slot;
        if (replacedId != null && !replacedId.isEmpty()) {
            slot = replaced;
        } else {
            int free = ~occupied & 63;
            slot = current != 0 ? current : free == 0 ? 0 : Integer.numberOfTrailingZeros(free) + 1;
        }
        List<QuestTask> tasks = slot == 0 ? null : setSlot(roleId, elfId, team, slot);
        if (tasks == null) {
            return null;
        }
        LocalElf saved = elfs.list(roleId).stream().filter(elf -> elf.id.equals(elfId)).findFirst().get();
        return new PartyChange(saved.position(), tasks);
    }

    /**
     * 普通队伍精灵上阵变更。
     *
     * @author tongruiyuan
     * @since 2026-11-18 23:13
     */
    public static final class PartyChange {
        /** 实际被分配的阵法位置。 */
        public final int slot;
        /** 本次上阵改变的任务。 */
        public final List<QuestTask> tasks;

        /**
         * 生成已提交的上阵变更。
         *
         * @param slot 实际上阵位置
         * @param tasks 任务变更
         */
        PartyChange(int slot, List<QuestTask> tasks) {
            this.slot = slot;
            this.tasks = tasks;
        }
    }

    /**
     * 读取该角色通过的首副本怪物组。
     *
     * @param roleId 角色标识
     * @return 已通过的原怪物组编号
     */
    @Override
    public Set<Integer> clearedEncounters(String roleId) {
        Set<Integer> cleared = mapper.cleared(roleId);
        cleared.retainAll(FirstBattleData.ENCOUNTERS);
        return cleared;
    }

    /**
     * 检查原版首场教学战进度，用于中断后恢复引导。
     *
     * @param roleId 角色标识
     * @return 教学战是否已完成
     */
    public boolean hasWonTutorial(String roleId) {
        return mapper.cleared(roleId).contains(FirstBattleData.GUIDE);
    }

    /**
     * 查询首副本组的剩余次数和当前进度。
     *
     * @param roleId 角色标识
     * @return 原协议的副本快照
     */
    public Map<String, Object> dungeon(String roleId) {
        int cleared = clearedEncounters(roleId).size();
        return fields("sid", FirstBattleData.GROUP, "dnum", remainingDungeonRuns(roleId, Instant.now().getEpochSecond()),
                "extern", fields("idkillmonstern", cleared, "mtotal", FirstBattleData.ENCOUNTERS.size()));
    }

    /**
     * 查询已计入当日首通及扫荡的普通副本剩余次数。
     * @param roleId 角色标识
     * @param now 当前 Unix 秒
     * @return 本地普通副本剩余机会
     */
    public int remainingDungeonRuns(String roleId, long now) {
        long start = Math.floorDiv(now + 28800, 86400) * 86400 - 28800;
        return Math.max(0, 3 + mapper.dungeonPurchases(roleId, start) + mapper.staminaRuns(roleId, start) - completedDungeonRuns(roleId, start));
    }

    /**
     * 读取当日购买次数与可挑战次数，用于原客户端次数面板。
     * @param roleId 角色标识
     * @return 当前的购买数与剩余挑战数
     */
    public Map<String,Object> dungeonDegree(String roleId) {
        long day = Math.floorDiv(Instant.now().getEpochSecond() + 28800, 86400) * 86400 - 28800;
        int bought = mapper.dungeonPurchases(roleId, day);
        return fields("buy_num", bought, "num", remainingDungeonRuns(roleId, day));
    }

    /**
     * 按原普通副本面板显示的递增钻石价购买一次挑战机会。
     * @param roleId 角色标识
     * @param key 连接和请求号
     * @return 协议错误码、当日已购数和剩余次数
     */
    @Transactional
    public Map<String,Object> buyDungeonRun(String roleId, String key) {
        roles.lockRole(roleId);
        long day = Math.floorDiv(Instant.now().getEpochSecond() + 28800, 86400) * 86400 - 28800;
        int bought = mapper.dungeonPurchases(roleId, day);
        if (!mapper.boughtDungeonRun(roleId, key)) {
            if (bought >= DUNGEON_BUY_LIMIT[payments.vip(roleId).level]) {
                return fields("errno", 7);
            }
            int price = 500 * (bought + 1);
            if (!inventory.spendMaterials(roleId, Collections.emptyMap(), 0, price)) {
                return fields("errno", 100011);
            }
            mapper.recordDungeonPurchase(roleId, day, key, price);
            bought++;
        }
        return fields("errno", 0, "num", remainingDungeonRuns(roleId, day), "buy_num", bought);
    }

    /**
     * 统计同一北京时间自然日已结算的普通副本次数。
     * @param roleId 角色编号
     * @param start 日期起点 Unix 秒
     * @return 首通和扫荡次数之和
     */
    public int completedDungeonRuns(String roleId, long start) {
        return mapper.dungeonUses(roleId, start);
    }

    /**
     * 领取首组普通副本的一次性通关宝箱。
     * @param roleId 角色标识
     * @param uid 本地通关宝箱标识
     * @return 错误码及本次发放的道具和交付途径
     */
    @Transactional
    public Map<String, Object> claimDungeonChest(String roleId, String uid) {
        roles.lockRole(roleId);
        if (!"dungeon-clear-100001".equals(uid) || !clearedEncounters(roleId).containsAll(FirstBattleData.ENCOUNTERS)) {
            return fields("errno", 3);
        }
        String receipt = "dungeon:first-box:" + FirstBattleData.GROUP;
        if (inventory.rewardGranted(roleId, receipt)) {
            return fields("errno", 7);
        }
        int item = 21082;
        Map<Integer, Long> delivered = inventory.grantItemsOrMail(roleId, receipt, Collections.singletonMap(item, 1L));
        return fields("errno", 0, "item", item, "delivered", delivered.containsKey(item));
    }

    /**
     * 扫荡已经完整通关的首组普通副本，一次消费一个当日机会。
     * @param roleId 角色标识
     * @param group 原普通副本组
     * @param mode 客户端请求模式，普通页面省略
     * @param key 网关连接和原请求编号
     * @return 错误码、剩余次数及直接入包的道具，溢出时道具进邮件
     */
    @Transactional
    public Map<String,Object> sweep(String roleId, int group, int mode, String key) {
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        if (group != FirstBattleData.GROUP || mode != 0 || !clearedEncounters(roleId).containsAll(FirstBattleData.ENCOUNTERS)
                || isActive(mapper.findAttempt(roleId), now) || power.score(roleId) < 9900) {
            return fields("errno", 3);
        }
        int remaining = remainingDungeonRuns(roleId, now);
        if (mapper.swept(roleId, key)) {
            return fields("errno", 0, "dnum", remaining);
        }
        if (remaining == 0) {
            return fields("errno", 7);
        }
        int item = ThreadLocalRandom.current().nextBoolean() ? 10001 : 10002;
        mapper.recordSweep(roleId, key, now, item);
        Map<Integer,Long> delivered = inventory.grantItemsOrMail(roleId, "dungeon-sweep:" + key,
                Collections.singletonMap(item, 1L));
        return fields("errno", 0, "dnum", remaining - 1, "item", item, "delivered", delivered.containsKey(item));
    }

    /**
     * 基于角色存档、上阵精灵和所属场景发起一场本地战斗。
     *
     * @param roleId 角色标识
     * @param type 客户端战斗类型
     * @param uid 地图上的怪物组实例编号
     * @param team 当前请求的阵容编号
     * @return 供原客户端播放的随机种子和双方阵容
     */
    @Transactional
    public Map<String, Object> start(String roleId, int type, String uid, int team) {
        LocalRole current = roles.lockRole(roleId);
        int selected=current.sceneId==FirstBattleData.SCENE ? mapper.choice(roleId,"dungeon") : defaultTeam(roleId);
        List<LocalElf> party = party(roleId,selected);
        if (party.isEmpty() || selected < 1 || selected > 3 || selected > current.stage + 1
                || team < 0 || (team > 0 && team != selected)) {
            return fields("errno", 1);
        }
        int encounter = resolveEncounter(current, type, uid);
        Set<Integer> cleared = mapper.cleared(roleId);
        if (encounter == 0 || cleared.contains(encounter)
                || encounter == FirstBattleData.BOSS && !cleared.containsAll(FirstBattleData.ENCOUNTERS.subList(0,
                FirstBattleData.ENCOUNTERS.size() - 1))) {
            return fields("errno", 1);
        }
        return startAttempt(current, party, encounter);
    }

    /** 查询首副本所选队伍当前成员的实际战斗生命。训练敌方只有被击败判定，不向玩家施加伤害。
     * @param roleId 角色编号
     * @return 原副本 teamhp
     */
    @Transactional
    public Map<String,Object> dungeonHealth(String roleId) {
        LocalRole role=roles.lockRole(roleId);
        int team=mapper.choice(roleId,"dungeon");
        RoleCosmeticService.BattleBonuses bonuses=cosmetics.battleBonuses(roleId);
        List<Map<String,Object>> detail=new ArrayList<>();
        for(LocalElf elf:party(roleId,team)) {
            int max=((Number)player(roleId,elf,bonuses,0).get("hp_max")).intValue();
            int current=role.sceneId==FirstBattleData.SCENE ? mapper.dungeonLife(roleId,elf.id,max) : max;
            detail.add(fields("uid",elf.id,"crthp",current,"maxhp",max));
        }
        return fields("teamid",team,"detail",detail);
    }

    /** 切换当前首副本的合法队伍并保留重登选择。
     * @param roleId 角色编号
     * @param team 普通编队号
     * @return 原副本 teamhp，非法操作为空
     */
    @Transactional
    public Map<String,Object> selectDungeon(String roleId,int team) {
        LocalRole role=roles.lockRole(roleId);
        if(role.sceneId!=FirstBattleData.SCENE || team<1 || team>3 || team>role.stage+1 || party(roleId,team).isEmpty()
                || isActive(mapper.findAttempt(roleId),Instant.now().getEpochSecond()))return null;
        mapper.saveChoice(roleId,"dungeon",team);
        return dungeonHealth(roleId);
    }

    /**
     * 不限每日次数，按原钻石价格连续结算最多一百个已配置关卡。
     * @param roleId 角色编号
     * @param key 当前连接的请求编号
     * @return 错误码与逐关实际结算
     */
    @Transactional
    public FastAdventure fastAdventure(String roleId, String key) {
        LocalRole role = roles.lockRole(roleId);
        if (hangup.usedFast(roleId, key)) {
            return new FastAdventure(100011, Collections.emptyList());
        }
        if (!isAdventureScene(role.sceneId)) {
            return new FastAdventure(100003, Collections.emptyList());
        }
        if (party(roleId).isEmpty()) {
            return new FastAdventure(100067, Collections.emptyList());
        }
        if (isActive(mapper.findAttempt(roleId), Instant.now().getEpochSecond())) {
            return new FastAdventure(3004, Collections.emptyList());
        }
        int first = adventure.nextMission(roleId);
        List<AdventureStage> stages = new ArrayList<>();
        int mission = first;
        int slots = 0;
        // variable[3503].data2 是原超速界面显示的关数，显示关数等于协议编号减 10000。
        while (stages.size() < 100 && mission <= 30000) {
            AdventureStage stage = AdventureStage.find(mission);
            if (stage == null || role.stage < stage.requiredWorld || stage.next <= mission || stage.next > 30001) {
                break;
            }
            for (int i = 0; i < stage.rewards.size(); i += 3) {
                if (stage.rewards.get(i) == AdventureStage.EQUIPMENT) {
                    slots += stage.rewards.get(i + 2);
                }
            }
            stages.add(stage);
            mission = stage.next;
        }
        if (stages.isEmpty()) {
            return new FastAdventure(AdventureStage.find(first) == null ? 4006 : 100079, Collections.emptyList());
        }
        if (slots > 0 && slots > inventory.ordinaryCapacity(roleId) - inventory.ordinaryUsed(roleId)) {
            return new FastAdventure(100049, Collections.emptyList());
        }
        if (role.diamonds < 1000) {
            return new FastAdventure(100006, Collections.emptyList());
        }
        if (!hangup.recordFast(roleId, key)) {
            return new FastAdventure(100011, Collections.emptyList());
        }
        if (!inventory.spendMaterials(roleId, Collections.emptyMap(), 0, 1000)) {
            throw new IllegalStateException("角色锁内闯关钻石扣费失败");
        }
        // 先占用已校验的装备格，普通道具不足以入包时按单关原回执转入邮件。
        for (AdventureStage stage : stages) {
            grantStageEquipment(roleId, stage);
        }
        List<Victory> cleared = new ArrayList<>();
        mission = first;
        for (AdventureStage stage : stages) {
            cleared.add(clearAdventure(roleId, mission, stage));
            mission = stage.next;
        }
        return new FastAdventure(0, cleared);
    }

    /**
     * 创建原关卡奖励中的独立装备。
     * @param roleId 已加锁的角色
     * @param stage 已验证的原配置
     * @return 本关已发放的装备实例
     */
    private List<AdventureEquipment> grantStageEquipment(String roleId, AdventureStage stage) {
        List<AdventureEquipment> equipment = new ArrayList<>();
        for (int i = 0; i < stage.rewards.size(); i += 3) {
            if (stage.rewards.get(i) == AdventureStage.EQUIPMENT) {
                int id = stage.rewards.get(i + 1);
                for (int count = 0; count < stage.rewards.get(i + 2); count++) {
                    equipment.add(adventure.grantEquipment(roleId, id, AdventureStage.equipmentPosition(id)));
                }
            }
        }
        return equipment;
    }

    /**
     * 完成单关首通，冻结挂机收益并按同一关卡回执发放普通道具与任务进度。
     * @param roleId 已加锁的角色
     * @param mission 当前应结算的原关卡
     * @param stage 对应配置
     * @return 本关实际入包与任务状态
     */
    private Victory clearAdventure(String roleId, int mission, AdventureStage stage) {
        hangup.freezeRate(roleId, mission);
        if (!adventure.recordClear(roleId, mission, stage.next)) {
            throw new IllegalStateException("角色锁内冒险关卡进度不一致");
        }
        Map<Integer, Long> items = new LinkedHashMap<>();
        for (int i = 0; i < stage.rewards.size(); i += 3) {
            if (stage.rewards.get(i) == AdventureStage.ITEM) {
                items.merge(stage.rewards.get(i + 1), stage.rewards.get(i + 2).longValue(), Long::sum);
            }
        }
        Map<Integer, Long> delivered = inventory.grantItemsOrMail(roleId, "adventure:clear:" + mission, items);
        // 本地首胜基础奖励按该关一分钟挂机产出结算，与挂机时间账本独立。
        int coins = Math.multiplyExact(stage.coins, 60);
        int experience = Math.multiplyExact(stage.roleExp, 60);
        roles.addCurrency(roleId, coins, 0, experience);
        hangup.addElfPool(roleId, Math.multiplyExact((long) stage.elfExp, 60));
        return new Victory(mission, false, coins, experience, 0, quests.recordBattleClear(roleId, mission),
                delivered, !delivered.equals(items));
    }

    /**
     * 超速闯关的逐关结果，用于生成原客户端的累计奖励。
     *
     * @author tongruiyuan
     * @since 2026-11-20 09:29
     */
    public static final class FastAdventure {
        public final int error;
        public final List<Victory> cleared;

        /**
         * 保存本批结算。
         * @param error 错误码
         * @param cleared 逐关奖励与任务变化
         */
        FastAdventure(int error, List<Victory> cleared) {
            this.error = error;
            this.cleared = cleared;
        }
    }

    /**
     * 发起角色接下来需要挑战的原版冒险关卡。
     *
     * @param roleId 角色编号
     * @return 原协议所用的种子和双方阵容
     */
    @Transactional
    public Map<String, Object> startAdventure(String roleId) {
        LocalRole role = roles.lockRole(roleId);
        int mission = adventure.nextMission(roleId);
        List<LocalElf> team = party(roleId);
        AdventureStage stage = AdventureStage.find(mission);
        if (!isAdventureScene(role.sceneId) || team.isEmpty() || stage == null || role.stage < stage.requiredWorld) {
            return fields("errno", 1);
        }
        return startAttempt(role, team, mission);
    }

    /**
     * 在角色事务内取得或创建同一关卡的进行中战斗。
     *
     * @param current 已锁定的角色
     * @param party 上阵精灵
     * @param encounter 关卡或怪物组编号
     * @return 双方战斗信息，其他战斗未完时返回错误
     */
    private Map<String, Object> startAttempt(LocalRole current, List<LocalElf> party, int encounter) {
        long now = Instant.now().getEpochSecond();
        BattleAttempt attempt = mapper.findAttempt(current.id);
        if (isActive(attempt, now) && attempt.encounter != encounter) {
            return fields("errno", 1);
        }
        if (!isActive(attempt, now)) {
            attempt = new BattleAttempt(UUID.randomUUID().toString(), encounter,
                    ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE), 0, now, "ACTIVE");
            mapper.start(current.id, attempt);
        }
        return formation(current, party, attempt);
    }

    /**
     * 结算本地冒险引擎已判定的胜利，其他副本按合法技能目标推进。
     *
     * @param roleId 角色标识
     * @param skill 操作中的技能编号；冒险胜利传零
     * @param targets 普通攻击的目标实例，或冒险胜利的本场敌队编号
     * @param flag 客户端输入模式，2 表示本地冒险胜利
     * @return 本场首次结算结果，其他情况为空
     */
    @Transactional
    public Victory synchronize(String roleId, int skill, List<?> targets, int flag) {
        boolean adventureWin = flag == 2 && skill == 0;
        if ((!adventureWin && (skill <= 0 || flag < -1 || flag > 1)) || targets == null || targets.isEmpty()) {
            return null;
        }
        LocalRole role = roles.lockRole(roleId);
        BattleAttempt attempt = mapper.findAttempt(roleId);
        long now = Instant.now().getEpochSecond();
        if (!isActive(attempt, now)) {
            return null;
        }
        AdventureStage stage = AdventureStage.find(attempt.encounter);
        if (stage != null) {
            // 本地原战斗引擎判胜后才结算；选择攻击目标不等于击败目标。
            if (!adventureWin || targets.size() != 1 || !("local-" + attempt.id).equals(targets.get(0))
                    || !isAdventureScene(role.sceneId) || adventure.nextMission(roleId) != attempt.encounter
                    || role.stage < stage.requiredWorld) {
                return null;
            }
            attempt.status = "WON";
            mapper.update(roleId, attempt);
            List<AdventureEquipment> equipment = grantStageEquipment(roleId, stage);
            Victory victory = clearAdventure(roleId, attempt.encounter, stage);
            victory.equipment.addAll(equipment);
            return victory;
        }
        if (adventureWin) {
            return null;
        }
        boolean validScene = attempt.encounter == FirstBattleData.GUIDE ? isGuideScene(role.sceneId)
                : role.sceneId == FirstBattleData.SCENE;
        if (!validScene) {
            return null;
        }
        int targetLimit = 0;
        for (LocalElf elf : party(roleId,role.sceneId==FirstBattleData.SCENE ? mapper.choice(roleId,"dungeon") : defaultTeam(roleId))) {
            FirstBattleData.Starter config = FirstBattleData.starter(elf.configId);
            targetLimit = Math.max(targetLimit, ElfSkillBar.targetLimit(elf, config.skill, config.targetLimit, skill));
            if (skill == com.pokemon.offline.elf.DynamaxRules.battleSkill(elf, attempt.seed)) {
                targetLimit = Math.max(targetLimit, ElfSkillBar.dynamaxTargetLimit(skill));
            }
        }
        if (targets.size() > targetLimit) {
            return null;
        }
        FirstBattleData.Encounter plan = FirstBattleData.encounter(attempt.encounter);
        int mask = 0;
        for (Object target : targets) {
            int slot = battleSlot(attempt, plan, target);
            if (slot < 0 || (mask & 1 << slot) != 0 || (attempt.defeatedMask & 1 << slot) != 0) {
                return null;
            }
            mask |= 1 << slot;
        }
        // 训练难度给每个对手一格生命。以上已验证确实为己方已装备的伤害技能和合法目标。
        attempt.defeatedMask |= mask;
        if (attempt.defeatedMask != (1 << plan.ids.length) - 1) {
            mapper.update(roleId, attempt);
            return null;
        }
        attempt.status = "WON";
        mapper.update(roleId, attempt);
        if (mapper.cleared(roleId).contains(attempt.encounter)) {
            return null;
        }
        mapper.addClear(roleId, attempt.encounter, now);
        if (attempt.encounter == FirstBattleData.GUIDE) {
            return new Victory(attempt.encounter, false, 0, 0, 0, quests.recordTutorialBattleWin(roleId), Collections.emptyMap(), false);
        }
        boolean completed = clearedEncounters(roleId).size() == FirstBattleData.ENCOUNTERS.size();
        if (completed) {
            roles.addCurrency(roleId, FIRST_CLEAR_COINS, 0, FIRST_CLEAR_EXP);
            Map<Integer, Long> delivered = inventory.grantItemsOrMail(roleId, "dungeon:clear:" + FirstBattleData.SCENE,
                    Collections.singletonMap(FIRST_CLEAR_ITEM, 1L));
            return new Victory(attempt.encounter, true, FIRST_CLEAR_COINS, FIRST_CLEAR_EXP, FIRST_CLEAR_ITEM,
                    quests.recordBattleClear(roleId, FirstBattleData.SCENE), delivered, delivered.isEmpty());
        }
        return new Victory(attempt.encounter, false, 0, 0, 0, Collections.emptyList(), Collections.emptyMap(), false);
    }

    /**
     * 保存逃跑状态，已经结算的战斗不再被修改。
     *
     * @param roleId 角色标识
     */
    @Transactional
    public void flee(String roleId) {
        roles.lockRole(roleId);
        BattleAttempt attempt = mapper.findAttempt(roleId);
        if (isActive(attempt, Instant.now().getEpochSecond())) {
            attempt.status = "FLED";
            mapper.update(roleId, attempt);
        }
    }

    /**
     * 查询可参与当前本地副本的上阵精灵。
     *
     * @param roleId 所属角色标识
     * @return 已上阵且具有战斗参数的精灵
     */
    private List<LocalElf> party(String roleId) { return party(roleId,defaultTeam(roleId)); }

    /** 读取一支队伍内可使用原战斗配置的真实精灵。
     * @param roleId 角色编号
     * @param team 普通编队
     * @return 依队内顺序排列的精灵
     */
    private List<LocalElf> party(String roleId, int team) {
        List<LocalElf> party = new ArrayList<>();
        for (LocalElf elf : elfs.list(roleId)) {
            if (elf.team == team && elf.slot > 0 && FirstBattleData.starter(elf.configId) != null) {
                party.add(elf);
            }
        }
        party.sort(Comparator.comparingInt(elf -> elf.slot));
        return party;
    }

    /**
     * 解出所处场景允许进入的遭遇。
     *
     * @param role 最新角色状态
     * @param type 战斗类型
     * @param uid 场景对象标识
     * @return 原怪物组或教学编号，失败返回零
     */
    private int resolveEncounter(LocalRole role, int type, String uid) {
        if (type == FirstBattleData.GUIDE && isGuideScene(role.sceneId) && (uid == null || uid.isEmpty())) {
            return FirstBattleData.GUIDE;
        }
        if (type == 0 && role.sceneId == FirstBattleData.SCENE) {
            for (int encounter : FirstBattleData.ENCOUNTERS) {
                if (FirstBattleData.monsterUid(encounter).equals(uid)) {
                    return encounter;
                }
            }
        }
        return 0;
    }

    /**
     * 识别原版主城引导场景和旧存档的研究所引导场景。
     *
     * @param sceneId 角色所在场景编号
     * @return 是否允许完成初始精灵及教学战
     */
    private boolean isGuideScene(int sceneId) {
        return sceneId == SceneService.CITY || sceneId == FirstBattleData.GUIDE_SCENE;
    }

    /**
     * 判断客户端可在冒险地图或旧版主城入口开启冒险挑战。
     *
     * @param sceneId 角色最新场景
     * @return 是否允许开启或继续冒险战斗
     */
    private boolean isAdventureScene(int sceneId) {
        return isGuideScene(sceneId) || HangupMap.byDoor(sceneId) != null;
    }

    /**
     * 判断战斗是否仍可执行操作。
     *
     * @param attempt 最近一次尝试
     * @param now 当前时间
     * @return 是否为有效的进行中尝试
     */
    private boolean isActive(BattleAttempt attempt, long now) {
        return attempt != null && "ACTIVE".equals(attempt.status) && now - attempt.startedAt <= EXPIRE_SECONDS;
    }

    /**
     * 查找有效的战斗目标槽位。
     *
     * @param attempt 当前尝试
     * @param plan 当前怪物组配置
     * @param target 客户端目标编号
     * @return 正式目标槽位，否则返回负数
     */
    private int battleSlot(BattleAttempt attempt, FirstBattleData.Encounter plan, Object target) {
        for (int i = 0; i < plan.ids.length; i++) {
            if (enemyId(attempt, i).equals(target)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 构造每次尝试内稳定且与精灵格式兼容的敌人编号。
     *
     * @param attempt 当前尝试
     * @param slot 敌人槽位
     * @return 仅本次尝试有效的精灵实例编号
     */
    private String enemyId(BattleAttempt attempt, int slot) {
        return "EL00" + attempt.id.replace("-", "").substring(0, 30) + "0" + slot;
    }

    /**
     * 将已保存的阵容和服务器种子转换为战斗协议开场数据。
     *
     * @param role 最新角色状态
     * @param party 已上阵精灵
     * @param attempt 当前战斗尝试
     * @return 进入客户端战斗场景所需数据
     */
    private Map<String, Object> formation(LocalRole role, List<LocalElf> party, BattleAttempt attempt) {
        AdventureStage stage = AdventureStage.find(attempt.encounter);
        FirstBattleData.Encounter encounter = stage == null ? FirstBattleData.encounter(attempt.encounter) : stage.encounter;
        String opposition = "local-" + attempt.id;
        List<Map<String, Object>> players = new ArrayList<>();
        RoleCosmeticService.BattleBonuses bonuses = cosmetics.battleBonuses(role.id);
        for (LocalElf elf : party) {
            Map<String, Object> member = player(role.id, elf, bonuses, attempt.seed);
            if (role.sceneId == FirstBattleData.SCENE) {
                int maximum = ((Number) member.get("hp_max")).intValue();
                member.put("hp", mapper.dungeonLife(role.id, elf.id, maximum));
            } else if (stage != null) {
                // 主线开场由原战斗引擎叠加队伍天赋，再按最终生命上限填满。
                member.remove("hp");
            }
            players.add(member);
        }
        List<Map<String, Object>> opponents = new ArrayList<>();
        for (int i = 0; i < encounter.ids.length; i++) {
            if ((attempt.defeatedMask & 1 << i) == 0) {
                int mid = encounter.ids[i];
                FirstBattleData.Monster enemy = stage == null ? FirstBattleData.monster(mid) : AdventureStage.monster(mid);
                opponents.add(fighter(enemyId(attempt, i), mid, enemy.sprite, opposition, encounter.positions[i], 1,
                        enemy.type, enemy.secondType, 2, enemy.skill, 1, 8, 8, 1, 1, 10 - i, false));
            }
        }
        Map<String,Object> playerTeam=fields("identifer", role.id, "power", 300 * players.size(), "pokemon", players,
                "sid", mapper.formationId(role.id, mapper.teamFormation(role.id,party.get(0).team)), "preference", 1, "pvp", false, "isnpc", false);
        Map<String,Object> pet=com.pokemon.offline.home.PetCombatRules.battlePet(party.get(0).battlePet);
        if(!pet.isEmpty())playerTeam.put("pet",pet);
        playerTeam.put("assists", assists.snapshot(role.id, party.stream().map(elf -> elf.id)
                .collect(java.util.stream.Collectors.toList())));
        return fields("errno", 0, "seed", attempt.seed, "weather", 0,
                "player", playerTeam,
                "monster", fields("identifer", opposition, "power", opponents.size() * 20, "pokemon", opponents,
                        "sid", encounter.formation, "preference", 1, "pvp", false, "isnpc", true));
    }

    /** 使用与战斗开场一致的精灵养成、属性和技能计算战斗成员。
     * @param roleId 角色编号
     * @param elf 队中精灵
     * @param bonuses 角色共用加成
     * @param seed 本场战斗种子
     * @return 原战斗成员
     */
    private Map<String,Object> player(String roleId,LocalElf elf,RoleCosmeticService.BattleBonuses bonuses,int seed) {
        FirstBattleData.Starter starter = FirstBattleData.starter(elf.configId);
        Map<String, Object> trained = fighter(elf.id, elf.configId, elf.configId, roleId, elf.position(), elf.level,
                starter.type, starter.secondType, starter.quality, starter.skill, 240,
                130 + starter.physicalAttack, 130 + starter.specialAttack,
                90 + starter.physicalDefense, 90 + starter.specialDefense, 100 + starter.speed, true);
        List<Integer> moves=new ArrayList<>(ElfSkillBar.battleSkills(elf,starter.skill));
        int dynaSkill=com.pokemon.offline.elf.DynamaxRules.battleSkill(elf,seed);
        if (dynaSkill>0) { moves.add(dynaSkill); }
        trained.put("skill_bar",moves);
        ElfCombatGrowth.apply(elf, trained);
        bonuses.withPet(elf).apply(trained);
        return trained;
    }

    /**
     * 生成单个精灵的战斗属性。
     *
     * @param id 精灵实例编号
     * @param sid 客户端配置编号
     * @param sprite 外观精灵编号
     * @param parent 阵营标识
     * @param position 所在阵法位置
     * @param level 精灵等级
     * @param type 第一属性
     * @param secondType 第二属性
     * @param quality 品质
     * @param skill 已装备的伤害技能
     * @param hp 最大生命和初始生命
     * @param patk 物理攻击
     * @param satk 特殊攻击
     * @param pdef 物理防御
     * @param sdef 特殊防御
     * @param speed 行动速度
     * @param player 是否为玩家精灵
     * @return 原协议 pokemon_t 数据
     */
    private Map<String, Object> fighter(String id, int sid, int sprite, String parent, int position, int level, int type,
                                        int secondType, int quality, int skill, int hp, int patk, int satk,
                                        int pdef, int sdef, int speed, boolean player) {
        return fields("id", id, "sid", sid, "level", level, "position", position, "type1", type, "type2", secondType,
                "quality", quality, "power", player ? 300 : 20, "parent", parent, "hp_max", hp, "hp", hp,
                "physical_attack", patk, "special_attack", satk, "physical_defense", pdef, "special_defense", sdef,
                "hit_chance", player ? 10000 : 0, "dodge_chance", 0, "crit_chance", 0, "toughness", 0, "speed", speed,
                "skill_bar", Collections.singletonList(skill), "trigger", Collections.emptyList(),
                "sp", player ? 100 : 0, "sprite", sprite, "gender", 1, "element_provide", 1000,
                "add_skill_point_limit", 0);
    }

    /**
     * 创建战斗协议键值表。
     *
     * @param keyValues 依次给出的字段名和字段值
     * @return 有序协议字段
     */
    private Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            fields.put((String) keyValues[i], keyValues[i + 1]);
        }
        return fields;
    }

    /**
     * 领取精灵后需要通知客户端的结果。
     *
     * @author tongruiyuan
     * @since 2026-11-18 18:46
     */
    public static final class Selection {
        public final LocalElf elf;
        public final List<QuestTask> tasks;

        /**
         * 创建领取结果。
         *
         * @param elf 已保存的精灵
         * @param tasks 变化的任务列表
         */
        Selection(LocalElf elf, List<QuestTask> tasks) {
            this.elf = elf;
            this.tasks = tasks;
        }
    }

    /**
     * 战斗结算后通知客户端的结果。
     *
     * @author tongruiyuan
     * @since 2026-11-18 18:46
     */
    public static final class Victory {
        public final int encounter;
        public final boolean completed;
        public final int coins;
        public final int exp;
        public final int itemId;
        public final List<QuestTask> tasks;
        /** 实际直接放入背包的普通道具。 */
        public final Map<Integer, Long> delivered;
        /** 普通冒险胜利时实际入包的装备实例。 */
        public final List<AdventureEquipment> equipment = new ArrayList<>();
        /** 本次是否生成溢出奖励邮件。 */
        public final boolean mailed;

        /**
         * 创建结算结果。
         *
         * @param encounter 击败的怪组
         * @param completed 是否完成整个首副本
         * @param coins 发放金币
         * @param exp 发放角色经验
         * @param itemId 发放道具配置编号
         * @param tasks 需要推送的任务变化
         * @param delivered 实际入包的普通道具
         * @param mailed 是否生成溢出邮件
         */
        Victory(int encounter, boolean completed, int coins, int exp, int itemId, List<QuestTask> tasks,
                Map<Integer, Long> delivered, boolean mailed) {
            this.encounter = encounter;
            this.completed = completed;
            this.coins = coins;
            this.exp = exp;
            this.itemId = itemId;
            this.tasks = tasks;
            this.delivered = delivered;
            this.mailed = mailed;
        }
    }
}
