package com.pokemon.offline.tower;
import com.pokemon.offline.activity.CarnivalProgressMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.arena.ArenaService;
import com.pokemon.offline.arena.ZodiacService;
import com.pokemon.offline.battle.BattleMapper;
import com.pokemon.offline.battle.FixedStageCombatService;
import com.pokemon.offline.battle.api.entity.BattleAttempt;
import com.pokemon.offline.dailydup.DailyDungeonService;
import com.pokemon.offline.dojo.DojoCombatService;
import com.pokemon.offline.eden.EdenEncounterService;
import com.pokemon.offline.guild.GuildEncounterService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.payment.GamePaymentService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 洞穴闯关的楼层、固定敌队、特性和首通存档。
 * @author tongruiyuan
 * @since 2026-11-19 19:18
 */
@Service
public class TowerService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode cfg;
    private final RoleService roles;
    private final GamePaymentService payments;
    private final InventoryService inventory;
    private final FixedStageCombatService fixed;
    private final BattleMapper battles;
    private final DailyDungeonService daily;
    private final ArenaService arena;
    private final GuildEncounterService guild;
    private final EdenEncounterService eden;
    private final ZodiacService zodiac;
    private final DojoCombatService dojo;
    private final CarnivalProgressMapper progress;

    /** 加载洞穴及相关事务服务。
     * @param jdbc 本地数据库
     * @param json 编队解码
     * @param roles 角色存档
     * @param payments 首通角色当前 VIP
     * @param inventory 道具和扣钻
     * @param fixed 固定关卡战斗
     * @param battles 普通副本
     * @param daily 每日副本
     * @param arena 竞技
     * @param guild 公会战斗
     * @param eden 神兽岛
     * @param zodiac 十二宫
     * @param dojo 道馆
     * @param progress 已成功发起的洞穴探险日常计数
     * @throws IOException 资源不可读
     */
    public TowerService(JdbcTemplate jdbc, ObjectMapper json, RoleService roles, InventoryService inventory, GamePaymentService payments,
            FixedStageCombatService fixed, BattleMapper battles, DailyDungeonService daily, ArenaService arena,
            GuildEncounterService guild, EdenEncounterService eden, ZodiacService zodiac, DojoCombatService dojo,
            CarnivalProgressMapper progress)
            throws IOException {
        this.jdbc = jdbc;
        this.json = json;
        this.roles = roles;
        this.payments = payments;
        this.inventory = inventory;
        this.fixed = fixed;
        this.battles = battles;
        this.daily = daily;
        this.arena = arena;
        this.guild = guild;
        this.eden = eden;
        this.zodiac = zodiac;
        this.dojo = dojo;
        this.progress = progress;
        try (InputStream in = new ClassPathResource("tower/stages.json").getInputStream()) {
            cfg = json.readTree(in);
        }
    }

    /** 建立洞穴存档、首通及回执表。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("tower/schema.sql")).execute(jdbc.getDataSource());
        for (int n = 1; n <= 120; n++)
            jdbc.update(
                    "INSERT INTO local_tower_layer(layer,cleared) SELECT ?,0 WHERE NOT EXISTS (SELECT 1 FROM local_tower_layer WHERE layer=?)",
                    n, n);
    }

    /** 读取存档或请求回执。
     * @param sql 查询语句
     * @param params 查询参数
     * @return 已存对象或空对象
     */
    private ObjectNode read(String sql, Object... params) {
        List<String> rows = jdbc.queryForList(sql, String.class, params);
        if (rows.isEmpty())
            return json.createObjectNode();
        try {
            return (ObjectNode) json.readTree(rows.get(0));
        } catch (IOException e) {
            throw new IllegalStateException("洞穴存档损坏", e);
        }
    }

    /** 查询原楼层。
     * @param layer 楼层
     * @return 原组及地图配置
     */
    private JsonNode stage(int layer) {
        return cfg.path("stages").path(String.valueOf(layer));
    }

    /** 校验当前洞穴场景。
     * @param scene 场景号
     * @return 是否原洞穴地图
     */
    public static boolean isScene(int scene) {
        return scene >= 11001 && scene <= 11010;
    }

    /** 提交持锁状态。
     * @param id 角色编号
     * @param state 存档
     */
    private void save(String id, ObjectNode state) {
        jdbc.update("MERGE INTO local_tower_state(role_id,state_json) KEY(role_id) VALUES(?,?)", id, state.toString());
    }

    /** 按当前自然日与四小时窗口恢复进度。
     * @param id 角色编号
     * @return 当前状态
     */
    private ObjectNode state(String id) {
        ObjectNode s = read("SELECT state_json FROM local_tower_state WHERE role_id=?", id);
        long now = Instant.now().getEpochSecond(),
                day = Instant.ofEpochSecond(now).atOffset(ZoneOffset.ofHours(8)).toLocalDate().toEpochDay();
        if (!s.has("mission"))
            s.put("mission", 1);
        if (s.path("day").asLong(-1) != day)
            s.put("day", day).put("times", 0).put("renovation", 0);
        if (s.has("fight") && s.path("fight").path("expiry").asLong() <= now) {
            s.remove("fight");
            s.put("times", Math.min(3, s.path("times").asInt() + 1));
        }
        int n = Math.min(120, s.path("mission").asInt());
        if (!s.has("fight") && (s.path("bufftime").asLong() <= now || s.path("buffLayer").asInt() != n))
            roll(s, n, now);
        return s;
    }

    /** 按两方原候选相对权重抽取可实际计算的常规属性特性。
     * @param s 进度
     * @param layer 楼层
     * @param now 当前秒
     */
    private void roll(ObjectNode s, int layer, long now) {
        JsonNode spec = stage(layer);
        s.put("buffLayer", layer).put("bufftime", (now / 14400 + 1) * 14400);
        for (int side = 0; side < 2; side++) {
            ArrayNode out = s.putArray(side == 0 ? "ally" : "foe");
            List<JsonNode> pool = new ArrayList<>();
            spec.path("buffs").path(side).forEach(pool::add);
            for (int n = 0; n < spec.path("num").asInt() && !pool.isEmpty(); n++) {
                int roll = ThreadLocalRandom.current().nextInt(pool.stream().mapToInt(x -> x.get(1).asInt()).sum());
                int i = 0;
                while (roll >= pool.get(i).get(1).asInt())
                    roll -= pool.get(i++).get(1).asInt();
                out.add(pool.remove(i).get(0).asInt());
            }
        }
    }

    /** 在角色事务内进入或离开真实楼层。
     * @param role 当前角色
     * @param leave 是否退出
     * @return 传送目的场景，零表示拒绝
     */
    @Transactional
    public int travel(LocalRole role, boolean leave) {
        roles.lockRole(role.id);
        ObjectNode s = state(role.id);
        if (s.has("fight"))
            return 0;
        if (leave) {
            s.put("current", 0);
            save(role.id, s);
            return 71002;
        }
        int next = s.path("mission").asInt(), old = s.path("current").asInt();
        if (role.level < 14 || next > 120 && !s.path("box").asBoolean() || isScene(role.sceneId) && old >= next)
            return 0;
        int target = s.path("box").asBoolean() ? next - 1 : next;
        s.put("current", target);
        save(role.id, s);
        return stage(target).path("map").get(0).asInt();
    }

    /** 返回当前角色的洞穴信息及己方常规属性。
     * @param id 角色编号
     * @return 进度快照
     */
    @Transactional
    public ObjectNode snapshot(String id) {
        roles.lockRole(id);
        ObjectNode s = state(id);
        save(id, s);
        return s;
    }

    /** 给地图对象分配角色、楼层与类别范围内稳定的编号。
     * @param id 角色编号
     * @param layer 楼层
     * @param box 是否宝箱
     * @return 原场景对象编号
     */
    private String uid(String id, int layer, boolean box) {
        return box ? "towerbox" + stage(layer).path("box").path(0).asText() + "-" + id
                : "tower-boss-" + layer + "-" + id;
    }

    /** 构造 S4 中的单个守卫或待取宝箱。
     * @param role 当前角色
     * @param box 是否宝箱
     * @return 地图对象列表
     */
    @Transactional
    public ArrayNode actors(LocalRole role, boolean box) {
        ArrayNode out = json.createArrayNode();
        if (!isScene(role.sceneId))
            return out;
        roles.lockRole(role.id);
        ObjectNode s = state(role.id);
        int n = s.path("current").asInt(), mission = s.path("mission").asInt();
        JsonNode spec = stage(n);
        if (!spec.isObject() || spec.path("map").get(0).asInt() != role.sceneId)
            return out;
        JsonNode pos;
        int sid;
        if (box) {
            if (!s.path("box").asBoolean() || n + 1 != mission)
                return out;
            pos = spec.path("box");
            if (pos.size() != 3)
                return out;
            sid = pos.get(0).asInt();
        } else {
            if (n != mission)
                return out;
            pos = spec.path("pos");
            sid = spec.path("group").asInt();
        }
        ObjectNode actor = out.addObject().put("uid", uid(role.id, n, box)).put("sid", sid).put("state", 0)
                .put("iscommon", false);
        actor.putObject("pos").put("x", 32 * pos.get(box ? 1 : 0).asInt()).put("y", 32 * pos.get(box ? 2 : 1).asInt());
        return out;
    }

    /** 根据本机已留存首通的真实人数计算原排名钻石。
     * @param rank 当前名次
     * @return 原排名钻石
     */
    private int diamonds(int rank) {
        return cfg.path("rank").path(Math.min(10, rank <= 9 ? rank - 1 : rank <= 100 ? 9 : 10)).asInt();
    }

    /** 返回原 C120 需要的本层预览和本机首通者。
     * @param id 角色编号
     * @param layer 目标楼层
     * @return 原预览结果
     */
    @Transactional
    public ObjectNode preview(String id, int layer) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = state(id);
        if (role.level < 14 || layer < 1 || layer > 120 || layer > s.path("mission").asInt())
            return error();
        save(id, s);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM local_tower_clear WHERE layer=?", Integer.class,
                layer);
        List<String> first = jdbc.queryForList(
                "SELECT role_id FROM local_tower_clear WHERE layer=? ORDER BY finished,role_id", String.class, layer);
        ObjectNode out = json.createObjectNode().put("errno", 0).put("total", count).put("mission", layer)
                .put("power", stage(layer).path("power").asInt()).put("redpoint", false);
        out.set("monbuff", s.path("foe"));
        out.putObject("award").putArray("items").addObject().put("id", 21063).put("n", 1 + layer / 10);
        if (!first.isEmpty())
            roles.findById(first.get(0)).ifPresent(r -> out.putObject("info").put("id", r.id).put("name", r.name)
                    .put("level", r.level).put("sex", r.sex).put("vip",payments.vip(r.id).level));
        return out;
    }

    /** 错误回执。
     * @return 普通拒绝回执
     */
    private ObjectNode error() {
        return json.createObjectNode().put("errno", 1);
    }

    /** 检查有无待结算的洞穴战斗。
     * @param id 角色编号
     * @return 是否占用战斗入口
     */
    public boolean active(String id) {
        JsonNode fight = read("SELECT state_json FROM local_tower_state WHERE role_id=?", id).path("fight");
        return fight.path("expiry").asLong() > Instant.now().getEpochSecond();
    }

    /** 从当前楼层实际地图对象进入原战斗。
     * @param id 角色编号
     * @param uid 点击的对象编号
     * @param team 玩家选择的队伍
     * @return 原战斗开场
     */
    @Transactional
    public ObjectNode start(String id, String uid, int team) {
        LocalRole r = roles.lockRole(id);
        ObjectNode s = state(id);
        int n = s.path("current").asInt();
        if ((team < 1 || team > 3 || team > r.stage + 1) || r.level < 14 || !isScene(r.sceneId) || n != s.path("mission").asInt()
                || !uid(id, n, false).equals(uid))
            return error();
        if (s.has("fight"))
            return (ObjectNode) s.path("fight").deepCopy();
        BattleAttempt current = battles.findAttempt(id);
        if (s.path("times").asInt() >= 3 || daily.active(id) || arena.active(id) || guild.active(id) || eden.active(id)
                || zodiac.active(id) || dojo.active(id) || current != null && "ACTIVE".equals(current.status)
                        && Instant.now().getEpochSecond() - current.startedAt < 1200)
            return error();
        ObjectNode fight = fixed.start(r, n, stage(n), team);
        if (fight.path("errno").asInt() != 0)
            return fight;
        affect(fight.path("player"), s.path("ally"));
        affect(fight.path("monster"), s.path("foe"));
        s.set("fight", fight);
        save(id, s);
        progress.record(id,40011,1);
        return fight;
    }

    /** 将已抽到的属性效果应用到对应阵营的实际演员。
     * @param formation 战斗阵营
     * @param selected 已保存的特性
     */
    private void affect(JsonNode formation, JsonNode selected) {
        for (JsonNode b : selected) {
            JsonNode buff = cfg.path("buffs").path(b.asText());
            String attr = buff.get(0).asText();
            int rate = buff.get(1).asInt();
            for (JsonNode value : formation.path("pokemon")) {
                ObjectNode actor = (ObjectNode) value;
                actor.put(attr, Math.max(1, Math.round(actor.path(attr).asInt() * (1 + rate / 1000.0))));
                if ("hp_max".equals(attr))
                    actor.put("hp", actor.path("hp_max").asInt());
            }
        }
    }

    /** 结算正式战斗，首次通关保存时间并同步钱包。
     * @param id 角色编号
     * @param skill 技能
     * @param target 目标编号
     * @param flag 原动作标记
     * @param leave 是否撤退
     * @return 当前回合结果
     */
    @Transactional
    public ObjectNode advance(String id, int skill, List<?> target, int flag, boolean leave) {
        roles.lockRole(id);
        ObjectNode s = state(id);
        if (!s.has("fight"))
            return error();
        ObjectNode fight = (ObjectNode) s.path("fight");
        int n = fight.path("stage").asInt();
        int result = fixed.advance(fight, skill, target, flag, leave);
        ObjectNode out = json.createObjectNode().put("errno", 0).put("outcome", result).put("layer", n);
        if (result > 0)
            s.remove("fight");
        if (result == 2)
            s.put("times", Math.min(3, s.path("times").asInt() + 1));
        if (result == 1) {
            jdbc.update("INSERT INTO local_tower_clear(role_id,layer,finished) VALUES(?,?,?)", id, n,
                    Instant.now().toEpochMilli());
            jdbc.update("UPDATE local_tower_layer SET cleared=cleared+1 WHERE layer=?", n);
            Integer rank = jdbc.queryForObject("SELECT cleared FROM local_tower_layer WHERE layer=?", Integer.class, n);
            int reward = diamonds(rank);
            roles.addCurrency(id, 0, reward, 0);
            ObjectNode adds = out.putObject("adds").put("diamonds", reward);
            long quantity = 1 + n / 10;
            Long received = inventory.grantItemsOrMail(id, "tower:win:" + n, Collections.singletonMap(21063, quantity))
                    .get(21063);
            if (received != null)
                adds.putArray("items").addObject().put("id", 21063).put("n", received);
            s.put("mission", n + 1).put("box", stage(n).path("box").size() == 3);
            out.put("uid", uid(id, n, false));
        }
        save(id, s);
        return out;
    }

    /** 领取当前已通关层的地图宝箱。
     * @param id 角色编号
     * @param uid 被点击的实际事件编号
     * @return 领取状态及真实到账
     */
    @Transactional
    public ObjectNode box(String id, String uid) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = state(id);
        int n = s.path("current").asInt();
        if (!isScene(role.sceneId) || !s.path("box").asBoolean() || n + 1 != s.path("mission").asInt()
                || !uid(id, n, true).equals(uid))
            return error();
        long qty = 1 + (n - 5) / 30;
        Long received = inventory.grantItemsOrMail(id, "tower:box:" + n, Collections.singletonMap(20027, qty))
                .get(20027);
        ObjectNode out = json.createObjectNode().put("errno", 0);
        ObjectNode adds = out.putObject("adds");
        if (received != null)
            adds.putArray("items").addObject().put("id", 20027).put("n", received);
        s.put("box", false);
        save(id, s);
        return out;
    }

    /** 刷新当前层的实际特性并记录免费次数或付费请求回执。
     * @param id 角色编号
     * @param receipt 原连接请求标志
     * @return 原刷新结果
     */
    @Transactional
    public ObjectNode refresh(String id, String receipt) {
        LocalRole r = roles.lockRole(id);
        ObjectNode s = state(id);
        ObjectNode prior = read("SELECT reply_json FROM local_tower_receipt WHERE role_id=? AND receipt=?", id,
                receipt);
        if (prior.has("errno"))
            return prior;
        int n = s.path("current").asInt(), used = s.path("renovation").asInt();
        if (!isScene(r.sceneId) || n != s.path("mission").asInt() || s.has("fight")
                || used >= 3 && !inventory.spendMaterials(id, Collections.emptyMap(), 0, 500))
            return error();
        roll(s, n, Instant.now().getEpochSecond());
        s.put("renovation", used + 1);
        save(id, s);
        ObjectNode out = json.createObjectNode().put("errno", 0).put("renovation", used + 1);
        out.set("monbuff", s.path("foe"));
        jdbc.update("INSERT INTO local_tower_receipt(role_id,receipt,reply_json) VALUES(?,?,?)", id, receipt,
                out.toString());
        return out;
    }
}
