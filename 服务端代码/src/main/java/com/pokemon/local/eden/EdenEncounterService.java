package com.pokemon.local.eden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.arena.ArenaCombat;
import com.pokemon.local.arena.ArenaService;
import com.pokemon.local.arena.ZodiacService;
import com.pokemon.local.battle.BattleMapper;
import com.pokemon.local.battle.api.entity.BattleAttempt;
import com.pokemon.local.dojo.DojoCombatService;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.guild.GuildEncounterService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleCosmeticService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 神兽岛场景目标、挑战和捕获的本地存档服务。
 * @author tongruiyuan
 * @since 2026-11-19 18:27
 */
@Service
public class EdenEncounterService {
    private static final int[][] TILES = { { 15, 14 }, { 15, 16 }, { 13, 12 }, { 14, 15 }, { 14, 13 }, { 12, 13 } };
    private static final int[] PRICES = { 200, 500, 800, 1600, 3000 };
    private final EdenService config;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RoleService roles;
    private final RoleCosmeticService cosmetics;
    private final InventoryService inventory;
    private final ElfService elves;
    private final ArenaCombat combat;
    private final BattleMapper normal;
    private final GuildEncounterService guild;
    private final ArenaService arena;
    private final ZodiacService zodiac;
    private final DojoCombatService dojo;

    /** 绑定原配置、战斗与资产事务。
     * @param config 神兽岛目标配置
     * @param jdbc 存档数据库
     * @param json 存档转换器
     * @param roles 当前角色和角色锁
     * @param cosmetics 角色战斗加成
     * @param inventory 背包、货币和邮件
     * @param elves 精灵资产
     * @param combat 单人回合结算
     * @param normal 当前常规战斗
     * @param guild 公会战斗
     * @param arena 竞技战斗
     * @param zodiac 十二宫战斗
     * @param dojo 道馆战斗
     */
    public EdenEncounterService(EdenService config, JdbcTemplate jdbc, ObjectMapper json, RoleService roles,
            RoleCosmeticService cosmetics, InventoryService inventory, ElfService elves, ArenaCombat combat,
            BattleMapper normal, GuildEncounterService guild, ArenaService arena, ZodiacService zodiac,
            DojoCombatService dojo) {
        this.config = config;
        this.jdbc = jdbc;
        this.json = json;
        this.roles = roles;
        this.cosmetics = cosmetics;
        this.inventory = inventory;
        this.elves = elves;
        this.combat = combat;
        this.normal = normal;
        this.guild = guild;
        this.arena = arena;
        this.zodiac = zodiac;
        this.dojo = dojo;
    }

    /** 建立角色存档与请求回执。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("eden/schema.sql")).execute(jdbc.getDataSource());
    }

    /** 读取一个存档或回执。
     * @param sql 查询
     * @param args 参数
     * @return 已存对象，缺失时为空对象
     */
    private ObjectNode read(String sql, Object... args) {
        List<String> rows = jdbc.queryForList(sql, String.class, args);
        if (rows.isEmpty())
            return json.createObjectNode();
        try {
            return (ObjectNode) json.readTree(rows.get(0));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("神兽岛存档无效", e);
        }
    }

    /** 在角色锁下提交状态。
     * @param id 角色编号
     * @param state 岛屿与战斗状态
     */
    private void save(String id, ObjectNode state) {
        jdbc.update("MERGE INTO local_eden_state(role_id,state_json) KEY(role_id) VALUES(?,?)", id, state.toString());
    }

    /** 恢复每日次数并处理在线或离线期间的目标到期。
     * @param role 已锁角色
     * @param now 当前秒
     * @return 当前状态
     */
    private ObjectNode load(LocalRole role, long now) {
        ObjectNode state = read("SELECT state_json FROM local_eden_state WHERE role_id=?", role.id);
        long day = LocalDate.now(ZoneOffset.ofHours(8)).toEpochDay();
        if (state.path("day").asLong(-1) != day)
            state.put("day", day).put("times", 0).put("buytime", 0);
        if (!state.has("actors")) {
            state.set("actors", json.valueToTree(config.list(role)));
            for (JsonNode value : state.path("actors")) {
                ObjectNode actor = (ObjectNode) value;
                actor.put("autotime", now + 60L * template(actor).path("recycling").asInt());
            }
        }
        for (JsonNode value : state.path("actors")) {
            ObjectNode actor = (ObjectNode) value;
            int status = actor.path("state").asInt();
            long deadline = actor.path(status == 0 ? "autotime" : status == 2 ? "escapetime" : "deadline").asLong();
            if (deadline <= 0 || now < deadline)
                continue;
            if (status == 1)
                state.remove("fight");
            if (status != 3) {
                actor.put("state", 3).put("deadline", deadline + 60L * template(actor).path("flush").asInt());
                actor.remove(Arrays.asList("ownerid", "ownername"));
            }
            if (now >= actor.path("deadline").asLong())
                respawn(state, actor, now);
        }
        return state;
    }

