package com.pokemon.local.guild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.arena.ArenaCombat;
import com.pokemon.local.arena.ArenaService;
import com.pokemon.local.battle.BattleMapper;
import com.pokemon.local.battle.FirstBattleData;
import com.pokemon.local.battle.api.entity.BattleAttempt;
import com.pokemon.local.dojo.DojoCombatService;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.guild.api.entity.LocalGuild;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleCosmeticService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import com.pokemon.local.scene.SceneService;
import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本机强盗、公会副本和回合结算 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:27
 */
@Service
public class GuildEncounterService {
    public static final int GUARD = 127;
    public static final int BANDIT_BOSS = 128;
    public static final int GUILD_BOSS = 5070;
    private static final int GUARD_LIMIT = 3;
    private static final int BOSS_LIMIT = 1;
    private static final int SIMULATION_LIMIT = 5;
    private static final long BATTLE_TTL = 1_200;
    private static final String ATTEMPT = "attempt";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 查询当前角色考古背包。
     * @param roleId 角色编号
     * @return S465 原协议背包
     */
    public Map<String, Object> antiquities(String roleId) {
        return guilds.antiquities(roleId);
    }

    private final GuildEncounterMapper states;
    private final ObjectMapper json;
    private final JsonNode config;
    private final ArenaCombat combat;
    private final RoleService roles;
    private final PersonalGuildService guilds;
    private final ElfService elfs;
    private final RoleCosmeticService cosmetics;
    private final InventoryService inventory;
    private final ObjectProvider<ArenaService> arena;
    private final DojoCombatService dojo;
    private final BattleMapper normal;

    /**
     * 注入快照、战斗计算、角色及已启用的其他模式。
     *
     * @param states 本地遭遇状态
     * @param json 配置和快照解析器
     * @param combat 共用回合计算
     * @param roles 当前本地角色
     * @param guilds 个人公会存档
     * @param elfs 本机出阵精灵
     * @param cosmetics 角色外观加成
     * @param inventory 奖励存档
     * @param arena 竞技模式状态
     * @param dojo 道馆战斗状态
     * @param normal 首关及冒险挑战状态
     * @throws IOException 读取随包配置失败
     */
    GuildEncounterService(GuildEncounterMapper states, ObjectMapper json, ArenaCombat combat, RoleService roles,
                          PersonalGuildService guilds, ElfService elfs, RoleCosmeticService cosmetics,
                          InventoryService inventory, ObjectProvider<ArenaService> arena, DojoCombatService dojo,
                          BattleMapper normal) throws IOException {
        this.states = states;
        this.json = json;
        this.combat = combat;
        this.roles = roles;
        this.guilds = guilds;
        this.elfs = elfs;
        this.cosmetics = cosmetics;
        this.inventory = inventory;
        this.arena = arena;
        this.dojo = dojo;
        this.normal = normal;
        try (InputStream input = new ClassPathResource("guild/encounters.json").getInputStream()) {
            config = json.readTree(input);
        }
    }

    /**
     * 查询当前角色是否有本模块正在进行的战斗。
     *
     * @param roleId 当前角色
     * @return 战斗仍可操作时为真
     */
    public boolean active(String roleId) {
        return live(states.read(roleId, ATTEMPT), Instant.now().getEpochSecond());
    }

    /**
     * 结束重登入服后无法继续操作的旧场景战斗，保留已经使用的挑战次数。
     *
     * @param roleId 重新进入游戏的角色编号
     */
    @Transactional
    public void abandonDisconnectedBattle(String roleId) {
        roles.lockRole(roleId);
        ObjectNode attempt = states.read(roleId, ATTEMPT);
        if (attempt.has("id") && !attempt.path("ended").asBoolean()) {
            if (live(attempt, Instant.now().getEpochSecond())) {
                action(roleId, 0, java.util.Collections.emptyList(), -1, true);
                attempt = states.read(roleId, ATTEMPT);
            }
            attempt.put("ended", true).put("won", false).put("disconnected", true);
            states.write(roleId, ATTEMPT, attempt);
        }
    }

    /**
     * 获取角色今日各模式已经使用的次数。
     *
     * @param roleId 当前角色主键
     * @param mode 原玩法协议
     * @param kind 公会首领正式或模拟模式
     * @return 北京自然日已用次数
     */
    public int used(String roleId, int mode, int kind) {
        return states.read(roleId, todayKey(mode, kind, Instant.now().getEpochSecond())).path("used").asInt();
    }

