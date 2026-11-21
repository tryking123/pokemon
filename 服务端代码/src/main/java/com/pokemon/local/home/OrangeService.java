package com.pokemon.local.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.battle.FixedStageCombatService;
import com.pokemon.local.dailydup.DailyDungeonService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import com.pokemon.local.space.SpaceService;
import com.pokemon.local.tower.TowerService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 橘子群岛第一岛的守卫、传送、队伍生命与通关记录。
 * @author tongruiyuan
 * @since 2026-11-19 20:05
 */
@Service
public class OrangeService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RoleService roles;
    private final HomeService home;
    private final FixedStageCombatService fixed;
    private final InventoryService inventory;
    private final DailyDungeonService daily;
    private final TowerService tower;
    private final SpaceService space;
    private final JsonNode cfg;

    /** 加载原怪物及地图。
     * @param jdbc 角色存档数据库
     * @param json 配置解析
     * @param roles 角色与位置
     * @param home 家园心情钱包
     * @param fixed 原阵容战斗服务
     * @param inventory 原掉落入包
     * @param daily 每日副本及其他战斗占用
     * @param tower 洞穴战斗占用
     * @param space 究极空间战斗占用
     * @throws IOException 原资源丢失或损坏
     */
    public OrangeService(JdbcTemplate jdbc, ObjectMapper json, RoleService roles, HomeService home,
            FixedStageCombatService fixed, InventoryService inventory, DailyDungeonService daily, TowerService tower,
            SpaceService space) throws IOException {
        this.jdbc = jdbc;
        this.json = json;
        this.roles = roles;
        this.home = home;
        this.fixed = fixed;
        this.inventory = inventory;
        this.daily = daily;
        this.tower = tower;
        this.space = space;
        try (InputStream in = new ClassPathResource("home/orange.json").getInputStream()) {
            cfg = json.readTree(in);
        }
    }

    /** 从饲育屋核心按当前级展示的金币及材料升级。
     * @param id 角色编号
     * @param receipt 升级请求编号
     * @return 下一级、重放标记或错误
     */
    @Transactional
    public ObjectNode upgrade(String id, String receipt) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = state(id), out = json.createObjectNode().put("errno", 1);
        int level = home.level(id);
        String oldLevel = home.reply(id, "upgrade:" + receipt);
        if (oldLevel != null)
            return out.put("errno", 0).put("nextlevel", Integer.parseInt(oldLevel)).put("replayed", true);
        if (receipt.equals(s.path("upgradeReceipt").asText()))
            return out.put("errno", 0).put("nextlevel", s.path("upgradeLevel").asInt()).put("replayed", true);
        if (role.sceneId != 80012 || level < 1 || level >= 20)
            return out;
        JsonNode cost = cfg.path("breeroom").path(Integer.toString(level));
        if (!inventory.spendMaterials(id, cost.get(4).asInt(), cost.get(5).asLong(), cost.get(2).asLong(), 0))
            return out;
        jdbc.update("UPDATE local_home_state SET level=? WHERE role_id=?", level + 1, id);
        home.record(id, "upgrade:" + receipt, Integer.toString(level + 1));
        s.put("upgradeReceipt", receipt).put("upgradeLevel", level + 1);
        save(id, s);
        return out.put("errno", 0).put("nextlevel", level + 1);
    }

    /** 读取岛屿存档。
     * @param id 角色编号
     * @return 探索与历史进度
     */
    private ObjectNode state(String id) {
        List<String> rows = jdbc.queryForList("SELECT state_json FROM local_orange_state WHERE role_id=?", String.class,
                id);
        if (rows.isEmpty())
            return json.createObjectNode();
        try {
            return (ObjectNode) json.readTree(rows.get(0));
        } catch (IOException e) {
            throw new IllegalStateException("橘子群岛存档损坏", e);
        }
    }

    /** 保存状态。
     * @param id 角色编号
     * @param s 当前探索记录
     */
    private void save(String id, ObjectNode s) {
        jdbc.update("MERGE INTO local_orange_state(role_id,state_json) KEY(role_id) VALUES(?,?)", id, s.toString());
    }

    /** 判断是否为已接入的第一岛地图。
     * @param scene 场景编号
     * @return 是否为第一岛
     */
    public static boolean scene(int scene) {
        return scene >= 81001 && scene <= 81705 && scene % 100 >= 1 && scene % 100 <= 5;
    }

    /** 从原怪物权重池中抽出守卫，固定首领房沿用原配置。
     * @param s 当前角色探索存档
     * @param scene 实际目标场景
     * @param x 进入横坐标格
     * @param y 进入纵坐标格
     */
    private void room(ObjectNode s, int scene, int x, int y) {
        s.put("room", scene);
        ArrayNode actors = s.putArray("actors");
        JsonNode data = cfg.path("rooms").path(Integer.toString(scene)), pool = data.path("events");
        int n = data.path("count").asInt();
        for (int i = 0; i < n; i++) {
            int total = 0, template = 0;
            for (int j = 0; j < pool.size(); j += 3) {
                int weight = pool.get(j).asInt();
                total += weight;
                if (ThreadLocalRandom.current().nextInt(total) < weight)
                    template = pool.get(j + 2).asInt();
            }
            place(actors, template, x, y, i);
        }
        JsonNode fixed = data.path("fixed");
        for (int i = 0; i < fixed.size(); i += 3)
            for (int k = 0; k < fixed.get(i + 2).asInt(); k++)
                place(actors, fixed.get(i + 1).asInt(), x, y, actors.size());
    }

    /** 将原事件或怪物实例放到独立位置。
     * @param actors 房间对象
     * @param template 原模板
     * @param x 进入点横坐标
     * @param y 进入点纵坐标
     * @param slot 生成序号
     */
    private void place(ArrayNode actors, int template, int x, int y, int slot) {
        ObjectNode actor = actors.addObject().put("uid", UUID.randomUUID().toString()).put("sid", template)
                .put("state", 0).put("iscommon", false);
        actor.putObject("pos").put("x", (x + (slot % 2 == 0 ? -1 : 1)) * 32).put("y", Math.min(39, y + 1 + slot) * 32);
    }

    /** 查询场景内仍可挑战的真实敌人。
     * @param role 当前角色
     * @return 原地图单位
     */
    public JsonNode actors(LocalRole role) {
        return scene(role.sceneId) ? state(role.id).path("actors") : json.createArrayNode();
    }

    /** 恢复三队生命及已完成通关。
     * @param id 角色编号
     * @return 原 S827
     */
    public ObjectNode snapshot(String id) {
        ObjectNode s = state(id), r = json.createObjectNode();
        JsonNode a = s.path("actors");
        r.put("current", s.path("total").asInt() - a.size()).put("total", s.path("total").asInt()).put("finished",
                s.path("done").asBoolean());
        ArrayNode hp = r.putArray("teamhp");
        s.path("hp").elements().forEachRemaining(hp::add);
        ArrayNode history = r.putArray("history");
        if (s.path("cleared").asBoolean())
            history.add(1);
        s.path("history").fieldNames().forEachRemaining(k -> {
            if (!"1".equals(k) || !s.path("cleared").asBoolean())
                history.add(Integer.parseInt(k));
        });
        return r;
    }

    /** 在真实家园接受挑战或扫荡，仅允许有相同岛屿通关记录者扫荡。
     * @param id 角色编号
     * @param island 岛屿编号
     * @param sweep 是否扫荡
     * @param key 本连接操作编号
     * @return 应答及到账
     */
    @Transactional
    public ObjectNode enter(String id, int island, boolean sweep, String key) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = state(id), out = json.createObjectNode().put("errno", 1);
        String oldIsland = home.reply(id, "orange:" + key);
        if (oldIsland != null)
            return out.put("errno", 0).put("idx", Integer.parseInt(oldIsland)).put("replayed", true);
        if (s.path("last").asText().equals(key))
            return out.put("errno", 0).put("idx", island).put("replayed", true);
        String islandKey = Integer.toString(island);
        JsonNode islandCfg = cfg.path("islands").path(islandKey);
        boolean cleared = s.path("history").has(islandKey) || island == 1 && s.path("cleared").asBoolean();
        if (islandCfg.isMissingNode() || role.sceneId != 80011 || home.level(id) < islandCfg.path("level").asInt()
                || sweep && !cleared || !home.spendMood(id, islandCfg.path("cost").asInt()))
            return out;
        s.put("island", island);
        s.put("last", key);
        if (sweep) {
            out.set("adds", reward(id, island, "orange:sweep:" + key));
        } else {
            s.remove(Arrays.asList("hp", "fight", "target", "done", "buffs", "deck"));
            JsonNode born = islandCfg.path("born");
            int scene = born.get(0).asInt(), x = born.get(1).asInt(), y = born.get(2).asInt();
            room(s, scene, x, y);
            s.put("total", s.path("actors").size());
            roles.updatePosition(id, scene, x * 32, y * 32, null, null, null);
        }
        home.record(id, "orange:" + key, Integer.toString(island));
        save(id, s);
        return out.put("errno", 0).put("idx", island);
    }

    /** 发放原第一岛通关材料。
     * @param id 角色编号
     * @param island 原岛屿编号
     * @param key 奖励回执
     * @return 实际入包道具
     */
    private ObjectNode reward(String id, int island, String key) {
        Map<Integer, Long> items = new LinkedHashMap<>();
        JsonNode arr = cfg.path("islands").path(Integer.toString(island)).path("clear");
        for (int i = 0; i < arr.size(); i += 3)
            items.put(arr.get(i + 1).asInt(), arr.get(i + 2).asLong());
        ObjectNode out = json.createObjectNode();
        ArrayNode actual = out.putArray("items");
        inventory.grantItemsOrMail(id, key, items).forEach((item, n) -> actual.addObject().put("id", item).put("n", n));
        return out;
    }

    /** 使用原入口坐标、传送权重和同房守卫状态移动。
     * @param role 当前角色
     * @param portal 点击的传送阵
     * @return 移动后的角色，拒绝时为空
     */
    @Transactional
    public LocalRole travel(LocalRole role, long portal) {
        if (!scene(role.sceneId))
            return null;
        roles.lockRole(role.id);
        ObjectNode s = state(role.id);
        if (s.has("fight"))
            return null;
        if (portal == 230014) {
            s.remove(Arrays.asList("actors", "room", "total", "hp", "target", "done", "buffs", "deck"));
            save(role.id, s);
            return roles.updatePosition(role.id, 80011, 15 * 32, 49 * 16, null, null, null);
        }
        if (s.path("actors").size() != 0)
            return null;
        JsonNode doors = cfg.path("rooms").path(Integer.toString(role.sceneId)).path("doors");
        boolean found = false;
        for (int i = 0; i < doors.size(); i += 3)
            if (doors.get(i).asLong() == portal)
                found = true;
        JsonNode target = cfg.path("portals").path(Long.toString(portal));
        if (!found || !target.isArray())
            return null;
        int roll = ThreadLocalRandom.current().nextInt(10000);
        for (int i = 0; i < target.size(); i += 4) {
            roll -= target.get(i).asInt();
            if (roll >= 0)
                continue;
            int dest = target.get(i + 1).asInt(), x = target.get(i + 2).asInt(), y = target.get(i + 3).asInt();
            room(s, dest, x, y);
            s.put("total", s.path("actors").size());
            save(role.id, s);
            return roles.updatePosition(role.id, dest, x * 32, y * 32, null, null, null);
        }
        return null;
    }

    /** 是否有待继续的岛屿战斗。
     * @param id 角色编号
     * @return 是否占用战斗
     */
    public boolean active(String id) {
        return state(id).has("fight");
    }

    /** 挑战当前房间真实单位并恢复选择队伍的生命。
     * @param id 角色编号
     * @param uid 场景内实例
     * @param team 原队伍编号
     * @return 战斗开场
     */
    @Transactional
    public ObjectNode start(String id, String uid, int team) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = state(id), err = json.createObjectNode().put("errno", 1);
        if (!scene(role.sceneId) || team < 1 || team > 3)
            return err;
        if (s.has("fight"))
            return uid.equals(s.path("target").asText()) ? (ObjectNode) s.path("fight").deepCopy() : err;
        if (daily.active(id) || daily.otherFightActive(id) || tower.active(id) || space.active(id))
            return err;
        for (JsonNode actor : s.path("actors")) {
            if (actor.path("sid").asInt() < 830000 || !uid.equals(actor.path("uid").asText()))
                continue;
            int group = actor.path("sid").asInt();
            ObjectNode battle = fixed.start(role, group, cfg.path("groups").path(Integer.toString(group)), team);
            if (battle.path("errno").asInt() != 0)
                return err;
            boolean alive = false;
            for (JsonNode ally : battle.path("player").path("pokemon")) {
                ObjectNode a = (ObjectNode) ally;
                JsonNode prior = s.path("hp").path(a.path("id").asText());
                applyBuffs(a, s.path("buffs"));
                if (prior.has("crthp"))
                    a.put("hp", Math.min(a.path("hp_max").asInt(), prior.path("crthp").asInt()));
                if (a.path("hp").asInt() > 0)
                    alive = true;
            }
            if (!alive)
                return err;
            s.put("target", uid).set("fight", battle);
            save(id, s);
            return battle;
        }
        return err;
    }

    /** 结算精灵残血、击杀、通关和奖励。
     * @param id 角色编号
     * @param skill 技能编号
     * @param targets 目标
     * @param flag 操作标记
     * @param retreat 是否撤退
     * @return 岛屿战斗结果及消失的目标
     */
    @Transactional
    public ObjectNode advance(String id, int skill, List<?> targets, int flag, boolean retreat) {
        roles.lockRole(id);
        ObjectNode s = state(id), out = json.createObjectNode();
        if (!s.has("fight"))
            return out;
        ObjectNode fight = (ObjectNode) s.get("fight");
        int result = fixed.advance(fight, skill, targets, flag, retreat);
        if (result < 0)
            return out;
        if (result != 0) {
            for (JsonNode ally : fight.path("player").path("pokemon")) {
                String uid = ally.path("id").asText();
                s.with("hp").putObject(uid).put("uid", uid).put("crthp", ally.path("hp").asInt()).put("maxhp",
                        ally.path("hp_max").asInt());
            }
            if (result == 1) {
                ArrayNode actors = (ArrayNode) s.get("actors");
                String target = s.path("target").asText();
                out.put("uid", target);
                for (int i = 0; i < actors.size(); i++)
                    if (target.equals(actors.get(i).path("uid").asText())) {
                        actors.remove(i);
                        break;
                    }
                int island = s.path("island").asInt(1);
                if (fight.path("stage").asInt() == 830000 + 10 * island) {
                    s.with("history").put(Integer.toString(island), true);
                    s.put("done", true);
                    prepareDeck(s, island);
                    actors.removeAll();
                    out.set("adds", reward(id, island, "orange:boss:" + target));
                }
            }
            s.remove(Arrays.asList("fight", "target"));
        }
        save(id, s);
        return out.put("outcome", result).put("island", s.path("island").asInt(1));
    }

    /** 将本次探险实得增益应用到角色出战面板。
     * @param actor 原出战单位
     * @param buffs 本次探险增益
     */
    private void applyBuffs(ObjectNode actor, JsonNode buffs) {
        buffs.fields().forEachRemaining(entry -> {
            JsonNode spec = cfg.path("buffs").path(entry.getKey());
            String attr;
            switch (spec.path(0).asInt()) {
            case 23:
                attr = "damage_bonus";
                break;
            case 12:
                attr = "crit_chance";
                break;
            case 22:
                attr = "crit_damage_bonus";
                break;
            case 63:
                attr = "add_skill_point_limit";
                break;
            case 20:
                attr = "reduced";
                break;
            default:
                return;
            }
            actor.put(attr, actor.path(attr).asInt() + spec.path(1).asInt() * entry.getValue().asInt());
        });
    }

    /** 投影供原冒险导航显示的持续增益。
     * @param id 角色编号
     * @return 当前游戏增益
     */
    public ArrayNode buffs(String id) {
        ArrayNode list = json.createArrayNode();
        state(id).path("buffs").fields().forEachRemaining(e -> list.addObject().put("id", Integer.parseInt(e.getKey()))
                .put("amass", e.getValue().asInt()).put("endtime", 0));
        return list;
    }

    /** 完成真实场景事件，按原动作治疗、复活、发奖或累积增益。
     * @param role 当前角色
     * @param uid 场景对象编号
     * @return 是否成功及实际到账
     */
    @Transactional
    public ObjectNode event(LocalRole role, String uid) {
        role = roles.lockRole(role.id);
        ObjectNode s = state(role.id), out = json.createObjectNode().put("errno", 1);
        if (!scene(role.sceneId) || s.has("fight") || s.path("done").asBoolean())
            return out;
        ArrayNode actors = (ArrayNode) s.get("actors");
        if (actors == null)
            return out;
        for (int i = 0; i < actors.size(); i++) {
            JsonNode actor = actors.get(i);
            if (!uid.equals(actor.path("uid").asText()))
                continue;
            int sid = actor.path("sid").asInt();
            JsonNode action = cfg.path("actions").path(Integer.toString(sid));
            if (!action.isArray())
                return out;
            int arg = action.path(1).asInt();
            switch (action.path(0).asInt()) {
            case 21:
                String key = Integer.toString(arg);
                s.with("buffs").put(key, s.path("buffs").path(key).asInt() + action.path(2).asInt());
                break;
            case 28:
                int slot = sid - 120001;
                JsonNode clear = cfg.path("islands").path(s.path("island").asText()).path("clear");
                Map<Integer, Long> items = Collections.singletonMap(clear.path(slot * 3 + 1).asInt(),
                        slot == 1 ? 2L : 1L);
                ObjectNode adds = out.putObject("adds");
                ArrayNode sent = adds.putArray("items");
                inventory.grantItemsOrMail(role.id, "orange:event:" + uid, items)
                        .forEach((item, n) -> sent.addObject().put("id", item).put("n", n));
                break;
            case 29:
                for (JsonNode hp : s.path("hp")) {
                    int n = hp.path("crthp").asInt(), max = hp.path("maxhp").asInt();
                    if (n > 0)
                        ((ObjectNode) hp).put("crthp", Math.min(max, n + (int) ((long) max * arg / 1000)));
                }
                break;
            case 30:
                List<ObjectNode> dead = new ArrayList<>();
                for (JsonNode hp : s.path("hp"))
                    if (hp.path("crthp").asInt() == 0)
                        dead.add((ObjectNode) hp);
                if (!dead.isEmpty()) {
                    ObjectNode revived = dead.get(ThreadLocalRandom.current().nextInt(dead.size()));
                    revived.put("crthp", revived.path("maxhp").asInt());
                }
                break;
            default:
                return out;
            }
            actors.remove(i);
            save(role.id, s);
            return out.put("errno", 0);
        }
        return out;
    }

    /** 在首领胜利时固定本局翻牌奖品排列。
     * @param s 探索存档
     * @param island 岛屿编号
     */
    private void prepareDeck(ObjectNode s, int island) {
        JsonNode row = cfg.path("islands").path(Integer.toString(island));
        ObjectNode d = s.putObject("deck");
        d.put("id", UUID.randomUUID().toString()).put("idx", row.path("deck").asInt());
        List<Integer> slots = new ArrayList<>(Arrays.asList(0, 1, 2));
        Collections.shuffle(slots);
        ArrayNode list = d.putArray("list");
        for (int j : slots) {
            JsonNode clear = row.path("clear");
            list.addObject().put("itype", 1).put("id", clear.get(j * 3 + 1).asInt()).put("num",
                    clear.get(j * 3 + 2).asInt());
        }
        d.putArray("taken");
    }

    /** 查询可由原翻牌弹窗展示的本次首领奖励。
     * @param id 角色编号
     * @return 牌面数据；无资格时为空
     */
    public ObjectNode deck(String id) {
        JsonNode d = state(id).path("deck");
        ObjectNode out = json.createObjectNode();
        if (d.has("idx") && d.path("taken").size() == 0) {
            out.put("idx", d.path("idx").asInt()).put("source", 0);
            out.set("list", d.path("list").deepCopy());
        }
        return out;
    }

    /** 结算首领翻牌并记录已翻位置与实际扣费。
     * @param role 角色
     * @param index 单翻格号
     * @param all 是否全部翻开
     * @param receipt 操作回执
     * @return 扣费和实际到账
     */
    @Transactional
    public ObjectNode flip(LocalRole role, int index, boolean all, String receipt) {
        role = roles.lockRole(role.id);
        ObjectNode s = state(role.id), out = json.createObjectNode().put("errno", 1);
        String key = "orange:flip:" + receipt;
        if (home.reply(role.id, key) != null)
            return out.put("errno", 0).put("replayed", true);
        JsonNode d = s.path("deck");
        if (!scene(role.sceneId) || !s.path("done").asBoolean() || !d.has("taken"))
            return out;
        ArrayNode taken = (ArrayNode) d.get("taken");
        if (taken.size() >= 3 || (!all && (index < 1 || index > 3)))
            return out;
        List<Integer> chosen = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            boolean used = false;
            for (JsonNode t : taken)
                if (t.asInt() == i)
                    used = true;
            if (!used && (all || i == index))
                chosen.add(i);
        }
        if (chosen.isEmpty())
            return out;
        JsonNode prices = cfg.path("islands").path(s.path("island").asText()).path("prices");
        int cost = prices.get(all ? 3 : taken.size()).asInt();
        if (cost > 0 && !inventory.spendMaterials(role.id, Collections.emptyMap(), 0, cost))
            return out;
        Map<Integer, Long> rewards = new LinkedHashMap<>();
        for (int i : chosen) {
            JsonNode award = d.path("list").get(i - 1);
            rewards.merge(award.path("id").asInt(), award.path("num").asLong(), Long::sum);
            taken.add(i);
        }
        ArrayNode items = out.putObject("adds").putArray("items");
        inventory.grantItemsOrMail(role.id, key, rewards)
                .forEach((item, n) -> items.addObject().put("id", item).put("n", n));
        home.record(role.id, key, "ok");
        save(role.id, s);
        return out.put("errno", 0).put("cost", cost);
    }
}