    /** 读取一个目标的静态配置。
     * @param actor 状态行
     * @return 原配置
     */
    private JsonNode template(JsonNode actor) {
        return config.actor(actor.path("index").asInt());
    }

    /** 同岛同类别按原权重生成新的目标编号，避免与已有目标重复。
     * @param state 角色状态
     * @param actor 已过刷新的槽
     * @param now 当前秒
     */
    private void respawn(ObjectNode state, ObjectNode actor, long now) {
        int land = actor.path("land").asInt();
        Set<Integer> used = new HashSet<>();
        for (JsonNode peer : state.path("actors"))
            if (peer.path("land").asInt() == land)
                used.add(peer.path("index").asInt());
        JsonNode pool = config.pool(land, actor.path("isboss").asBoolean());
        int sum = 0;
        for (JsonNode pair : pool)
            if (!used.contains(pair.get(0).asInt()))
                sum += pair.get(1).asInt();
        int roll = ThreadLocalRandom.current().nextInt(sum);
        for (JsonNode pair : pool) {
            if (used.contains(pair.get(0).asInt()))
                continue;
            roll -= pair.get(1).asInt();
            if (roll < 0) {
                actor.put("index", pair.get(0).asInt());
                break;
            }
        }
        actor.put("uid", UUID.randomUUID().toString()).put("state", 0).put("deadline", 0).put("escapetime", 0);
        actor.put("autotime", now + 60L * template(actor).path("recycling").asInt());
    }

    /** 定位当前已知的目标。
     * @param state 存档
     * @param uid 目标实例
     * @return 原目标或空值
     */
    private ObjectNode target(ObjectNode state, String uid) {
        for (JsonNode value : state.path("actors"))
            if (uid.equals(value.path("uid").asText()))
                return (ObjectNode) value;
        return null;
    }

    /** 读取原 S813 次数。
     * @param id 角色编号
     * @return 已使用、已购买次数及上次挑战时间
     */
    @Transactional
    public ObjectNode counters(String id) {
        LocalRole role = roles.lockRole(id);
        ObjectNode state = load(role, Instant.now().getEpochSecond());
        save(id, state);
        return json.createObjectNode().put("times", state.path("times").asInt())
                .put("buytime", state.path("buytime").asInt()).put("lasttime", state.path("lasttime").asLong());
    }

    /** 获取岛内可见动态对象。
     * @param role 当前场景角色
     * @return 原 S4 对象
     */
    @Transactional
    public ArrayNode sceneActors(LocalRole role) {
        ArrayNode out = json.createArrayNode();
        if (role.sceneId < 80001 || role.sceneId > 80003)
            return out;
        roles.lockRole(role.id);
        ObjectNode state = load(role, Instant.now().getEpochSecond());
        save(role.id, state);
        int slot = 0;
        for (JsonNode actor : state.path("actors")) {
            if (actor.path("land").asInt() != role.sceneId - 80000)
                continue;
            int[] tile = TILES[slot++];
            if (actor.path("state").asInt() == 3)
                continue;
            JsonNode spec = template(actor);
            ObjectNode obj = out.addObject().put("sid", spec.path("group").asInt())
                    .put("uid", actor.path("uid").asText()).put("face", 0).put("state", 0)
                    .put("level", spec.path("level").asInt()).put("iscommon", true);
            obj.putObject("pos").put("x", tile[0] * 32).put("y", tile[1] * 32);
        }
        return out;
    }