    /**
     * 查询公会首领允许的剩余次数。
     *
     * @param roleId 当前角色主键
     * @param kind 正式或模拟模式
     * @return 今日剩余次数
     */
    public int remaining(String roleId, int kind) {
        return Math.max(0, limit(GUILD_BOSS, kind) - used(roleId, GUILD_BOSS, kind));
    }

    /**
     * 计算该角色在本地场景今日可见的强盗节点。
     *
     * @param role 实际场景存档
     * @param boss 是否为强盗首领
     * @return 可见的场景对象数组
     */
    public ArrayNode actors(LocalRole role, boolean boss) {
        // 地鼠与捣蛋王只出现在公会大厅，独立挑战和面板预览沿用各自的资格校验。
        if (role.sceneId != SceneService.GUILD_HALL) {
            return json.createArrayNode();
        }
        return actors(role, boss, Instant.now().getEpochSecond(), false);
    }

    /**
     * 获取当前角色可查看的强盗首领及击杀后的对应当日状态。
     *
     * @param role 当前角色快照
     * @return 原客户端首领详情字段
     */
    public JsonNode banditBoss(LocalRole role) {
        return actors(role, true, Instant.now().getEpochSecond(), true).path(0);
    }

    /**
     * 按一次读取的本地时钟生成场景演员。
     *
     * @param role 已验证角色
     * @param boss 是否选择首领类型
     * @param now 本次请求时间
     * @param preview 是否用于首领详细资料
     * @return 对应时段及角色的场景演员
     */
    private ArrayNode actors(LocalRole role, boolean boss, long now, boolean preview) {
        ArrayNode rows = json.createArrayNode();
        if (role.sceneId != SceneService.CITY && role.sceneId != SceneService.GUILD_HALL) {
            return rows;
        }
        int mode = boss ? BANDIT_BOSS : GUARD;
        String key = todayKey(mode, 1, now);
        ObjectNode state = states.read(role.id, key);
        if (!preview && state.path("used").asInt() >= limit(mode, 1)) {
            return rows;
        }
        JsonNode candidates = config.path(boss ? "boss" : "guard");
        int count = boss ? 1 : GUARD_LIMIT;
        for (int slot = 0; slot < count; slot++) {
            boolean defeated = state.path("defeated").path(Integer.toString(slot)).asBoolean();
            if (!preview && defeated) {
                continue;
            }
            int which = Math.floorMod(role.id.hashCode() + (int) date(now).toEpochDay() + slot, candidates.size());
            int group = candidates.get(which).asInt();
            ObjectNode row = rows.addObject();
            row.put("sid", group);
            row.put("uid", targetId(role, key, slot));
            row.put("level", Math.max(1, roster(group).path("sourceLevel").asInt()));
            row.put("face", 0);
            row.put("state", 1);
            if (boss) {
                long maximum = maximumHp(group);
                row.put("hp", defeated ? 0 : maximum).put("hpmax", maximum);
            }
            row.putObject("pos").put("x", (boss ? 28 : 23 + slot * 2) * 32)
                    .put("y", (role.sceneId == SceneService.CITY ? (boss ? 30 : 31) : 12) * 32);
        }
        return rows;
    }

    /**
     * 查看当周公会首领第几阶段与当前剩余生命。
     *
     * @param roleId 当前会长
     * @param index 原首领类型索引
     * @return 原面板信息，类型不可用时为空
     */
    public ObjectNode bossGroup(String roleId, int index) {
        LocalGuild guild = guilds.active(roleId);
        JsonNode rule = config.path("guildBosses").path(Integer.toString(index));
        if (guild == null || rule.isMissingNode() || guilds.level(guild) < rule.path("requiredLevel").asInt()) {
            return null;
        }
        String key = guildKey(guild.id, index, Instant.now().getEpochSecond());
        ObjectNode saved = states.read(roleId, key);
        ObjectNode response = error(0);
        JsonNode phases = rule.path("phases");
        int current = saved.path("phase").asInt();
        ArrayNode rows = response.putArray("info");
        for (int i = 0; i < phases.size(); i++) {
            int id = phases.get(i).asInt();
            long max = maximumHp(groupForStage(id));
            ObjectNode phase = rows.addObject().put("bossid", id).put("phase", i + 1).put("hp_max", max);
            phase.put("hp", i < current ? 0 : i == current ? saved.path("hp").asLong(max) : max);
            phase.put("status", !saved.has("phase") ? 0 : i < current ? 2 : i == current ? 1 : 0);
            phase.put("my_rank", i == current && saved.path("damage").asLong() > 0 ? 1 : 0);
            ArrayNode rank = phase.putArray("rank");
            if (i == current && saved.path("damage").asLong() > 0) {
                LocalRole role = roles.findById(roleId).orElseThrow(() -> new IllegalStateException("找不到会长"));
                rank.addObject().put("name", role.name).put("uid", role.id).put("level", role.level).put("sex", role.sex)
                        .put("damage", saved.path("damage").asLong());
            }
        }
        return response;
    }

