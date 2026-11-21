package com.pokemon.local.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.activitydaily.DailyVitalityEvents;
import com.pokemon.local.battle.BattleMapper;
import com.pokemon.local.battle.FirstBattleData;
import com.pokemon.local.battle.api.entity.BattleAttempt;
import com.pokemon.local.dojo.DojoCombatService;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.guild.GuildEncounterService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleCosmeticService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 竞技场、单人竞技及精灵王的本地 NPC 训练 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:10
 */
@Service
public class ArenaService {
    public static final String ARENA = "arena_training";
    public static final String SOLO = "solo_training";
    public static final String ZODIAC = "zodiac";
    public static final String KING = "elf_king_training";
    private static final int INVALID = 100021;
    private static final int BUSY = 2002;
    private static final int LIMIT = 2003;
    private static final int TEAM = 253540;
    private static final String ATTEMPT = "attempt";
    private static final int ATTEMPT_TTL = 1800;
    private static final int REFRESH_TTL = 300;
    private static final int INITIAL_RANK = 24;
    private static final int DEFAULT_POSITION_TYPE = 1;
    private static final int ATTRIBUTES = 18;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final ArenaMapper mapper;
    private final ArenaCombat combat;
    private final RoleService roles;
    private final ElfService elves;
    private final RoleCosmeticService cosmetics;
    private final InventoryService inventory;
    private final BattleMapper normalBattles;
    private final DojoCombatService dojo;
    private final GuildEncounterService guildEncounters;
    private final DailyVitalityEvents vitality;
    private final ObjectMapper json;

    /**
     * 绑定存档、队伍、奖励与共用本地战斗入口。
     *
     * @param mapper 训练存档
     * @param combat NPC 配置和回合验证
     * @param roles 角色事务锁
     * @param elves 角色精灵
     * @param cosmetics 战斗外观加成
     * @param inventory 本地背包和资产
     * @param normalBattles 普通战斗存档
     * @param dojo 道馆战斗
     * @param guildEncounters 本机强盗和公会首领对局
     * @param vitality 已完成训练的活跃记录
     * @param json 协议数据编码器
     */
    ArenaService(ArenaMapper mapper, ArenaCombat combat, RoleService roles, ElfService elves,
                 RoleCosmeticService cosmetics, InventoryService inventory, BattleMapper normalBattles,
                 DojoCombatService dojo, GuildEncounterService guildEncounters, DailyVitalityEvents vitality,
                 ObjectMapper json) {
        this.mapper = mapper;
        this.combat = combat;
        this.roles = roles;
        this.elves = elves;
        this.cosmetics = cosmetics;
        this.inventory = inventory;
        this.normalBattles = normalBattles;
        this.dojo = dojo;
        this.guildEncounters = guildEncounters;
        this.vitality = vitality;
        this.json = json;
    }

    /**
     * 获取普通竞技训练实际结算的历次积分，商城消费不改变历史成绩。
     *
     * @param roleId 角色编号
     * @return 普通竞技已入账积分总量
     */
    public long exchangePointsEarned(String roleId) {
        return mapper.load(roleId, "stats:" + ARENA).path("points").asLong();
    }