    /** 在同一事务内处理列表、详情、购买、挑战或投球，写入可重放请求回执。
     * @param id 角色编号
     * @param type 原协议编号
     * @param uid 目标实例编号
     * @param ball 道具编号
     * @param receipt 当前连接请求标志
     * @return 协议和推送所需数据
     */
    @Transactional
    public ObjectNode request(String id, int type, String uid, int ball, String receipt) {
        LocalRole role = roles.lockRole(id);
        long now = Instant.now().getEpochSecond();
        ObjectNode state = load(role, now);
        if (type >= 284) {
            ObjectNode prior = read("SELECT reply_json FROM local_eden_receipt WHERE role_id=? AND receipt=?", id,
                    receipt);
            if (prior.has("errno")) {
                prior.put("replayed", true);
                return prior;
            }
        }
        ObjectNode actor = target(state, uid);
        ObjectNode reply = json.createObjectNode().put("errno", 1);
        if (type == 282) {
            reply.put("errno", 0).set("list", state.path("actors"));
        } else if (type == 283) {
            if (actor != null && config.unlocked(role, actor.path("land").asInt()))
                reply.put("errno", 0).put("uid", uid).set("info", actor);
        } else if (type == 284) {
            int bought = state.path("buytime").asInt();
            if (config.open() && role.sceneId >= 80001 && role.sceneId <= 80003 && bought < Integer.MAX_VALUE - 3
                    && inventory.spendMaterials(id, Collections.emptyMap(), 0, PRICES[Math.min(bought, 4)])) {
                state.put("buytime", bought + 1);
                reply.put("errno", 0).put("buytime", bought + 1);
            }
        } else if (actor != null && config.unlocked(role, actor.path("land").asInt())
                && role.sceneId == 80000 + actor.path("land").asInt()) {
            if (type == 285)
                reply = start(role, state, actor, now);
            if (type == 286)
                reply = catchBall(role, actor, ball, now);
        }
        save(id, state);
        if (type >= 284)
            jdbc.update("INSERT INTO local_eden_receipt(role_id,receipt,reply_json) VALUES(?,?,?)", id, receipt,
                    reply.toString());
        return reply;
    }

