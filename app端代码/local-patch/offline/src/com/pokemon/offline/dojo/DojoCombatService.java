package com.pokemon.offline.dojo;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.offline.balance.GameplayRewards;
import com.pokemon.offline.battle.FirstBattleData;
import com.pokemon.offline.battle.BattleMapper;
import com.pokemon.offline.dojo.api.entity.DojoPlacement;
import com.pokemon.offline.elf.ElfCombatGrowth;
import com.pokemon.offline.elf.ElfSkillBar;
import com.pokemon.offline.elf.ElfMapper;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleCosmeticService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 使用底包对手及阵位执行本地道馆战斗和通关结算。
 *
 * @author tongruiyuan
 * @since 2026-11-18 23:06
 */
@Service
public class DojoCombatService {
    private static final int MAX_PRACTICES = 3;
    private static final int MAX_FAILURES = 1;
    private static final int ATTEMPT_TTL = 1800;
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int INVALID = 100021;
    private static final int CLOSED = 2002;
    private static final int DAILY_LIMIT = 2003;
    private static final int TEAM_REQUIRED = 253540;
    private static final int NOT_CLEARED = 2008;

    private final DojoMapper mapper;
    private final DojoStationService station;
    private final BattleMapper formations;
    private final RoleService roles;
    private final ElfMapper elves;
    private final DojoConfig config;
    private final GameplayRewards rewards;
    private final InventoryService inventory;
    private final RoleCosmeticService cosmetics;

    /**
     * 创建本地道馆实战服务。
     *
     * @param station 道馆驻防争夺业务
     * @param formations 角色通用阵法培养存档
     * @param mapper 道馆阵容、尝试和进度存档
     * @param roles 认证角色和存档事务锁
     * @param elves 精灵存档
     * @param config 底包道馆静态配置
     * @param rewards 全局扫荡奖档和日次数
     * @param inventory 奖励道具发放服务
     * @param cosmetics 本地外观持有及穿戴属性服务
     */
    DojoCombatService(DojoMapper mapper, RoleService roles, ElfMapper elves, DojoConfig config,
                      GameplayRewards rewards, InventoryService inventory, RoleCosmeticService cosmetics, BattleMapper formations, DojoStationService station) {
        this.station = station;
        this.formations = formations;
        this.mapper = mapper;
        this.roles = roles;
        this.elves = elves;
        this.config = config;
        this.rewards = rewards;
        this.inventory = inventory;
        this.cosmetics = cosmetics;
    }