    /**
     * 读取当前训练面板或刷新 NPC 匹配批次。
     *
     * @param roleId 角色编号
     * @param mode 训练模式
     * @param type 精灵王属性，零为总览
     * @param refresh 是否手动换一批
     * @return 原面板协议数据
     */
    @Transactional
    public ObjectNode information(String roleId, String mode, int type, boolean refresh) {
        LocalRole role = roles.lockRole(roleId);
        if (KING.equals(mode)) {
            return kingInformation(role, type);
        }
        ObjectNode selected = SOLO.equals(mode) ? soloLineup(role.id) : null;
        if (selected != null && selected.isEmpty()) {
            return error(TEAM);
        }
        long now = now();
        ObjectNode batch = batch(role, mode, 0, false);
        if (refresh && active(roleId)) {
            return error(BUSY);
        }
        if (refresh && batch.path("next").asLong() <= now) {
            batch = batch(role, mode, 0, true);
        }
        ObjectNode result = error(0);
        ArrayNode matching = result.putArray("matchingPlayers");
        for (JsonNode enemy : batch.path("enemies")) {
            JsonNode lead = enemy.path("team").path("pokemon").path(0);
            ObjectNode row = matching.addObject();
            row.put("id", enemy.path("uid").asText());
            row.put("name", enemy.path("name").asText());
            row.put("fighting", enemy.path("team").path("power").asLong());
            row.put("ranking", enemy.path("index").asInt());
            row.put("sex", 1);
            row.put("vip", 0);
            row.put("dress", 0);
            row.put("title", 0);
            row.put("isnpc", 1);
            row.put("elfid", lead.path("id").asText());
            row.put("elfsid", lead.path("sid").asInt());
        }
        result.put("matchingtime", Math.max(0, batch.path("next").asLong() - now));
        result.set("info", stats(role.id, mode, now));
        result.putObject("award").put("pkpoint", rule(mode).path("winPoints").asInt());
        if (selected != null) {
            result.set("elf_battle_info", selected.path("battle").path(0));
            result.put("positionid", selected.path("positionid").asInt(DEFAULT_POSITION_TYPE));
        }
        return result;
    }

    /**
     * 保存已拥有精灵的单人或属性阵容。
     *
     * @param roleId 角色编号
     * @param mode 训练模式
     * @param type 精灵王属性
     * @param formation 阵法配置编号
     * @param selection 客户端选择的实例与阵位
     * @return 原协议错误码
     */
    @Transactional
    public int setLineup(String roleId, String mode, int type, int formation, List<Map<String, Object>> selection) {
        LocalRole role = roles.lockRole(roleId);
        int max = SOLO.equals(mode) ? 1 : 6;
        if ((KING.equals(mode) && !validType(type)) || formation < 1 || formation > 7
                || selection.size() > max || SOLO.equals(mode) && selection.isEmpty()) {
            return INVALID;
        }
        if (active(roleId)) {
            return BUSY;
        }
        Map<String, LocalElf> owned = owned(roleId);
        ObjectNode state = json.createObjectNode();
        state.put("type", type);
        state.put("positionid", formation);
        ArrayNode lineup = state.putArray("battle");
        Set<String> seen = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        Set<Integer> battleIds = new HashSet<>();
        for (Map<String, Object> item : selection) {
            LocalElf elf = owned.get(item.get("id"));
            Number position = item.get("position") instanceof Number ? (Number) item.get("position") : 0;
            Number battleId = item.get("battleid") instanceof Number ? (Number) item.get("battleid") : position;
            FirstBattleData.Starter species = elf == null ? null : FirstBattleData.starter(elf.configId);
            if (elf == null || !ZODIAC.equals(mode) && species == null || KING.equals(mode) && species.type != type && species.secondType != type
                    || position.longValue() < 1 || position.longValue() > 6
                    || battleId.longValue() < 1 || battleId.longValue() > 6
                    || !seen.add(elf.id) || !positions.add(position.intValue()) || !battleIds.add(battleId.intValue())) {
                return INVALID;
            }
            ObjectNode entry = lineup.addObject();
            entry.put("id", elf.id);
            entry.put("sid", elf.configId);
            entry.put("position", position.intValue());
            entry.put("battleid", battleId.intValue());
        }
        mapper.save(roleId, lineupKey(mode, type), state);
        if (!ZODIAC.equals(mode)) {
            batch(role, mode, type, false);
        }
        return 0;
    }

    /**
     * 按十二宫原协议读取角色独立保存的阵容与阵法。
     *
     * @param roleId 角色编号
     * @return 十二宫编队推送字段
     */
    public ObjectNode zodiacTeam(String roleId) {
        ObjectNode stored = mapper.load(roleId, lineupKey(ZODIAC, 0));
        ObjectNode result = json.createObjectNode();
        result.put("tactics", stored.path("positionid").asInt(1));
        result.set("teaminfo", stored.withArray("battle"));
        return result;
    }

