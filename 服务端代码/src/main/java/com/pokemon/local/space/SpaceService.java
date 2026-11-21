package com.pokemon.local.space;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.battle.FixedStageCombatService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import com.pokemon.local.dailydup.DailyDungeonService;
import com.pokemon.local.tower.TowerService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import javax.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 究极空间逐关存档、第一阵容战斗与阶段奖励。
 * @author tongruiyuan
 * @since 2026-11-19 19:40
 */
@Service
public class SpaceService {
    private static final String[] ATTR = { "hp_max", "physical_attack", "physical_defense", "special_attack",
            "special_defense", "speed" };
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RoleService roles;
    private final InventoryService inventory;
    private final FixedStageCombatService fixed;
    private final DailyDungeonService daily;
    private final TowerService tower;
    private final JsonNode config;

    /** 加载原关卡、敌人与阶段奖励。
     * @param jdbc 本地数据库
     * @param json 配置及存档解码器
     * @param roles 角色服务
     * @param inventory 奖励入包服务
     * @param fixed 固定对手战斗服务
     * @param daily 其他固定副本的占用状态
     * @param tower 洞穴关卡占用状态
     * @throws IOException 原资源缺失
     */
    public SpaceService(JdbcTemplate jdbc, ObjectMapper json, RoleService roles, InventoryService inventory,
            FixedStageCombatService fixed, DailyDungeonService daily,
            TowerService tower) throws IOException {
        this.jdbc = jdbc;
        this.json = json;
        this.roles = roles;
        this.inventory = inventory;
        this.fixed = fixed;
        this.daily = daily;
        this.tower = tower;
        try (InputStream in = new ClassPathResource("space/stages.json").getInputStream()) {
            config = json.readTree(in);
        }
    }

    /** 初始化本地关卡进度表。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("space/schema.sql")).execute(jdbc.getDataSource());
    }

    /** 读取角色存档并按北京时间自然日恢复挑战次数。
     * @param id 角色编号
     * @return 当前状态
     */
    private ObjectNode state(String id) {
        List<String> rows = jdbc.queryForList("SELECT state_json FROM local_space_progress WHERE role_id=?",
                String.class, id);
        ObjectNode s = json.createObjectNode().put("mission_id", 10011);
        if (!rows.isEmpty())
            try {
                s = (ObjectNode) json.readTree(rows.get(0));
            } catch (IOException e) {
                throw new IllegalStateException("究极空间存档异常", e);
            }
        long now = Instant.now().getEpochSecond(), day = (now + 28800) / 86400;
        if (s.path("day").asLong() != day) {
            s.put("day", day).put("times", 10);
        }
        if (s.path("fight").path("expiry").asLong() < now)
            s.remove("fight");
        return s;
    }

    /** 保存当前战斗与关卡。
     * @param id 角色编号
     * @param s 进度
     */
    private void save(String id, ObjectNode s) {
        jdbc.update("MERGE INTO local_space_progress (role_id,mission_id,state_json) KEY(role_id) VALUES(?,?,?)", id,
                s.path("mission_id").asInt(), s.toString());
    }

    /** 查询角色是否仍在本玩法战斗。
     * @param id 角色编号
     * @return 是否占用
     */
    public boolean active(String id) {
        return state(id).has("fight");
    }

    /** 生成原阶段奖励表。
     * @param s 角色进度
     * @return 各档领取状态
     */
    private ObjectNode rewards(ObjectNode s) {
        int current = s.path("mission_id").asInt();
        ObjectNode result = json.createObjectNode().put("mission_id", current);
        ArrayNode entries = result.putArray("stage_reward");
        config.path("milestones").fieldNames()
                .forEachRemaining(k -> entries.addObject().put("mission_id", Integer.parseInt(k)).put("status",
                        s.path("claimed").has(k) ? 2 : Integer.parseInt(k) < current ? 1 : 0));
        return result;
    }