    /**
     * 根据当前所在道馆、组编号与保存的编队开始挑战或热身。
     *
     * @param roleId 角色标识
     * @param dojo 道馆编号
     * @param monster 怪物组场景实例标识
     * @param practice 是否是热身
     * @return 道馆原战斗应答
     */
    @Transactional
    public Map<String, Object> start(String roleId, long dojo, String monster, boolean practice) {
        LocalRole role = roles.lockRole(roleId);
        DojoMapper.Progress progress = day(mapper.progress(roleId));
        if (station.active(roleId) || dojo != progress.dojo || progress.history >= dojo) {
            return fields("errno", CLOSED);
        }
        JsonNode stage = config.stage(progress.dojo);
        if (stage == null || stage.get(0).asInt() != role.sceneId || role.level < stage.get(1).asInt()) {
            return fields("errno", CLOSED);
        }
        List<DojoPlacement> team = mapper.team(roleId);
        if (team.isEmpty()) {
            return fields("errno", TEAM_REQUIRED);
        }
        JsonNode groups = stage.get(3);
        int index = 0;
        while (index < groups.size() && !config.groupInstanceId((int) dojo, index).equals(monster)) {
            index++;
        }
        if (index == groups.size() || (!practice && (progress.defeated & (1 << index)) != 0)) {
            return fields("errno", INVALID);
        }
        DojoMapper.Attempt attempt = mapper.attempt(roleId);
        if (current(attempt, role) && attempt.dojo == dojo) {
            return attempt.group == index && attempt.practice == practice
                    ? formation(role, team, progress.tactics, attempt, groups.get(index)) : fields("errno", INVALID);
        }
        if ((practice && progress.practices >= MAX_PRACTICES) || (!practice && progress.failures >= MAX_FAILURES)) {
            return fields("errno", DAILY_LIMIT);
        }
        attempt = new DojoMapper.Attempt(UUID.randomUUID().toString().replace("-", ""), (int) dojo, index,
                ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE), practice, 0, Instant.now().getEpochSecond());
        Map<String, Object> formation = formation(role, team, progress.tactics, attempt, groups.get(index));
        if ((int) formation.get("errno") == 0) {
            mapper.saveAttempt(roleId, attempt);
            if (!practice) {
                mapper.recordChallengeDay(roleId, progress.day);
            }
            if (practice) {
                mapper.saveProgress(roleId, new DojoMapper.Progress(progress.history, progress.dojo, progress.tactics,
                        progress.defeated, progress.day, progress.failures, progress.practices + 1));
            }
        }
        return formation;
    }

    /**
     * 查询角色当前是否正在处理道馆战斗，用于分发共用的战斗消息。
     *
     * @param roleId 角色标识
     * @return 是否有有效的本地道馆战斗
     */
    public boolean active(String roleId) {
        LocalRole role = roles.findById(roleId).orElse(null);
        return role != null && (station.active(roleId) || current(mapper.attempt(roleId), role));
    }

    /**
     * 按原战斗引擎的终局结算当前对手，核对本场双方和种子以过滤过期或重复回执。
     *
     * @param roleId 角色编号
     * @param seed 客户端本场战斗种子
     * @param sides 依次为敌方阵容编号和胜方阵容编号
     * @return 正式胜利返回场景怪物编号，其余有效终局返回空串，无效回执返回空值
     */
    @Transactional
    public String finish(String roleId, long seed, List<?> sides) {
        LocalRole role = roles.lockRole(roleId);
        DojoMapper.Attempt attempt = mapper.attempt(roleId);
        if (!current(attempt, role) || attempt.seed != seed || sides.size() != 2) {
            return null;
        }
        String enemy = "dojo-" + attempt.id;
        if (!enemy.equals(sides.get(0)) || !(roleId.equals(sides.get(1)) || enemy.equals(sides.get(1)))) {
            return null;
        }
        DojoMapper.Progress progress = day(mapper.progress(roleId));
        if (progress.dojo != attempt.dojo) {
            return null;
        }
        if (!roleId.equals(sides.get(1))) {
            flee(roleId);
            return "";
        }
        mapper.deleteAttempt(roleId);
        if (attempt.practice) {
            return "";
        }
        JsonNode stage = config.stage(attempt.dojo);
        int groups = progress.defeated | (1 << attempt.group);
        boolean cleared = groups == (1 << stage.get(3).size()) - 1;
        mapper.saveProgress(roleId, new DojoMapper.Progress(cleared ? Math.max(progress.history, attempt.dojo)
                : progress.history, cleared ? stage.get(2).asInt() : attempt.dojo, progress.tactics, cleared ? 0 : groups,
                progress.day, progress.failures, progress.practices));
        return config.groupInstanceId(attempt.dojo, attempt.group);
    }

    /**
     * 结束尚未结算的挑战，过期或场景已改变的残留尝试同样清理；正式挑战只计一次失败。
     *
     * @param roleId 角色标识
     */
    @Transactional
    public void flee(String roleId) {
        roles.lockRole(roleId);
        DojoMapper.Attempt attempt = mapper.attempt(roleId);
        if (attempt == null) {
            return;
        }
        mapper.deleteAttempt(roleId);
        if (!attempt.practice) {
            DojoMapper.Progress progress = day(mapper.progress(roleId));
            mapper.saveProgress(roleId, new DojoMapper.Progress(progress.history, progress.dojo, progress.tactics,
                    progress.defeated, progress.day, progress.failures + 1, progress.practices));
        }
    }

    /**
     * 核对真实通关进度后发放扫荡奖励，并在同一事务内写入当日领取记录。
     *
     * @param roleId 角色标识
     * @param dojo 申请扫荡的道馆编号
     * @return 原协议错误码与本次入账或进入邮箱的结果
     */
    @Transactional
    public SweepResult sweep(String roleId, long dojo) {
        roles.lockRole(roleId);
        DojoMapper.Progress progress = mapper.progress(roleId);
        if (dojo < 1001 || dojo > progress.history || config.stage((int) dojo) == null) {
            return new SweepResult(NOT_CLEARED, Collections.emptyMap(), false);
        }
        Map<Integer, Long> items = rewards.dojoItems((int) dojo);
        long day = LocalDate.now(SERVER_ZONE).toEpochDay();
        int count = mapper.sweepCount(roleId, (int) dojo, day);
        if (items.isEmpty() || count >= rewards.dailyLimitPerDojo()) {
            return new SweepResult(0, Collections.emptyMap(), false);
        }
        Map<Integer, Long> received = inventory.grantItemsOrMail(roleId, "dojo-sweep:" + dojo + ":" + day + ":" + count,
                items);
        mapper.saveSweepCount(roleId, (int) dojo, day, count + 1);
        return new SweepResult(0, received, !items.equals(received));
    }

    /** 道馆扫荡结算结果，用于原客户端的应答和奖励推送。 */
    public static final class SweepResult {
        public final int errno;
        public final Map<Integer, Long> items;
        /** 本次结算是否有溢出奖励进入邮箱。 */
        public final boolean mailed;

        /**
         * 保存本次结算结果。
         *
         * @param errno 原协议错误码
         * @param items 已发放道具及数量
         * @param mailed 是否有溢出奖励
         */
        private SweepResult(int errno, Map<Integer, Long> items, boolean mailed) {
            this.errno = errno;
            this.items = items;
            this.mailed = mailed;
        }
    }

    /**
     * 查询场景里的怪物组是否已经击败。
     *
     * @param roleId 角色标识
     * @param sceneId 道馆场景编号
     * @param monsterId 道馆场景怪物组实例编号
     * @return 是否应从场景快照中移除
     */
    public boolean cleared(String roleId, int sceneId, String monsterId) {
        int dojo = sceneId - 71100;
        JsonNode stage = config.stage(dojo);
        if (stage == null) {
            return false;
        }
        DojoMapper.Progress progress = mapper.progress(roleId);
        if (dojo <= progress.history) {
            return true;
        }
        for (int i = 0; dojo == progress.dojo && i < stage.get(3).size(); i++) {
            if (config.groupInstanceId(dojo, i).equals(monsterId)) {
                return (progress.defeated & (1 << i)) != 0;
            }
        }
        return false;
    }

    /**
     * 构造场景实战阵容，单机训练难度以各目标一格生命结算。
     *
     * @param role 最新角色状态
     * @param team 道馆阵容
     * @param tactics 己方阵法
     * @param attempt 本次挑战
     * @param group 底包原始对手组
     * @return 完整挑战应答
     */
    private Map<String, Object> formation(LocalRole role, List<DojoPlacement> team, int tactics,
                                          DojoMapper.Attempt attempt, JsonNode group) {
        Map<String, LocalElf> owned = new HashMap<>();
        elves.list(role.id).forEach(elf -> owned.put(elf.id, elf));
        List<Map<String, Object>> players = new ArrayList<>();
        RoleCosmeticService.BattleBonuses bonuses = cosmetics.battleBonuses(role.id);
        for (DojoPlacement member : team) {
            LocalElf elf = owned.get(member.elfId);
            FirstBattleData.Starter base = FirstBattleData.starter(elf.configId);
            if (base != null) {
                Map<String, Object> trained = fighter(elf.id, elf.configId, elf.configId, role.id, member.position,
                        elf.level, base.type, base.secondType, base.quality, base.skill, true);
                trained.put("skill_bar", ElfSkillBar.battleSkills(elf, base.skill));
                ElfCombatGrowth.apply(elf, trained);
                bonuses.withBreakthrough(elf).apply(trained);
                players.add(trained);
            }
        }
        if (players.isEmpty()) {
            return fields("errno", TEAM_REQUIRED);
        }
        String enemy = "dojo-" + attempt.id;
        List<Map<String, Object>> opponents = new ArrayList<>();
        JsonNode members = group.get(4);
        for (int i = 0; i < members.size(); i++) {
            if ((attempt.defeated & (1 << i)) == 0) {
                JsonNode npc = members.get(i);
                opponents.add(fighter(foeId(attempt, i), npc.get(0).asInt(), npc.get(2).asInt(), enemy,
                        npc.get(1).asInt(), 1, npc.get(3).asInt(), npc.get(4).asInt(), npc.get(5).asInt(),
                        npc.get(6).asInt(), false));
            }
        }
        return fields("errno", 0, "dojoId", attempt.dojo,
                "monId", config.groupInstanceId(attempt.dojo, attempt.group), "istry", attempt.practice,
                "seed", attempt.seed, "weather", 0,
                "player", fields("identifer", role.id, "sid", formations.formationId(role.id, tactics), "pokemon", players,
                        "power", 300 * players.size(), "pvp", false, "isnpc", false, "preference", 1),
                "monster", fields("identifer", enemy, "sid", group.get(3).asInt(), "pokemon", opponents,
                        "power", 20 * opponents.size(), "pvp", false, "isnpc", true, "preference", 1));
    }

    /**
     * 构造单个原客户端对战单位。
     *
     * @param id 单位独有编号
     * @param sid 原战斗单位配置编号
     * @param sprite 外观配置编号
     * @param parent 所属编队
     * @param position 阵位
     * @param level 精灵等级
     * @param type 第一属性
     * @param secondType 第二属性
     * @param quality 品质
     * @param skill 装备技能
     * @param player 是否是己方
     * @return 原客户端 pokemon_t 编码字段
     */
    private Map<String, Object> fighter(String id, int sid, int sprite, String parent, int position, int level, int type,
                                        int secondType, int quality, int skill, boolean player) {
        int hp = player ? 240 + level * 10 : 1;
        return fields("id", id, "sid", sid, "sprite", sprite, "parent", parent, "position", position, "level", level,
                "type1", type, "type2", secondType, "quality", quality, "power", player ? 300 : 20,
                "hp_max", hp, "hp", hp, "physical_attack", player ? 180 + level : 8,
                "special_attack", player ? 180 + level : 8, "physical_defense", player ? 100 : 1,
                "special_defense", player ? 100 : 1, "speed", player ? 120 : 10, "hit_chance", player ? 10000 : 0,
                "dodge_chance", 0, "crit_chance", 0, "toughness", 0, "skill_bar", Collections.singletonList(skill),
                "trigger", Collections.emptyList(), "sp", player ? 100 : 0, "gender", 1, "element_provide", 1000,
                "add_skill_point_limit", 0);
    }

    /**
     * 登录时清理中断挑战。旧版逐目标记录无法证明胜负，允许重试并保留已通关的组。
     *
     * @param roleId 角色编号
     */
    @Transactional
    public void reconnect(String roleId) {
        roles.lockRole(roleId);
        DojoMapper.Attempt attempt = mapper.attempt(roleId);
        if (attempt != null && attempt.defeated != 0) {
            mapper.deleteAttempt(roleId);
        } else {
            flee(roleId);
        }
    }

    /**
     * 判断战斗是否属于角色当前所处的道馆并仍有效。
     *
     * @param attempt 当前数据库战斗
     * @param role 具有最新场景的角色
     * @return 是否继续接收该战斗的操作
     */
    private boolean current(DojoMapper.Attempt attempt, LocalRole role) {
        return attempt != null && config.stage(attempt.dojo).get(0).asInt() == role.sceneId
                && Instant.now().getEpochSecond() - attempt.started <= ATTEMPT_TTL;
    }

    /**
     * 日期变化时重置次数，其余道馆进度保持。
     *
     * @param progress 已存道馆进度
     * @return 具有当日次数的状态
     */
    private DojoMapper.Progress day(DojoMapper.Progress progress) {
        long today = LocalDate.now(SERVER_ZONE).toEpochDay();
        return progress.day == today ? progress : new DojoMapper.Progress(progress.history, progress.dojo,
                progress.tactics, progress.defeated, today, 0, 0);
    }

    /**
     * 构造挑战内的敌方精灵编号。
     *
     * @param attempt 当前挑战
     * @param position 敌方队伍序号
     * @return 与当前战斗绑定的唯一编号
     */
    private String foeId(DojoMapper.Attempt attempt, int position) {
        return "EL00" + attempt.id.substring(0, 31) + position;
    }

    /**
     * 转换成原协议使用的字段表。
     *
     * @param pairs 交替排列的字段名和值
     * @return 已构造的原协议结构
     */
    private Map<String, Object> fields(Object... pairs) {
        Map<String, Object> fields = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], pairs[i + 1]);
        }
        return fields;
    }
}