    /**
     * 锁定当前匹配与战斗阵容并扣除一次对应模式挑战次数。
     *
     * @param roleId 角色编号
     * @param mode 训练模式
     * @param type 精灵王属性
     * @param position 当前匹配批次中的敌方索引
     * @param team 玩家选择的普通阵容编号
     * @return 战斗快照或业务错误码
     */
    @Transactional
    public ObjectNode start(String roleId, String mode, int type, int position, int team) {
        LocalRole role = roles.lockRole(roleId);
        long now = now();
        ObjectNode current = mapper.load(roleId, ATTEMPT);
        if (isActive(current, now) || dojo.active(roleId) || guildEncounters.active(roleId)) {
            return error(BUSY);
        }
        BattleAttempt normal = normalBattles.findAttempt(roleId);
        if (normal != null && "ACTIVE".equals(normal.status) && now - normal.startedAt <= 20 * 60) {
            return error(BUSY);
        }
        if ((KING.equals(mode) && !validType(type)) || position <= 0 || (ARENA.equals(mode) && (team < 1 || team > 3 || team > role.stage + 1))) {
            return error(INVALID);
        }
        ArrayNode players = players(role, mode, type, team);
        if (players.isEmpty()) {
            return error(TEAM);
        }
        ObjectNode day = mapper.load(roleId, dailyKey(mode, now));
        int used = day.path("used").asInt();
        if (used >= rule(mode).path("free").asInt() + day.path("bought").asInt()) {
            return error(LIMIT);
        }
        ObjectNode batch = mapper.load(roleId, batchKey(mode, type));
        if (batch.path("day").asLong(Long.MIN_VALUE) != date(now).toEpochDay()) {
            return error(INVALID);
        }
        JsonNode chosen = null;
        for (JsonNode enemy : batch.path("enemies")) {
            if (enemy.path("index").asInt() == position) {
                chosen = enemy;
                break;
            }
        }
        if (chosen == null) {
            return error(INVALID);
        }
        String attemptId = UUID.randomUUID().toString();
        // 挑战按钮携带实际选队，副队沿用已展示的阵容模板和难度，按本队属性匹配对手。
        ObjectNode monster = ARENA.equals(mode) && team != 1
                ? combat.opposition(chosen.path("roster").asText(), "pending", players,
                        chosen.path("difficulty").asInt(), players.path(0).path("level").asInt(1))
                : chosen.path("team").deepCopy();
        String parent = "npc-" + attemptId;
        monster.put("identifer", parent);
        int slot = 0;
        for (JsonNode member : monster.path("pokemon")) {
            ObjectNode pokemon = (ObjectNode) member;
            pokemon.put("id", parent + "-" + (++slot));
            pokemon.put("parent", parent);
        }
        long reference = combat.power(players);
        if (combat.power(ARENA.equals(mode) && team != 1 ? players(role, mode, type, 1) : players)
                != batch.path("reference").asLong()) {
            return error(INVALID);
        }
        int formation = ARENA.equals(mode) ? normalBattles.formationId(roleId, normalBattles.teamFormation(roleId, team))
                : mapper.load(roleId, lineupKey(mode, type)).path("positionid").asInt(DEFAULT_POSITION_TYPE) * 100 + 1;
        ObjectNode attempt = error(0);
        attempt.put("id", attemptId);
        attempt.put("seed", (attemptId.hashCode() & Integer.MAX_VALUE) | 1);
        attempt.put("arenaDamageVersion", 2);
        attempt.put("weather", 0);
        // 原 SINGLE 客户端把顶层 isnpc=1 的 SID 按 tmonster_proto 解析。训练阵容使用精灵 SID。
        attempt.put("isnpc", 0);
        attempt.put("started", now);
        attempt.put("mode", mode);
        attempt.put("type", type);
        attempt.put("version", combat.config.path("sourceVersions").path("rosters").asText());
        attempt.put("roster", chosen.path("roster").asText());
        attempt.put("difficulty", chosen.path("difficulty").asInt());
        attempt.put("reference", reference);
        attempt.put("teamid", team);
        ObjectNode trainedParty = combat.formation(roleId, formation, false, players);
        combat.addTrainingFallback(trainedParty);
        combat.addTrainingFallback(monster);
        attempt.set("player", trainedParty);
        attempt.set("monster", monster);
        mapper.save(roleId, ATTEMPT, attempt);
        day.put("used", used + 1);
        mapper.save(roleId, dailyKey(mode, now), day);
        return attempt;
    }