    /** 查询原面板及登录状态。
     * @param id 角色编号
     * @return 原面板数据
     */
    public ObjectNode snapshot(String id) {
        ObjectNode s = state(id);
        return rewards(s).put("times", s.path("times").asInt()).put("refresh_time",
                (s.path("day").asLong() + 1) * 86400 - 28800);
    }

    /** 发放实际道具并记录背包增量，背包满由道具服务生成邮件。
     * @param id 角色编号
     * @param receipt 发奖键
     * @param items 道具数量
     * @return 实际入包增量
     */
    private ObjectNode grant(String id, String receipt, Map<Integer, Long> items) {
        ObjectNode adds = json.createObjectNode();
        ArrayNode sent = adds.putArray("items");
        inventory.grantItemsOrMail(id, receipt, items)
                .forEach((item, n) -> sent.addObject().put("id", item).put("n", n));
        return adds;
    }

    /** 查询或领取已通过关卡的阶段奖励。
     * @param id 角色编号
     * @param mission 领取关卡，零表示只查询
     * @return 原领取结果与真实到账
     */
    @Transactional
    public ObjectNode milestones(String id, int mission) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = state(id);
        String k = Integer.toString(mission);
        if (role.level < 50)
            return json.createObjectNode().put("errno", 1);
        if (mission == 0)
            return rewards(s).put("errno", 0);
        JsonNode award = config.path("milestones").path(k);
        if (!award.isArray() || s.path("mission_id").asInt() <= mission || s.path("claimed").has(k))
            return json.createObjectNode().put("errno", 1);
        Map<Integer, Long> items = new LinkedHashMap<>();
        for (int i = 0; i < award.size(); i += 3)
            items.merge(award.get(i + 1).asInt(), award.get(i + 2).asLong(), Long::sum);
        ObjectNode adds = grant(id, "space:milestone:" + mission, items);
        s.with("claimed").put(k, true);
        save(id, s);
        ObjectNode result = rewards(s).put("errno", 0);
        result.set("adds", adds);
        return result;
    }

    /** 读取本服角色最高通关排行。
     * @param realm 当前区服
     * @return 原协议排行榜
     */
    public ObjectNode rank(int realm) {
        ObjectNode r = json.createObjectNode().put("errno", 0);
        ArrayNode board = r.putArray("rank");
        for (String id : jdbc.queryForList(
                "SELECT role_id FROM local_space_progress WHERE mission_id>10011 ORDER BY mission_id DESC,role_id",
                String.class)) {
            LocalRole role = roles.findById(id).orElse(null);
            if (role == null || role.realmId != realm)
                continue;
            int next = state(id).path("mission_id").asInt(), last = next % 10 == 1 ? next - 3 : next - 1;
            board.addObject().put("uid", id).put("icon", role.avatar).put("name", role.name).put("svrid", realm)
                    .put("sex", role.sex).put("mission_id", last).put("rank", board.size());
            if (board.size() >= 100)
                break;
        }
        return r;
    }

    /** 创建当前关的实际六人队伍。
     * @param mission 原关卡编号
     * @return 原阵容与本地等级面板
     */
    private ObjectNode encounter(int mission) {
        JsonNode stage = config.path("stages").path(Integer.toString(mission));
        int floor = (mission - 10000) / 10;
        int level = Math.min(200, 50 + (floor - 1) / 3);
        ObjectNode cfg = json.createObjectNode().put("level", level).put("formation", stage.get(2).asInt());
        ArrayNode actors = cfg.putArray("actors");
        for (JsonNode row : stage.get(5)) {
            int sprite = row.get(1).asInt();
            JsonNode base = config.path("species").path(Integer.toString(sprite));
            ObjectNode actor = actors.addObject().put("enemy", row.get(0).asInt()).put("sprite", sprite)
                    .put("position", row.get(2).asInt()).put("type1", base.get(6).asInt())
                    .put("type2", base.get(7).asInt()).put("quality", base.get(8).asInt())
                    .put("skill", base.get(9).asInt());
            for (int i = 0; i < ATTR.length; i++) {
                int amount = 2 * base.get(i).asInt() * level / 100 + (i == 0 ? level + 10 : 5);
                actor.put(ATTR[i], amount);
            }
        }
        return cfg;
    }

    /** 重连保留战斗，胜利后下一次发起必须使用新的请求。
     * @param id 角色编号
     * @param receipt 当前连接及请求编号
     * @return 原战斗开场或拒绝
     */
    @Transactional
    public ObjectNode start(String id, String receipt) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = state(id);
        if (s.has("fight"))
            return (ObjectNode) s.path("fight").deepCopy();
        int mission = s.path("mission_id").asInt();
        JsonNode stage = config.path("stages").path(Integer.toString(mission));
        if (daily.active(id) || daily.otherFightActive(id) || tower.active(id) || role.level < 50
                || stage.isMissingNode() || s.path("times").asInt() < 1 || s.path("last").asText().equals(receipt))
            return json.createObjectNode().put("errno", 1);
        ObjectNode battle = fixed.start(role, mission, encounter(mission));
        if (battle.path("errno").asInt() != 0)
            return battle;
        double factor = 1 - stage.get(3).asDouble() / 100;
        for (JsonNode ally : battle.path("player").path("pokemon")) {
            ObjectNode actor = (ObjectNode) ally;
            for (String key : ATTR)
                actor.put(key, Math.max(1, (int) (actor.path(key).asInt() * factor)));
            actor.put("hp", actor.path("hp_max").asInt());
        }
        s.put("last", receipt).set("fight", battle);
        save(id, s);
        return battle;
    }

    /** 处理战斗动作、败北次数及真实首通奖励。
     * @param id 角色编号
     * @param skill 技能
     * @param targets 目标
     * @param flag 客户端操作标记
     * @param retreat 是否主动退出
     * @return 结算结果及资产增量
     */
    @Transactional
    public ObjectNode advance(String id, int skill, List<?> targets, int flag, boolean retreat) {
        roles.lockRole(id);
        ObjectNode s = state(id), r = json.createObjectNode();
        if (!s.has("fight"))
            return r;
        ObjectNode fight = (ObjectNode) s.get("fight");
        int mission = fight.path("stage").asInt(), floor = (mission - 10000) / 10, round = fight.path("round").asInt(),
                before = alive(fight);
        int outcome = fixed.advance(fight, skill, targets, flag, retreat);
        if (outcome < 0)
            return r;
        if (alive(fight) < before) {
            JsonNode enemies = fight.path("monster").path("pokemon");
            for (int i = 0; i < enemies.size(); i++) {
                ObjectNode enemy = (ObjectNode) enemies.get((round + i) % enemies.size());
                if (enemy.path("hp").asInt() <= 0)
                    continue;
                String top = ATTR[1];
                for (int n = 2; n < ATTR.length; n++)
                    if (enemy.path(ATTR[n]).asInt() > enemy.path(top).asInt())
                        top = ATTR[n];
                enemy.put(top, (int) (enemy.path(top).asLong() * (105 + (floor - 1) / 50) / 100));
                break;
            }
        }
        r.put("outcome", outcome);
        if (outcome == 1) {
            s.put("mission_id", config.path("stages").path(Integer.toString(mission)).get(0).asInt());
            r.set("adds", grant(id, "space:clear:" + mission, Collections.singletonMap(20027, 1L + (floor - 1) / 50)));
        } else if (outcome == 2)
            s.put("times", s.path("times").asInt() - 1);
        if (outcome != 0)
            s.remove("fight");
        save(id, s);
        return r;
    }

    /** 统计第一队存活个数。
     * @param fight 当前战斗
     * @return 存活数量
     */
    private int alive(ObjectNode fight) {
        int n = 0;
        for (JsonNode elf : fight.path("player").path("pokemon"))
            if (elf.path("hp").asInt() > 0)
                n++;
        return n;
    }
}