    /** 创建以真实第一队和底包单怪组成的岛屿战斗。
     * @param role 当前角色
     * @param state 岛屿存档
     * @param actor 目标对象
     * @param now 当前秒
     * @return 开场快照
     */
    private ObjectNode start(LocalRole role, ObjectNode state, ObjectNode actor, long now) {
        ObjectNode reject = json.createObjectNode().put("errno", 2002);
        if (state.has("fight")) {
            return actor.path("uid").asText().equals(state.path("fight").path("uid").asText())
                    ? ((ObjectNode) state.path("fight")).deepCopy()
                    : reject;
        }
        BattleAttempt attempt = normal.findAttempt(role.id);
        if (!config.open() || actor.path("state").asInt() != 0
                || state.path("times").asInt() >= 3L + state.path("buytime").asInt()
                || now - state.path("lasttime").asLong() < 60 || arena.active(role.id) || dojo.active(role.id)
                || guild.active(role.id) || zodiac.active(role.id)
                || attempt != null && "ACTIVE".equals(attempt.status) && now - attempt.startedAt < 1200)
            return reject;
        ArrayNode team = json.createArrayNode();
        RoleCosmeticService.BattleBonuses bonuses = cosmetics.battleBonuses(role.id);
        int activeTeam=normal.choice(role.id,"default");
        for (LocalElf elf : elves.list(role.id))
            if (elf.team == activeTeam && elf.position() > 0)
                team.add(combat.player(role.id, elf, elf.position(), bonuses));
        if (team.isEmpty())
            return reject;
        JsonNode spec = template(actor);
        String opponent = actor.path("uid").asText();
        ObjectNode enemy = spec.path("props").deepCopy();
        combat.fixedActor(enemy, opponent + "-1", opponent, spec.path("enemy").asInt(), spec.path("location").asInt(),
                spec.path("level").asInt(), spec.path("position").asInt(), enemy.path("type1").asInt(),
                enemy.path("type2").asInt(), enemy.path("quality").asInt(), enemy.path("skill").asInt(), 1, 0);
        ObjectNode fight = json.createObjectNode().put("errno", 0)
                .put("seed", ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE)).put("weather", 0)
                .put("uid", opponent);
        fight.set("player", combat.formation(role.id, normal.formationId(role.id), false, team));
        fight.set("monster",
                combat.formation(opponent, spec.path("formation").asInt(), true, json.createArrayNode().add(enemy)));
        state.set("fight", fight);
        state.put("times", state.path("times").asInt() + 1).put("lasttime", now);
        actor.put("state", 1).put("ownerid", role.id).put("ownername", role.name).put("deadline", now + 1200);
        return fight.deepCopy();
    }

    /** 检查当前角色是否有岛内战斗。
     * @param id 角色编号
     * @return 是否已保存有效战斗
     */
    public boolean active(String id) {
        ObjectNode state = read("SELECT state_json FROM local_eden_state WHERE role_id=?", id);
        ObjectNode actor = target(state, state.path("fight").path("uid").asText());
        return actor != null && actor.path("state").asInt() == 1
                && actor.path("deadline").asLong() > Instant.now().getEpochSecond();
    }

    /** 推进原回合或撤退，胜利后开放归属捕捉。
     * @param id 角色编号
     * @param skill 原技能
     * @param targets 当前目标实例列表
     * @param flag 战斗标志
     * @param retreat 是否撤退
     * @return 战斗结论
     */
    @Transactional
    public int advance(String id, int skill, List<?> targets, int flag, boolean retreat) {
        LocalRole role = roles.lockRole(id);
        long now = Instant.now().getEpochSecond();
        ObjectNode state = load(role, now);
        if (!state.has("fight")) {
            save(id, state);
            return -1;
        }
        ObjectNode fight = (ObjectNode) state.path("fight");
        if (skill == 0 && targets.isEmpty() && (flag == 0 || flag == 1)) {
            JsonNode party = fight.path("player").path("pokemon");
            for (int i = 0; i < party.size(); i++) {
                JsonNode member = party.get((fight.path("turn").asInt() + i) % party.size());
                if (member.path("hp").asInt() > 0) {
                    skill = member.path("skill_bar").path(0).asInt();
                    break;
                }
            }
            targets = Collections.singletonList(fight.path("monster").path("pokemon").path(0).path("id").asText());
        }
        int outcome = retreat ? 2 : combat.action(fight, skill, targets, flag);
        if (outcome > 0) {
            ObjectNode actor = target(state, fight.path("uid").asText());
            state.remove("fight");
            if (outcome == 1)
                actor.put("state", 2).put("escapetime", now + 60L * template(actor).path("run").asInt());
            else {
                actor.put("state", 0).put("autotime", now + 60L * template(actor).path("recycling").asInt());
                actor.remove(Arrays.asList("ownerid", "ownername"));
            }
        }
        save(id, state);
        return outcome;
    }

    /** 消耗实际球，按原表对应档位发放整只、碎片或零掉落。
     * @param role 当前角色
     * @param actor 已有归属的目标
     * @param ball 实际道具编号
     * @param now 当前秒
     * @return 捕捉结果及碎片增量
     */
    private ObjectNode catchBall(LocalRole role, ObjectNode actor, int ball, long now) {
        ObjectNode reply = json.createObjectNode().put("errno", 1);
        if (ball < 21070 || ball > 21072 || actor.path("state").asInt() != 2
                || !role.id.equals(actor.path("ownerid").asText())
                || !inventory.spendMaterials(role.id, Collections.singletonMap(ball, 1L), 0, 0))
            return reply;
        JsonNode spec = template(actor);
        int sid = spec.path("location").asInt(), tier = ball - 21070;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean caught = random.nextInt(200) < spec.path("capture").get(tier).asInt();
        reply.put("errno", 0).put("uid", actor.path("uid").asText()).put("ball", ball).put("result", caught);
        if (caught) {
            if (elves.list(role.id).size() < inventory.elfCapacity(role.id))
                elves.grant(role.id, sid);
            else
                inventory.createRewardMail(role.id, UUID.randomUUID().toString(), "神兽岛捕获", "背包已满，请领取捕获的精灵。", now,
                        now + 30L * 86400, Arrays.asList(2, sid, 1));
            actor.put("state", 3).put("deadline", now + 60L * spec.path("flush").asInt());
            actor.remove(Arrays.asList("ownerid", "ownername"));
        } else {
            JsonNode awards = spec.path("award" + (tier + 1));
            int roll = random.nextInt(10000);
            for (int i = 0; i + 3 < awards.size(); i += 4) {
                roll -= awards.get(i + 3).asInt();
                if (roll >= 0)
                    continue;
                int qty = awards.get(i + 2).asInt();
                if (qty > 0) {
                    inventory.grantChip(role.id, awards.get(i + 1).asInt(), qty);
                    reply.put("chip", awards.get(i + 1).asInt()).put("qty", qty);
                }
                break;
            }
        }
        return reply;
    }
}