    /**
     * 查询通用战斗包是否应转交给 NPC 训练场。
     *
     * @param roleId 角色编号
     * @return 当前是否有有效的训练战斗
     */
    public boolean active(String roleId) {
        return isActive(mapper.load(roleId, ATTEMPT), now());
    }

    /**
     * 读取冒险概览显示的剩余竞技训练次数。
     *
     * @param roleId 当前角色编号
     * @return 已扣除使用次数并包含加购次数的今日余额
     */
    public int remainingArena(String roleId) {
        ObjectNode day = mapper.load(roleId, dailyKey(ARENA, now()));
        return Math.max(0, rule(ARENA).path("free").asInt() + day.path("bought").asInt() - day.path("used").asInt());
    }

    /**
     * 提交有效玩家技能并把最终结果原子入账。
     *
     * @param roleId 角色编号
     * @param skill 技能编号
     * @param targets 原客户端目标编号列表
     * @param flag 操作模式
     * @return 战斗结束的真实结算，继续时为空
     */
    @Transactional
    public ObjectNode synchronize(String roleId, int skill, List<?> targets, int flag) {
        roles.lockRole(roleId);
        long now = now();
        ObjectNode attempt = mapper.load(roleId, ATTEMPT);
        if (!isActive(attempt, now)) {
            return null;
        }
        int result = combat.action(attempt, skill, targets, flag);
        if (result == 0) {
            mapper.save(roleId, ATTEMPT, attempt);
        }
        if (result <= 0) {
            return null;
        }
        return finish(roleId, attempt, now, result == 1, false);
    }

    /**
     * 放弃有效训练，记录取消后的次数状态与战绩。
     *
     * @param roleId 角色编号
     * @return 已放弃战斗的结算，无有效训练时为空
     */
    @Transactional
    public ObjectNode flee(String roleId) {
        roles.lockRole(roleId);
        ObjectNode attempt = mapper.load(roleId, ATTEMPT);
        long now = now();
        return isActive(attempt, now) ? finish(roleId, attempt, now, false, true) : null;
    }

    /**
     * 按模式扣除本地钻石购买当日一次挑战机会。
     *
     * @param roleId 角色编号
     * @param mode 训练模式
     * @param receipt 本次连接与请求的幂等键
     * @return 原错误码及余额是否改变
     */
    @Transactional
    public ObjectNode buy(String roleId, String mode, String receipt) {
        roles.lockRole(roleId);
        long now = now();
        String key = dailyKey(mode, now);
        ObjectNode state = mapper.load(roleId, key);
        if (state.path("receipts").has(receipt)) {
            return error(0);
        }
        if (active(roleId)) {
            return error(BUSY);
        }
        int bought = state.path("bought").asInt();
        JsonNode prices = rule(mode).path("buyDiamonds");
        if (bought >= prices.size()) {
            return error(LIMIT);
        }
        if (!inventory.spendMaterials(roleId, Collections.emptyMap(), 0, prices.get(bought).asLong())) {
            return error(100004);
        }
        state.put("bought", bought + 1);
        state.with("receipts").put(receipt, true);
        mapper.save(roleId, key, state);
        return error(0).put("changed", true);
    }