    /**
     * 按原开放时段、等级和活跃费用开启指定首领的当日副本。
     *
     * @param roleId 当前会长
     * @param index 原首领类型索引
     * @return 原协议错误码
     */
    @Transactional
    public int open(String roleId, int index) {
        return openAt(roleId, index, Instant.now().getEpochSecond());
    }

    /**
     * 使用同一结算时刻校验开放窗口并冻结当日开启记录。
     * @param roleId 当前会长
     * @param index 原首领类型索引
     * @param now 本次结算UTC秒数
     * @return 原协议错误码
     */
    private int openAt(String roleId, int index, long now) {
        LocalRole role = roles.lockRole(roleId);
        LocalGuild guild = guilds.active(roleId);
        JsonNode rule = config.path("guildBosses").path(Integer.toString(index));
        if (guild == null) return 251300;
        if (role.sceneId != SceneService.GUILD_DUNGEON || rule.isMissingNode()) return 100021;
        if (guilds.level(guild) < rule.path("requiredLevel").asInt()) return 251303;
        String key = guildKey(guild.id, index, now);
        ObjectNode saved = states.read(roleId, key);
        if (saved.has("phase")) return 0;
        int timeError = openingTimeError(now);
        if (timeError != 0) return timeError;
        int fee = rule.path("openingVitality").asInt();
        if (!guilds.spendGuildVitality(roleId, guild, fee, key)) return 251313;
        saved.put("phase", 0).put("openedAt", now);
        states.write(roleId, key, saved);
        return 0;
    }

    /**
     * 依据原 variable3202 的北京时间开放范围返回对应原错误码。
     * @param now 当前UTC秒数
     * @return 可开启为零，未到或已过开放时段为原语言错误码
     */
    private int openingTimeError(long now) {
        int hour = Instant.ofEpochSecond(now).atZone(ZONE).getHour();
        JsonNode hours = config.path("guildOpening");
        return hour < hours.path("startHour").asInt() ? 251314
                : hour >= hours.path("endHour").asInt() ? 251305 : 0;
    }

    /**
     * 恢复该会长当日指定档期的公会首领进度。
     *
     * @param roleId 当前会长
     * @param index 首领类型索引，零表示所有已开启的类型
     * @return 已打开的各首领类型及当前可挑战的原阶段编号
     */
    public Map<Integer, Integer> openedBosses(String roleId, int index) {
        Map<Integer, Integer> opened = new LinkedHashMap<>();
        LocalGuild guild = guilds.active(roleId);
        if (guild == null) {
            return opened;
        }
        long now = Instant.now().getEpochSecond();
        config.path("guildBosses").fields().forEachRemaining(entry -> {
            int type = Integer.parseInt(entry.getKey());
            if (index == 0 || index == type) {
                int phase = states.read(roleId, guildKey(guild.id, type, now)).path("phase").asInt(-1);
                if (phase >= 0) {
                    opened.put(type, entry.getValue().path("phases").path(phase).asInt());
                }
            }
        });
        return opened;
    }