    /**
     * 领取自然周实际积分满足的最高一档奖励，每周仅结算一次。
     *
     * @param roleId 角色编号
     * @param index 请求的 1 基档位
     * @return 错误码、已领取档位及实际放进背包的道具
     */
    @Transactional
    public ObjectNode claim(String roleId, int index) {
        roles.lockRole(roleId);
        long now = now();
        String key = weekKey(ARENA, now);
        ObjectNode week = mapper.load(roleId, key);
        int points = week.path("points").asInt();
        int highest = 0;
        JsonNode thresholds = combat.config.path("thresholds");
        for (int i = 0; i < thresholds.size(); i++) {
            if (points >= thresholds.get(i).asInt()) {
                highest = i + 1;
            }
        }
        if (index <= 0 || index != highest) {
            return error(INVALID);
        }
        int prior = week.path("claimed").asInt();
        if (prior > 0) {
            return error(prior == index ? 0 : LIMIT).put("idx", prior);
        }
        Map<Integer, Long> rewards = new HashMap<>();
        for (JsonNode award : combat.config.path("awards").get(index - 1)) {
            rewards.merge(award.path("id").asInt(), award.path("quantity").asLong(), Long::sum);
        }
        Map<Integer, Long> delivered = inventory.grantItemsOrMail(roleId, "arena-week-" + weekStart(now), rewards);
        week.put("claimed", index);
        mapper.save(roleId, key, week);
        ObjectNode answer = error(0).put("idx", index).put("changed", true);
        ArrayNode items = answer.putArray("items");
        delivered.forEach((id, count) -> items.addObject().put("id", id).put("n", count));
        return answer;
    }

    /**
     * 提取已经保存的精灵王出战阵容供原客户端推送。
     *
     * @param roleId 角色编号
     * @return 精灵王阵容列表
     */
    public ArrayNode kingLineups(String roleId) {
        ArrayNode states = json.createArrayNode();
        for (int type = 1; type <= ATTRIBUTES; type++) {
            ObjectNode lineup = mapper.load(roleId, lineupKey(KING, type));
            if (!lineup.isEmpty()) {
                states.add(lineup);
            }
        }
        return states;
    }

    /**
     * 生成精灵王的属性总览或三个难度对手。
     *
     * @param role 当前角色
     * @param type 零为总览，否则为指定属性
     * @return C107 面板
     */
    private ObjectNode kingInformation(LocalRole role, int type) {
        if (type != 0 && !validType(type)) {
            return error(INVALID);
        }
        ObjectNode state = error(0);
        state.put("type", type);
        state.set("lineup", kingLineups(role.id));
        ObjectNode day = mapper.load(role.id, dailyKey(KING, now()));
        state.put("sumCount", day.path("used").asInt());
        state.put("buyTimes", day.path("bought").asInt());
        ArrayNode awards = state.putArray("awardlist");
        for (int difficulty = 0; difficulty < 3; difficulty++) {
            awards.addObject().put("pkpoint", rule(KING).path("winPoints").asInt());
        }
        ArrayNode ranks = state.putArray("myrank");
        for (int current = 1; current <= ATTRIBUTES; current++) {
            ranks.add(mapper.load(role.id, "king-rank:" + current).path("rank").asInt());
        }
        ArrayNode opponents = state.putArray("battle_king");
        int first = type == 0 ? 1 : type;
        int last = type == 0 ? ATTRIBUTES : type;
        for (int current = first; current <= last; current++) {
            JsonNode enemies = batch(role, KING, current, false).path("enemies");
            for (JsonNode enemy : enemies) {
                if (type == 0 && enemy.path("index").asInt() != 1) {
                    continue;
                }
                ObjectNode row = opponents.addObject();
                row.put("uid", enemy.path("uid").asText());
                row.put("type", current);
                row.put("isnpc", 1);
                row.put("title", 0);
                row.put("stage", 0);
                row.put("dress", 0);
                row.put("name", enemy.path("name").asText());
                row.put("sex", 1);
                row.put("power", enemy.path("team").path("power").asLong());
                row.put("pos", enemy.path("index").asInt());
                ArrayNode slots = row.putArray("elfbattle");
                for (JsonNode member : enemy.path("team").path("pokemon")) {
                    slots.addObject().put("id", member.path("id").asText()).put("sid", member.path("sid").asInt())
                            .put("position", member.path("position").asInt()).put("battleid", member.path("position").asInt())
                            .put("hp", member.path("hp").asInt()).put("hpmax", member.path("hp_max").asInt());
                }
            }
        }
        return state;
    }

    /**
     * 更新模式战绩、积分钱包、已结束场次和精灵王分路记录。
     *
     * @param roleId 角色编号
     * @param attempt 已打开的训练战斗
     * @param now 结束时间
     * @param won 是否取得胜利
     * @param forfeited 是否由玩家主动中止
     * @return 供原客户端推送的结果
     */
    private ObjectNode finish(String roleId, ObjectNode attempt, long now, boolean won, boolean forfeited) {
        String mode = attempt.path("mode").asText();
        int points = forfeited ? 0 : rule(mode).path(won ? "winPoints" : "lossPoints").asInt();
        String lifetimeKey = "stats:" + mode;
        ObjectNode lifetime = mapper.load(roleId, lifetimeKey);
        int oldRank = lifetime.path("rank").asInt(INITIAL_RANK);
        int newRank = won ? Math.max(1, oldRank - 1) : Math.min(INITIAL_RANK, oldRank + 1);
        lifetime.put("rank", newRank);
        lifetime.put("points", lifetime.path("points").asLong() + points);
        lifetime.put("wins", lifetime.path("wins").asInt() + (won ? 1 : 0));
        lifetime.put("streak", won ? lifetime.path("streak").asInt() + 1 : 0);
        mapper.save(roleId, lifetimeKey, lifetime);
        long start = attempt.path("started").asLong(now);
        String weekKey = weekKey(mode, start);
        ObjectNode week = mapper.load(roleId, weekKey);
        week.put("points", week.path("points").asLong() + points);
        mapper.save(roleId, weekKey, week);
        if (KING.equals(mode) && won) {
            String key = "king-rank:" + attempt.path("type").asInt();
            ObjectNode state = mapper.load(roleId, key);
            int rank = attempt.path("difficulty").asInt();
            state.put("rank", Math.min(state.path("rank").asInt(3), 3 - rank));
            mapper.save(roleId, key, state);
        }
        if (ARENA.equals(mode) && !forfeited) {
            vitality.record(roleId, 17, attempt.path("id").asText(), 1);
        }
        mapper.remove(roleId, ATTEMPT);
        return json.createObjectNode().put("mode", mode).put("won", won).put("points", points)
                .put("oldranking", oldRank).put("nowranking", newRank);
    }

    /**
     * 读取当前可见且只对该角色有效的一批 NPC。
     *
     * @param role 当前角色
     * @param mode 训练模式
     * @param type 精灵王属性
     * @param replace 是否重新分配对手
     * @return 角色私有的匹配批次和实际阵容
     */
    private ObjectNode batch(LocalRole role, String mode, int type, boolean replace) {
        long now = now();
        String key = batchKey(mode, type);
        ObjectNode batch = mapper.load(role.id, key);
        long day = date(now).toEpochDay();
        ArrayNode team = players(role, mode, type, 1);
        long reference = combat.power(team);
        if (!replace && batch.path("day").asLong(Long.MIN_VALUE) == day
                && batch.path("reference").asLong() == reference) {
            return batch;
        }
        JsonNode pool = combat.config.path("pools").path(mode);
        if (KING.equals(mode)) {
            pool = pool.path(Integer.toString(type));
        }
        int revision = batch.path("revision").asInt() + (replace ? 1 : 0);
        long deadline = batch.path("day").asLong(Long.MIN_VALUE) == day ? batch.path("next").asLong() : 0;
        batch = json.createObjectNode();
        batch.put("revision", revision);
        batch.put("day", day);
        batch.put("reference", reference);
        batch.put("next", replace ? now + REFRESH_TTL : deadline);
        ArrayNode enemies = batch.putArray("enemies");
        int level = team.isEmpty() ? 1 : team.path(0).path("level").asInt(1);
        for (int difficulty = 0; difficulty < 3; difficulty++) {
            int pick = KING.equals(mode) ? difficulty
                    : Math.floorMod((int) day + role.id.hashCode() + revision * 3 + difficulty, pool.size());
            String roster = pool.path(pick).asText();
            String uid = "npc-" + mode + "-" + type + "-" + day + "-" + revision + "-" + difficulty;
            ObjectNode enemy = enemies.addObject();
            enemy.put("uid", uid);
            enemy.put("name", "[NPC] " + (difficulty == 0 ? "初阶训练" : difficulty == 1 ? "进阶训练" : "高阶训练"));
            enemy.put("roster", roster);
            enemy.put("difficulty", difficulty);
            enemy.put("index", difficulty + 1);
            enemy.set("team", combat.opposition(roster, uid, team, difficulty, level));
        }
        mapper.save(role.id, key, batch);
        return batch;
    }