    /**
     * 加载并冻结本机已有的目标、角色精灵及选定模式。
     *
     * @param roleId 当前角色
     * @param mode 玩法协议
     * @param uid 点击的强盗场景编号
     * @param stage 公会首领原阶段编号
     * @param kind 正式或模拟首领模式
     * @param team 出战队编号
     * @return 原客户端需要的双方战斗数据
     */
    @Transactional
    public ObjectNode start(String roleId, int mode, String uid, int stage, int kind, int team) {
        LocalRole role = roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ObjectNode previous = states.read(roleId, ATTEMPT);
        if (live(previous, now)) {
            return previous.path("mode").asInt() == mode && previous.path("uid").asText().equals(uid)
                    && previous.path("stage").asInt() == stage && previous.path("kind").asInt() == kind
                    ? previous : error(3);
        }
        BattleAttempt normalBattle = normal.findAttempt(roleId);
        ArenaService otherArena = arena.getIfAvailable();
        if (team != 1 || dojo.active(roleId) || otherArena != null && otherArena.active(roleId)
                || normalBattle != null && "ACTIVE".equals(normalBattle.status) && now - normalBattle.startedAt <= BATTLE_TTL) {
            return error(3);
        }
        ArrayNode allies = json.createArrayNode();
        RoleCosmeticService.BattleBonuses bonuses = cosmetics.battleBonuses(roleId);
        for (LocalElf elf : elfs.list(roleId)) {
            if (elf.team == team && elf.slot > 0 && FirstBattleData.starter(elf.configId) != null) {
                allies.add(combat.player(roleId, elf, elf.position(), bonuses));
            }
        }
        if (allies.isEmpty()) {
            return error(3);
        }
        String daily = todayKey(mode, kind, now);
        ObjectNode quota = states.read(roleId, daily);
        if (quota.path("used").asInt() >= limit(mode, kind)) {
            return error(3);
        }
        ObjectNode attempt = error(0);
        ObjectNode groupState = json.createObjectNode();
        int group;
        if (mode == GUILD_BOSS) {
            LocalGuild guild = guilds.active(roleId);
            if (guild == null || role.sceneId != SceneService.GUILD_DUNGEON || kind < 1 || kind > 2 || stage < 1001) {
                return error(3);
            }
            int index = (stage - 1001) / 10 + 1;
            JsonNode rule = config.path("guildBosses").path(Integer.toString(index));
            String key = guildKey(guild.id, index, now);
            groupState = states.read(roleId, key);
            int phase = groupState.path("phase").asInt(-1);
            if (phase < 0 || rule.path("phases").path(phase).asInt() != stage
                    || guilds.level(guild) < rule.path("requiredLevel").asInt()) {
                return error(3);
            }
            group = groupForStage(stage);
            attempt.put("guildKey", key).put("index", index).put("phase", phase).put("guildId", guild.id);
        } else {
            JsonNode selected = null;
            for (JsonNode actor : actors(role, mode == BANDIT_BOSS, now, false)) {
                if (actor.path("uid").asText().equals(uid)) {
                    selected = actor;
                    break;
                }
            }
            if (selected == null) {
                return error(3);
            }
            group = selected.path("sid").asInt();
            attempt.put("slot", Integer.parseInt(uid.substring(uid.lastIndexOf(':') + 1)));
        }
        String id = UUID.randomUUID().toString();
        attempt.put("id", id).put("started", now).put("mode", mode).put("kind", kind).put("scene", role.sceneId)
                .put("stage", stage).put("uid", uid).put("quota", daily).put("weather", 0)
                .put("seed", id.hashCode() & Integer.MAX_VALUE).put("version", config.path("sourceConfigVersion").asText());
        attempt.set("player", combat.formation(roleId, normal.formationId(roleId), false, allies));
        attempt.set("monster", opposition(group, id, groupState.path("members")));
        attempt.put("startHp", hp(attempt));
        states.write(roleId, ATTEMPT, attempt);
        quota.put("used", quota.path("used").asInt() + 1);
        states.write(roleId, daily, quota);
        return attempt;
    }