    /**
     * 将有效模式次数与积分转换为原竞技面板字段。
     *
     * @param roleId 角色编号
     * @param mode 训练模式
     * @param now 查询时间
     * @return 原 arenainfo 结构
     */
    private ObjectNode stats(String roleId, String mode, long now) {
        ObjectNode day = mapper.load(roleId, dailyKey(mode, now));
        ObjectNode week = mapper.load(roleId, weekKey(mode, now));
        ObjectNode lifetime = mapper.load(roleId, "stats:" + mode);
        ObjectNode view = json.createObjectNode();
        view.put("fighting", mapper.load(roleId, batchKey(mode, 0)).path("reference").asLong());
        view.put("ranking", lifetime.path("rank").asInt(INITIAL_RANK));
        view.put("winnum", lifetime.path("wins").asInt());
        view.put("continuouswin", lifetime.path("streak").asInt());
        view.put("arena_point", lifetime.path("points").asLong());
        view.put("sumCount", day.path("used").asInt());
        view.put("buyTimes", day.path("bought").asInt());
        int claimed = week.path("claimed").asInt();
        view.put("weektimes", claimed > 0 ? 0 : week.path("points").asLong());
        ArrayNode record = view.putArray("weekrecord");
        if (claimed > 0) {
            record.add(claimed);
        }
        return view;
    }

    /**
     * 核验上阵实例后生成当前角色的战斗精灵列表。
     *
     * @param role 当前角色
     * @param mode 训练模式
     * @param type 精灵王属性
     * @param team 普通队伍编号
     * @return 有效且按阵位排序的出战成员
     */
    ArrayNode players(LocalRole role, String mode, int type, int team) {
        ArrayNode result = json.createArrayNode();
        ArrayList<Map.Entry<LocalElf, Integer>> members = new ArrayList<>();
        Map<String, LocalElf> owned = owned(role.id);
        if (ARENA.equals(mode)) {
            for (LocalElf elf : owned.values()) {
                if (elf.team == team && elf.slot > 0 && elf.slot <= 6) {
                    members.add(new java.util.AbstractMap.SimpleImmutableEntry<>(elf, elf.position()));
                }
            }
        } else {
            ObjectNode saved = mapper.load(role.id, lineupKey(mode, type));
            for (JsonNode entry : saved.path("battle")) {
                LocalElf elf = owned.get(entry.path("id").asText());
                int position = entry.path("position").asInt();
                if (elf != null && position > 0) {
                    members.add(new java.util.AbstractMap.SimpleImmutableEntry<>(elf, position));
                }
            }
        }
        members.sort(Comparator.comparingInt(Map.Entry::getValue));
        RoleCosmeticService.BattleBonuses bonuses = cosmetics.battleBonuses(role.id);
        for (Map.Entry<LocalElf, Integer> entry : members) {
            if (FirstBattleData.starter(entry.getKey().configId) != null) {
                result.add(combat.player(role.id, entry.getKey(), entry.getValue(), bonuses));
            }
        }
        return result;
    }

    /**
     * 读取玩家实际拥有的精灵实例。
     *
     * @param roleId 角色编号
     * @return 以精灵实例编号查询的集合
     */
    private Map<String, LocalElf> owned(String roleId) {
        Map<String, LocalElf> result = new HashMap<>();
        for (LocalElf elf : elves.list(roleId)) {
            result.put(elf.id, elf);
        }
        return result;
    }

    /**
     * 返回当前模式随包规则。
     *
     * @param mode 训练模式
     * @return 数值组
     */
    private JsonNode rule(String mode) {
        return combat.config.path("rules").path(mode);
    }

    /**
     * 识别可选的底包属性。
     *
     * @param type 属性编号
     * @return 属性是否有效
     */
    private boolean validType(int type) {
        return type >= 1 && type <= ATTRIBUTES;
    }

    /**
     * 判断角色存档是否仍有进行中的战斗。
     *
     * @param attempt 训练战斗存档
     * @param now 当前时间
     * @return 是否有效
     */
    private boolean isActive(ObjectNode attempt, long now) {
        if ("world-boss".equals(attempt.path("mode").asText()) || "feast".equals(attempt.path("mode").asText())) return now < attempt.path("expiry").asLong();
        return attempt.has("id") && now - attempt.path("started").asLong() <= ATTEMPT_TTL;
    }

    /**
     * 恢复已保存的单人精灵。首次打开时使用真正拥有的有效精灵。
     *
     * @param roleId 当前角色编号
     * @return 单人阵容，无可用精灵时为空对象
     */
    private ObjectNode soloLineup(String roleId) {
        String key = lineupKey(SOLO, 0);
        ObjectNode saved = mapper.load(roleId, key);
        Map<String, LocalElf> owned = owned(roleId);
        LocalElf previous = owned.get(saved.path("battle").path(0).path("id").asText());
        if (previous != null && FirstBattleData.starter(previous.configId) != null) {
            ObjectNode slot = (ObjectNode) saved.path("battle").get(0);
            if (slot.path("sid").asInt() != previous.configId) {
                slot.put("sid", previous.configId);
                mapper.save(roleId, key, saved);
            }
            return saved;
        }
        int currentTeam=normalBattles.choice(roleId,"default");
        Comparator<LocalElf> order = Comparator
                .comparingInt((LocalElf elf) -> elf.team == currentTeam && elf.slot > 0 ? elf.slot : Integer.MAX_VALUE)
                .thenComparing(elf -> elf.id);
        LocalElf starter = owned.values().stream().filter(elf -> FirstBattleData.starter(elf.configId) != null)
                .min(order).orElse(null);
        ObjectNode lineup = json.createObjectNode();
        if (starter != null) {
            lineup.put("positionid", DEFAULT_POSITION_TYPE);
            lineup.putArray("battle").addObject().put("id", starter.id).put("sid", starter.configId)
                    .put("position", 1).put("battleid", 1);
            mapper.save(roleId, key, lineup);
        }
        return lineup;
    }

    /**
     * 创建原协议响应。
     *
     * @param errno 原错误码
     * @return 通用业务响应
     */
    private ObjectNode error(int errno) {
        return json.createObjectNode().put("errno", errno);
    }

    /**
     * 读取 UTC 秒时间。
     *
     * @return 当前 Unix 时间
     */
    private long now() {
        return Instant.now().getEpochSecond();
    }

    /**
     * 计算当前业务日。
     *
     * @param time Unix 时间
     * @return 北京时间日期
     */
    private LocalDate date(long time) {
        return Instant.ofEpochSecond(time).atZone(ZONE).toLocalDate();
    }

    /**
     * 获取本地自然周周一。
     *
     * @param time Unix 时间
     * @return 周一起算日
     */
    private long weekStart(long time) {
        return date(time).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    /**
     * 按日期和模式隔离次数。
     *
     * @param mode 训练模式
     * @param time 业务时间
     * @return 当日存档键
     */
    private String dailyKey(String mode, long time) {
        return "day:" + mode + ":" + date(time).toEpochDay();
    }

    /**
     * 按周和模式隔离积分。
     *
     * @param mode 训练模式
     * @param time 业务时间
     * @return 当周存档键
     */
    private String weekKey(String mode, long time) {
        return "week:" + mode + ":" + weekStart(time);
    }

    /**
     * 确定模式与属性的阵容存档组。
     *
     * @param mode 训练模式
     * @param type 精灵王属性，其他模式为零
     * @return 阵容存档键
     */
    private String lineupKey(String mode, int type) {
        return "lineup:" + mode + ":" + type;
    }

    /**
     * 确定模式与属性的当前匹配批次。
     *
     * @param mode 训练模式
     * @param type 精灵王属性，其他模式为零
     * @return 匹配批次键
     */
    private String batchKey(String mode, int type) {
        return "batch:" + mode + ":" + type;
    }
}