    /**
     * 由有效技能回合计算伤害、失败及首杀奖励。
     *
     * @param roleId 当前角色
     * @param skill 原技能编号
     * @param targets 本次技能目标列表
     * @param flag 原战斗模式标记
     * @param flee 是否放弃本场
     * @return 结束时的真实结算快照，未结束时为空
     */
    @Transactional
    public ObjectNode action(String roleId, int skill, List<?> targets, int flag, boolean flee) {
        LocalRole role = roles.lockRole(roleId);
        ObjectNode attempt = states.read(roleId, ATTEMPT);
        if (!live(attempt, Instant.now().getEpochSecond())) {
            return null;
        }
        int mode = attempt.path("mode").asInt();
        LocalGuild guild = mode == GUILD_BOSS ? guilds.active(roleId) : null;
        boolean valid = role.sceneId == attempt.path("scene").asInt()
                && (mode != GUILD_BOSS || guild != null && guild.id.equals(attempt.path("guildId").asText()));
        int outcome = valid && !flee ? combat.action(attempt, skill, targets, flag) : 2;
        if (outcome < 0) {
            return null;
        }
        if (outcome == 0) {
            states.write(roleId, ATTEMPT, attempt);
            return null;
        }
        boolean won = outcome == 1;
        long damage = valid ? attempt.path("startHp").asLong() - hp(attempt) : 0;
        boolean official = mode != GUILD_BOSS || attempt.path("kind").asInt() == 1;
        if (valid && official && mode == GUILD_BOSS) {
            String key = attempt.path("guildKey").asText();
            ObjectNode group = states.read(roleId, key);
            if (won) {
                group.put("phase", group.path("phase").asInt() + 1);
                group.remove("members");
                group.remove("hp");
                group.remove("damage");
            } else {
                group.put("hp", hp(attempt)).put("damage", group.path("damage").asLong() + damage);
                ArrayNode hp = group.putArray("members");
                attempt.path("monster").path("pokemon").forEach(monster -> hp.add(monster.path("hp").asLong()));
            }
            states.write(roleId, key, group);
        }
        if (valid && won && mode != GUILD_BOSS) {
            String key = attempt.path("quota").asText();
            ObjectNode state = states.read(roleId, key);
            state.with("defeated").put(attempt.path("slot").asText(), true);
            states.write(roleId, key, state);
        }
        Map<Integer, Long> direct = new LinkedHashMap<>();
        if (valid && won && official) {
            Map<Integer, Long> reward = new LinkedHashMap<>();
            reward.put(21063, mode == BANDIT_BOSS ? 2L : 1L);
            if (mode == GUARD) states.defeatedMole(roleId);
            if (mode != GUARD) {
                reward.put(20027, 1L);
            }
            if ((mode == GUARD || mode == BANDIT_BOSS) && guilds.active(roleId) != null) {
                ObjectNode archaeology = states.read(roleId, "archaeology");
                if (mode == GUARD && role.level >= 50
                        && java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < 30) {
                    int fragment = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 9);
                    String slot = String.valueOf(fragment);
                    if (!archaeology.path("pieces").has(slot)) {
                        archaeology.with("pieces").put(slot, 0);
                        attempt.put("tabletDrop", fragment);
                        states.write(roleId, "archaeology", archaeology);
                    }
                }
                int map = mode == GUARD ? 101 : 102;
                String grantDay = "rewardDay" + map;
                long today = date(Instant.now().getEpochSecond()).toEpochDay();
                if (archaeology.path(grantDay).asLong(-1) != today) {
                    PersonalGuildService.addAntiquity(archaeology, map, 1);
                    if (mode == BANDIT_BOSS) {
                        int badge = guilds.drawBadgeMaterial();
                        PersonalGuildService.addAntiquity(archaeology, badge, 1);
                        attempt.put("badgeMaterial", badge);
                    }
                    archaeology.put(grantDay, today);
                    states.write(roleId, "archaeology", archaeology);
                    attempt.put("treasureMap", map);
                }
            }
            direct = inventory.grantItemsOrMail(roleId, "guild-encounter:" + attempt.path("id").asText(), reward);
        }
        attempt.put("ended", true).put("won", won && valid).put("damage", damage);
        ArrayNode items = attempt.putArray("items");
        // 原 parse_reward 将带 type 的普通道具当作技能书；普通奖励仅传模板和数量。
        direct.forEach((item, count) -> items.addObject().put("id", item).put("n", count));
        states.write(roleId, ATTEMPT, attempt);
        return attempt;
    }

    /**
     * 返回强盗首领已经记录的实际击杀排行。
     *
     * @param role 当前角色
     * @return 原单人公会强盗首领排名
     */
    public ArrayNode banditRank(LocalRole role) {
        long now = Instant.now().getEpochSecond();
        ArrayNode rows = json.createArrayNode();
        if (states.read(role.id, todayKey(BANDIT_BOSS, 1, now)).path("defeated").path("0").asBoolean()) {
            rows.addObject().put("uid", role.id).put("name", role.name).put("sex", role.sex).put("score", 1);
        }
        return rows;
    }

    /**
     * 读取底包固定怪物组并按记录还原残余生命。
     *
     * @param group 原怪物组编号
     * @param id 当前对局编号
     * @param savedHp 副本上次保存的生命数组
     * @return 战斗协议的怪物队伍
     */
    private ObjectNode opposition(int group, String id, JsonNode savedHp) {
        JsonNode roster = roster(group);
        String parent = "npc:" + id;
        ArrayNode members = json.createArrayNode();
        int slot = 0;
        for (JsonNode member : roster.path("members")) {
            int sprite = member.path("sid").asInt();
            JsonNode species = config.path("species").path(Integer.toString(sprite));
            JsonNode skill = species.path("skill");
            ObjectNode sourceStats = (ObjectNode) member.path("stats");
            ObjectNode attrs = sourceStats.deepCopy();
            ObjectNode actor = combat.fixedActor(attrs, parent + ":" + slot, parent, member.path("enemyId").asInt(),
                    sprite, Math.max(1, member.path("sourceLevel").asInt(roster.path("sourceLevel").asInt())),
                    member.path("position").asInt(), species.path("type1").asInt(), species.path("type2").asInt(),
                    species.path("quality").asInt(), skill.path("id").asInt(), skill.path("targetCount").asInt(),
                    skill.path("power").asInt());
            actor.setAll(sourceStats);
            actor.put("hp", savedHp.path(slot++).asLong(actor.path("hp_max").asLong()));
            members.add(actor);
        }
        return combat.formation(parent, roster.path("formationId").asInt(), true, members);
    }

    /**
     * 检查当前快照是否有可执行的战斗回合。
     *
     * @param attempt 对局快照
     * @param now 当前秒
     * @return 是否在结算窗口内
     */
    private boolean live(ObjectNode attempt, long now) {
        return attempt.has("id") && !attempt.path("ended").asBoolean() && now - attempt.path("started").asLong() <= BATTLE_TTL;
    }

    /**
     * 汇总参战怪物的剩余生命。
     *
     * @param attempt 对局快照
     * @return 当前怪物剩余生命总和
     */
    private long hp(ObjectNode attempt) {
        long hp = 0;
        for (JsonNode unit : attempt.path("monster").path("pokemon")) {
            hp += unit.path("hp").asLong();
        }
        return hp;
    }

    /**
     * 计算原怪物组在满状态时的生命。
     *
     * @param group 原怪物组
     * @return 该组的总满生命
     */
    private long maximumHp(int group) {
        long sum = 0;
        for (JsonNode unit : roster(group).path("members")) {
            sum += unit.path("stats").path("hp_max").asLong();
        }
        return sum;
    }

    /**
     * 读取随包的原怪物组。
     *
     * @param group 怪物组编号
     * @return 已核对的固定怪物组资料
     */
    private JsonNode roster(int group) {
        return config.path("rosters").path(Integer.toString(group));
    }

    /**
     * 通过副本阶段查找原战斗组。
     *
     * @param stage 原公会首领阶段
     * @return 对应底包怪物组编号
     */
    private int groupForStage(int stage) {
        return config.path("stages").path(Integer.toString(stage)).asInt();
    }

    /**
     * 查找单个挑战入口的日上限。
     *
     * @param mode 挑战玩法
     * @param kind 正式或模拟模式
     * @return 今日上限
     */
    private int limit(int mode, int kind) {
        return mode == GUARD ? GUARD_LIMIT : mode == GUILD_BOSS && kind == 2 ? SIMULATION_LIMIT : BOSS_LIMIT;
    }

    /**
     * 生成原战斗接口使用的处理结果。
     *
     * @param errno 原协议错误码
     * @return 处理结果体
     */
    private ObjectNode error(int errno) {
        return json.createObjectNode().put("errno", errno);
    }

    /**
     * 计算挑战所在的业务日期。
     *
     * @param now 当前 UTC 秒
     * @return 北京自然日
     */
    private LocalDate date(long now) {
        return Instant.ofEpochSecond(now).atZone(ZONE).toLocalDate();
    }

    /**
     * 构建按玩法和北京自然日隔离的次数键。
     *
     * @param mode 玩法
     * @param kind 正式或模拟
     * @param now 当前秒
     * @return 独立次数键
     */
    private String todayKey(int mode, int kind, long now) {
        return mode + ":" + kind + ":" + date(now).toEpochDay();
    }

    /**
     * 对公会和首领类型隔离当周档期。
     *
     * @param gid 个人公会主键
     * @param index 首领类别
     * @param now 当前秒
     * @return 当日首领状态键，旧周档保留归档而不覆盖
     */
    private String guildKey(String gid, int index, long now) {
        return gid + ":" + index + ":day:" + date(now).toEpochDay();
    }

    /**
     * 用真实场景、时期和槽位生成客户端攻击对象。
     *
     * @param role 当前角色
     * @param key 次数键
     * @param slot 敌人槽位
     * @return 场景内合法的攻击对象号
     */
    private String targetId(LocalRole role, String key, int slot) {
        return "npc:" + role.sceneId + ":" + role.id + ":" + key + ":" + slot;
    }
}
