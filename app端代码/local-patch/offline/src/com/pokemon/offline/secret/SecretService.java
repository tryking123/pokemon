package com.pokemon.offline.secret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.activity.CarnivalProgressMapper;
import com.pokemon.offline.battle.FixedStageCombatService;
import com.pokemon.offline.hangup.HangupService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.util.*;
import java.time.*;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 秘境任务、探索格、NPC战斗和首领结算存档。
 * @author tongruiyuan
 * @since 2026-11-19 22:04
 */
@Service
public class SecretService {
    private static final int MAP_COLUMNS = 38;
    private static final int MAP_ROWS = 33;
    private static final int TILE_SIZE = 32;
    /** 原变量 2275 的八档钻价，超过最后一档后沿用末档。 */
    private static final int[] ACTION_PRICES = {30, 40, 50, 60, 70, 80, 90, 100};
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final FixedStageCombatService combat;
    private final HangupService hangup;
    private final com.pokemon.offline.inventory.InventoryService inventory;
    private final JsonNode cfg;
    private final JsonNode stoneRules;
    private final com.pokemon.offline.elf.StoneFusionService fusion;
    private final CarnivalProgressMapper carnival;
    private final SecretEncounterService encounters;
    private final SecretTerrain terrain;

    /** 加载原任务和阵容数据。
     * @param json 序列化服务
     * @param jdbc 本地存档
     * @param roles 角色锁和资产
     * @param combat 通用固定对手战斗
     * @param hangup 普通挂机累计
     * @param inventory 背包容量、材料及钻石支付
     * @param fusion 奥义石词条及旧档恢复
     * @param carnival 七日目标挑战次数流水
     * @param encounters 本局秘境商店与镜像机遇
     * @param terrain 秘境首层通行区域
     * @throws IOException 原配置读取失败
     */
    public SecretService(ObjectMapper json, JdbcTemplate jdbc, RoleService roles, FixedStageCombatService combat,
            HangupService hangup, com.pokemon.offline.inventory.InventoryService inventory,
            CarnivalProgressMapper carnival, com.pokemon.offline.elf.StoneFusionService fusion,
            SecretEncounterService encounters, SecretTerrain terrain) throws IOException {
        this.encounters = encounters;
        this.terrain = terrain;
        this.fusion = fusion;
        this.carnival = carnival;
        this.inventory = inventory;
        this.json = json;
        this.jdbc = jdbc;
        this.roles = roles;
        this.combat = combat;
        this.hangup = hangup;
        try (InputStream input = new ClassPathResource("secret/rosters.json").getInputStream()) {
            cfg = json.readTree(input);
        }
        try (InputStream input = new ClassPathResource("elf/zstone.json").getInputStream()) {
            stoneRules = json.readTree(input);
        }
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS local_secret_state(role_id VARCHAR(100) PRIMARY KEY,state_json TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_stone_action(role_id VARCHAR(100) NOT NULL, receipt VARCHAR(100) NOT NULL, reply TEXT NOT NULL, PRIMARY KEY(role_id,receipt))");
    }

    /** 读取角色已经领取且尚可用于秘境的探险值。
     * @param id 角色编号
     * @return 探险值余额
     */
    public long balance(String id) {
        return Math.max(0, hangup.earnedChallenge(id) - load(id).path("spent").asLong());
    }

    /** 解析存档。 */
    private ObjectNode load(String id) {
        List<String> rows = jdbc.queryForList("SELECT state_json FROM local_secret_state WHERE role_id=?", String.class,
                id);
        if (rows.isEmpty())
            return json.createObjectNode();
        try {
            ObjectNode saved = (ObjectNode) json.readTree(rows.get(0));
            for (JsonNode offer : saved.path("offers"))
                if (!offer.has("last_pray"))
                    ((ObjectNode) offer).put("last_pray", 0);
            return saved;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 原子保存当前角色秘境。 */
    private void save(String id, ObjectNode s) {
        jdbc.update("MERGE INTO local_secret_state(role_id,state_json) KEY(role_id) VALUES(?,?)", id, s.toString());
    }

    /** 原候选池随机抽取一项。 */
    private int pick(JsonNode a) {
        return a.get(ThreadLocalRandom.current().nextInt(a.size())).asInt();
    }

    /** 创建当日任务。进行中的任务跨日保留。 */
    private void offer(ObjectNode s) {
        String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        if (s.has("run") || day.equals(s.path("day").asText()))
            return;
        ArrayNode list = s.putArray("offers");
        s.put("day", day);
        cfg.path("offers").fields().forEachRemaining(e -> {
            JsonNode o = e.getValue();
            list.addObject().put("id", Integer.parseInt(e.getKey())).put("bossid", pick(o.path("boss")))
                    .put("juqingid", ThreadLocalRandom.current().nextInt(1, 11)).put("reward", pick(o.path("rew")))
                    .put("state", 0).put("last_pray", 0).put("gailv", o.path("gailv").asInt());
        });
    }

    /** 恢复四小时免费刷新次数，满三次时停止储存时间。 */
    private void refreshClock(ObjectNode s) {
        long now = Instant.now().getEpochSecond(), then = s.path("refill").asLong(now);
        int left = s.path("free").asInt(3);
        long elapsed = Math.max(0, now - then) / 14400;
        int available = (int) Math.min(3, left + elapsed);
        s.put("free", available).put("refill", available == 3 ? now : then + elapsed * 14400);
    }

    /** 消耗一次免费、刷新券或钻石，生成一组新任务。
     * @param id 角色编号
     * @param type 原界面指定的支付类型
     * @param receipt 原连接和请求号
     * @return 刷新后的任务或空
     */
    @Transactional
    public ObjectNode refresh(String id, int type, String receipt) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = load(id);
        if (s.path("refreshReceipts").has(receipt))
            return s;
        if (s.has("run") || role.level < 21)
            return null;
        refreshClock(s);
        if (s.path("free").asInt() > 0) {
            if (type != 3)
                return null;
            s.put("free", s.path("free").asInt() - 1);
        } else if (inventory.count(id, 21054) > 0) {
            if (type != 1 || !inventory.spendMaterials(id, 21054, 1, 0, 0))
                return null;
        } else if (type != 2 || !inventory.spendMaterials(id, Collections.emptyMap(), 0, 500))
            return null;
        s.remove("day");
        offer(s);
        s.with("refreshReceipts").put(receipt, true);
        save(id, s);
        return s;
    }

    /**
     * 保存当前角色背包中一枚奥义石的保护状态。
     *
     * @param id 角色编号
     * @param sid 奥义石实例编号
     * @param locked 是否禁止分解
     * @param receipt 会话与请求序号
     * @return 当前保护状态，实例无效时为空
     */
    @Transactional
    public Boolean lockStone(String id, String sid, boolean locked, String receipt) {
        roles.lockRole(id);
        String key = "lock:" + receipt;
        List<String> prior = jdbc.queryForList("SELECT reply FROM local_stone_action WHERE role_id=? AND receipt=?",
                String.class, id, key);
        if (!prior.isEmpty()) {
            if (!sid.equals(prior.get(0))) {
                return null;
            }
            List<Integer> state = jdbc.queryForList("SELECT locked FROM local_secret_stone WHERE role_id=? AND sid=?",
                    Integer.class, id, sid);
            return state.isEmpty() ? null : state.get(0) != 0;
        }
        if (jdbc.update("UPDATE local_secret_stone SET locked=? WHERE role_id=? AND sid=?", locked ? 1 : 0, id, sid) == 0) {
            return null;
        }
        jdbc.update("INSERT INTO local_stone_action(role_id,receipt,reply) VALUES(?,?,?)", id, key, sid);
        return locked;
    }

    /**
     * 在同一事务内分解选中的未锁定奥义石，按模板及当前星级发放奥义结晶。
     *
     * @param id 角色编号
     * @param selection 原客户端选中的实例编号
     * @return 是否已完成整批结算
     */
    @Transactional
    public boolean decomposeStones(String id, List<?> selection) {
        if (selection.isEmpty()) {
            return false;
        }
        roles.lockRole(id);
        Set<String> selected = new LinkedHashSet<>();
        for (Object sid : selection) {
            if (!(sid instanceof String) || !selected.add((String) sid)) {
                return false;
            }
        }
        long crystals = 0;
        Set<String> remaining = new HashSet<>(selected);
        for (JsonNode stone : stones(id)) {
            if (remaining.remove(stone.path("sid").asText())) {
                int amount = stoneRules.path("templates").path(stone.path("id").asText()).path("sell")
                        .path(stone.path("elfequ_star").asInt()).asInt();
                if (stone.path("lock").asBoolean() || amount <= 0) {
                    return false;
                }
                crystals += amount;
            }
        }
        if (!remaining.isEmpty()) {
            return false;
        }
        for (String sid : selected) {
            if (jdbc.update("DELETE FROM local_secret_stone WHERE role_id=? AND sid=? AND locked=0 AND elf_id IS NULL", id, sid) != 1) {
                throw new IllegalStateException("奥义石状态已变更");
            }
        }
        inventory.grantItemsOrMail(id, "zstone:" + selected.iterator().next(), Collections.singletonMap(21062, crystals));
        return true;
    }

    /**
     * 使用当前星级的材料和金币尝试一次升星，保存结果与原请求回执。
     *
     * @param id 角色编号
     * @param sid 奥义石编号
     * @param receipt 会话与请求序号
     * @return 原协议状态及结算后的奥义石
     */
    @Transactional
    public ObjectNode upgradeStone(String id, String sid, String receipt) {
        LocalRole owner = roles.lockRole(id);
        List<String> saved = jdbc.queryForList("SELECT reply FROM local_stone_action WHERE role_id=? AND receipt=?",
                String.class, id, receipt);
        if (!saved.isEmpty()) {
            try {
                return (ObjectNode) json.readTree(saved.get(0));
            } catch (IOException e) {
                throw new IllegalStateException("奥义石升星回执损坏", e);
            }
        }
        ObjectNode result = json.createObjectNode().put("errno", 100021);
        for (JsonNode item : stoneItems(id, false)) {
            if (!item.path("sid").asText().equals(sid)) {
                continue;
            }
            int star = item.path("elfequ_star").asInt();
            int lucky = item.path("lucky").asInt();
            JsonNode rule = stoneRules.path("stars").path(star);
            JsonNode cost = stoneRules.path("templates").path(item.path("id").asText()).path("upgrade").path(star);
            if (!rule.isObject() || cost.size() != 2) {
                return result;
            }
            // 原界面每次按当前星级支付完整费用；幸运值是失败后的保底进度。
            if (owner.coins < cost.get(1).asLong()) {
                return result.put("errno", 100005);
            }
            if (inventory.count(id, 21062) < cost.get(0).asLong()) {
                return result.put("errno", 250501);
            }
            if (!inventory.spendMaterials(id, 21062, cost.get(0).asLong(), cost.get(1).asLong(), 0)) {
                return result;
            }
            int limit = rule.path("limit").asInt();
            boolean success = lucky >= limit || ThreadLocalRandom.current().nextInt(100) < rule.path("chance").asInt();
            star += success ? 1 : 0;
            lucky = success ? 0 : Math.min(limit, lucky + rule.path("gain").asInt());
            jdbc.update("UPDATE local_secret_stone SET stars=?,lucky=? WHERE role_id=? AND sid=?", star, lucky, id, sid);
            ObjectNode changed = (ObjectNode) item;
            changed.put("elfequ_star", star).put("lucky", lucky);
            result.put("errno", success ? 0 : 250803).set("info", changed);
            jdbc.update("INSERT INTO local_stone_action(role_id,receipt,reply) VALUES(?,?,?)", id, receipt, result.toString());
            break;
        }
        return result;
    }

    /**
     * 查询背包中持有的独立奥义石。
     *
     * @param id 角色编号
     * @return 原客户端使用的奥义石物品列表
     */
    public JsonNode stones(String id) {
        return stoneItems(id, true);
    }

    /**
     * 读取角色的奥义石实例。
     * @param id 角色编号
     * @param bagOnly 是否仅获取尚未穿戴的实例
     * @return 原协议物品数据
     */
    private JsonNode stoneItems(String id, boolean bagOnly) {
        ArrayNode rows = json.createArrayNode();
        jdbc.query("SELECT sid,template_id,locked,stars,lucky,attrs FROM local_secret_stone WHERE role_id=?"
                + (bagOnly ? " AND elf_id IS NULL" : "") + " ORDER BY sid", (row, index) -> {
            ObjectNode stone = rows.addObject().put("id", row.getInt(2)).put("sid", row.getString(1)).put("n", 1)
                    .put("level", 0).put("elfequ_star", row.getInt(4)).put("elfequ_starexp", 0)
                    .put("lucky", row.getInt(5)).put("lock", row.getInt(3) != 0);
            stone.set("elfequ_addattr", fusion.attributes(row.getInt(2), row.getString(1), row.getString(6)));
            return 1;
        }, id);
        return rows;
    }

    /** 获取当前角色已冻结的任务面板。
     * @param id 角色编号
     * @return 任务面板
     */
    @Transactional
    public ObjectNode panel(String id) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = load(id);
        offer(s);
        refreshClock(s);
        if (s.path("run").isObject()) {
            ObjectNode run = (ObjectNode) s.get("run");
            encounters.initialize(run);
            if (terrain.align(run) && role.sceneId == run.path("map").asInt()) {
                int x = role.x + 4 * TILE_SIZE, y = role.y + 3 * TILE_SIZE;
                if (!terrain.walkable(x, y)) {
                    x = run.path("spawn").path("x").asInt();
                    y = run.path("spawn").path("y").asInt();
                }
                roles.updatePosition(id, role.sceneId, x, y, null, null, null);
            }
        }
        save(id, s);
        return s;
    }

    /** 取得当前副本的复本状态。
     * @param id 角色编号
     * @return 副本状态
     */
    public JsonNode run(String id) {
        return load(id).path("run");
    }

    /**
     * 在入场前改变当前委托的报酬概率。每次抽出的心情替换上一份，同批委托共享。
     * @param id 当前角色编号
     * @param type 原按钮类型，1 为金币，2 为钻石
     * @param receipt 本连接和请求号
     * @return 当前委托列表，资格或余额不足返回空
     */
    @Transactional
    public JsonNode pray(String id, long type, String receipt) {
        LocalRole role = roles.lockRole(id);
        if (role.level < 21 || (type != 1 && type != 2))
            return null;
        ObjectNode state = load(id);
        offer(state);
        JsonNode offers = state.path("offers");
        if (state.path("prayReceipts").has(receipt))
            return state.path("prayReceipts").path(receipt).asLong() == type ? offers : null;
        if (state.has("run"))
            return null;
        boolean eligible = false;
        for (JsonNode row : offers)
            if (row.path("state").asInt() == 0 && row.path("gailv").asInt() < 10000)
                eligible = true;
        if (!eligible || !inventory.spendMaterials(id, Collections.emptyMap(), type == 1 ? 30000 : 0,
                type == 2 ? 3000 : 0))
            return null;

        // 原变量 2349/2351/2353 的五档权重，2350/2352/2354 唯一结果为 6000。
        int bonus = 6000;
        if (type == 1) {
            int[] weights = {4000, 2500, 2000, 1000, 500};
            int ticket = ThreadLocalRandom.current().nextInt(10000);
            for (int i = 0; i < weights.length; i++) {
                ticket -= weights[i];
                if (ticket < 0) {
                    bonus = i * 1000;
                    break;
                }
            }
        }
        for (JsonNode offer : offers) {
            ObjectNode row = (ObjectNode) offer;
            row.put("last_pray", bonus);
            if (row.path("state").asInt() == 0) {
                int base = cfg.path("offers").path(row.path("id").asText()).path("gailv").asInt();
                row.put("gailv", Math.min(10000, base + bonus));
            }
        }
        state.with("prayReceipts").put(receipt, type);
        save(id, state);
        return offers;
    }

    /** 进入已展示的开放任务并仅扣费一次。
     * @param id 角色编号
     * @param index 原面板展示位置
     * @return 是否接受
     */
    @Transactional
    public boolean enter(String id, int index) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = load(id);
        offer(s);
        if (index < 1 || index > s.path("offers").size())
            return false;
        int task = s.path("offers").get(index - 1).path("id").asInt();
        if (role.level < 21)
            return false;
        if (s.has("run"))
            return s.path("run").path("task").asInt() == task;
        for (JsonNode row : s.path("offers")) {
            if (row.path("id").asInt() != task || row.path("state").asInt() != 0)
                continue;
            JsonNode o = cfg.path("offers").path(Integer.toString(task));
            int cost = o.path("challenge").asInt();
            if (hangup.earnedChallenge(id) - s.path("spent").asLong() < cost)
                return false;
            s.put("spent", s.path("spent").asLong() + cost);
            ObjectNode r = s.putObject("run").put("index", index).put("task", task)
                    .put("map", o.path("scene").get(0).asInt()).put("action", o.path("xingdong").asInt());
            ArrayNode units = r.putArray("units");
            int count = o.path("monsternum").asInt();
            for (int i = 0; i <= count; i++) {
                boolean boss = i == count;
                ObjectNode unit = units.addObject()
                        .put("sid", boss ? row.path("bossid").asInt() : pick(o.path("monster")))
                        .put("uid", UUID.randomUUID().toString()).put("boss", boss).put("dead", false);
                unit.putObject("pos").put("x", (9 + i % 3) * 32).put("y", (9 + i / 3) * 32);
            }
            JsonNode rare=o.path("monster2num");
            if(rare.size()==4 && ThreadLocalRandom.current().nextInt(rare.get(1).asInt()+rare.get(3).asInt())>=rare.get(1).asInt()){
                ObjectNode unit=units.addObject().put("sid",o.path("monster2").asInt()).put("uid",UUID.randomUUID().toString()).put("boss",false).put("dead",false);
                unit.putObject("pos").put("x",12*32).put("y",10*32);
            }
            ArrayNode walls=r.putArray("walls");JsonNode countRange=o.path("stopnum");
            int wallCount=ThreadLocalRandom.current().nextInt(countRange.get(0).asInt(),countRange.get(1).asInt()+1);
            for(int i=0;i<wallCount;i++){ObjectNode wall=walls.addObject().put("uid",UUID.randomUUID().toString()).put("sid",pick(o.path("stop")));
                wall.putObject("pos").put("x",(8+i)*32).put("y",11*32);}
            ArrayNode events=r.putArray("events");
            for(String pool:Arrays.asList("key","box")){
                JsonNode bounds=o.path(pool+"num");int n=ThreadLocalRandom.current().nextInt(bounds.get(0).asInt(),bounds.get(1).asInt()+1);
                for(int i=0;i<n;i++){
                    int template=pool.equals("key")?o.path("key").asInt():pick(o.path("box"));
                    ObjectNode event=events.addObject().put("sid",template).put("uid",UUID.randomUUID().toString()).put("done",false);
                    event.putObject("pos").put("x",(8+i)*32).put("y",(pool.equals("key")?7:6)*32);
                }
            }
            ArrayNode open = r.putArray("open");
            for (int i = 0; i < o.path("open").asInt(); i++) {
                open.addObject().put("x", (8 + i % 3) * 32).put("y", (8 + i / 3) * 32);
            }
            encounters.initialize(r);
            terrain.align(r);
            save(id, s);
            carnival.record(id, 101, 1);
            return true;
        }
        return false;
    }

    /** 通过本局已揭开的 NPC 处理购买、翻牌与查询。
     * @param id 角色编号
     * @param command 原协议编号
     * @param npc 对话对象编号
     * @param x 对话对象横向格号
     * @param y 对话对象纵向格号
     * @param index 单开卡牌位置
     * @param receipt 本次连接请求凭据
     * @return 可直接回复的商品或镜像面板，非法操作返回空
     */
    @Transactional
    public ObjectNode encounter(String id, int command, long npc, long x, long y, long index, String receipt) {
        LocalRole role = roles.lockRole(id);
        ObjectNode state = load(id);
        JsonNode current = state.path("run");
        if (!current.isObject() || current.has("fight") || current.path("map").asInt(-1) != role.sceneId) {
            return null;
        }
        ObjectNode run = (ObjectNode) current;
        ObjectNode result;
        switch (command) {
            case 5055:
            case 5056:
                result = encounters.merchant(id, run, npc, x, y, command == 5055, receipt);
                break;
            case 5080:
            case 5081:
            case 5082:
                result = encounters.mirror(id, run, npc, x, y, command, index, receipt);
                break;
            default:
                return null;
        }
        if (result != null && command != 5056 && command != 5082) {
            save(id, state);
        }
        return result;
    }

    /** 进入或退出当前秘境场景。
     * @param id 角色编号
     * @param enter 是否进入
     * @return 新位置或空
     */
    @Transactional
    public LocalRole travel(String id, boolean enter) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = load(id);
        JsonNode r = s.path("run");
        if (!r.has("map"))
            return null;
        if (enter) {
            JsonNode spawn = r.path("spawn");
            return roles.updatePosition(id, r.path("map").asInt(), spawn.path("x").asInt(12 * 32),
                    spawn.path("y").asInt(11 * 32), null, null, null);
        }
        if (role.sceneId != r.path("map").asInt() || r.has("fight"))
            return null;
        if (r.path("won").asBoolean() && !r.path("claimed").asBoolean()) {
            if (claim(id) == 0)
                return null;
            s = load(id);
        }
        if (!r.path("won").asBoolean() && !r.path("claimed").asBoolean())
            for (JsonNode row : s.path("offers"))
                if (row.path("id").asInt() == r.path("task").asInt())
                    ((ObjectNode) row).put("state", 1);
        s.remove("run");
        save(id, s);
        return roles.updatePosition(id, 71002, 26 * 32, 33 * 32, null, null, null);
    }

    /** 通过原地图的停步请求揭开途中格子，每个新格消耗一次行动。
     * @param id 当前角色
     * @param scene 请求中的场景
     * @param x 原停步横向像素
     * @param y 原停步纵向像素
     * @return 已确认的位置、剩余行动及是否需要校正，其他场景返回空引用
     */
    @Transactional
    public ObjectNode stopExploration(String id, long scene, long x, long y) {
        LocalRole role = roles.lockRole(id);
        if (role.sceneId != 40001) {
            return null;
        }
        ObjectNode state = load(id);
        if (state.path("run").path("map").asInt() != role.sceneId) {
            return null;
        }
        ObjectNode run = (ObjectNode) state.get("run");
        JsonNode last = run.has("lastStop") ? run.get("lastStop") : run.get("spawn");
        ObjectNode result = last.deepCopy();
        result.put("accepted", false).put("action", run.path("action").asInt());
        if (scene != role.sceneId || x < 0 || y < 0 || x >= MAP_COLUMNS * TILE_SIZE || y >= MAP_ROWS * TILE_SIZE || run.has("fight")) {
            return result;
        }
        List<JsonNode> steps = terrain.route(run, (int) x, (int) y);
        if (steps == null) {
            return result;
        }
        java.util.Set<JsonNode> revealed = new java.util.HashSet<>();
        run.path("open").forEach(revealed::add);
        steps.removeIf(revealed::contains);
        int left = run.path("action").asInt() - steps.size();
        if (left < 0) {
            return result;
        }
        ArrayNode open = (ArrayNode) run.get("open");
        steps.forEach(open::add);
        result.putArray("revealed").addAll(steps);
        run.put("action", left).putObject("lastStop").put("x", x).put("y", y);
        roles.updatePosition(id, role.sceneId, (int) x, (int) y, null, null, null);
        save(id, state);
        return result.put("accepted", true).put("action", left).put("x", x).put("y", y);
    }

    /** 翻开相邻原像素格并扣一点行动。
     * @param id 角色编号
     * @param x 横坐标
     * @param y 纵坐标
     * @return 剩余行动或负数
     */
    @Transactional
    public int reveal(String id, int x, int y) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = load(id);
        JsonNode r = s.path("run");
        if (r.path("map").asInt() != role.sceneId || !r.has("open") || x < 0 || y < 0 || x >= MAP_COLUMNS * TILE_SIZE || y >= MAP_ROWS * TILE_SIZE
                || x % 32 != 0 || y % 32 != 0 || !terrain.walkable(x, y))
            return -1;
        ArrayNode open = (ArrayNode) r.get("open");
        boolean adjacent = false;
        for (JsonNode p : open) {
            int distance = Math.abs(p.path("x").asInt() - x) + Math.abs(p.path("y").asInt() - y);
            if (distance == 0)
                return r.path("action").asInt();
            if (distance == 32)
                adjacent = true;
        }
        if (!adjacent || r.path("action").asInt() <= 0)
            return -1;
        open.addObject().put("x", x).put("y", y);
        int left = r.path("action").asInt() - 1;
        ((ObjectNode) r).put("action", left);
        save(id, s);
        return left;
    }

    /**
     * 按本次秘境已购次数扣钻并增加三点行动力。
     *
     * @param id 角色编号
     * @param receipt 原连接和请求序号
     * @return 更新后的探索实例，购买失败时为空
     */
    @Transactional
    public ObjectNode buyAction(String id, String receipt) {
        LocalRole role = roles.lockRole(id);
        ObjectNode state = load(id);
        JsonNode current = state.path("run");
        if (!current.isObject() || current.path("map").asInt() != role.sceneId || current.path("won").asBoolean()) {
            return null;
        }
        ObjectNode run = (ObjectNode) current;
        if (run.path("actionReceipts").has(receipt)) {
            return run;
        }
        int count = run.path("actionBuys").asInt();
        int price = ACTION_PRICES[Math.min(count, ACTION_PRICES.length - 1)];
        if (!inventory.spendMaterials(id, Collections.emptyMap(), 0, price)) {
            return null;
        }
        run.put("actionBuys", Math.addExact(count, 1));
        run.put("action", Math.addExact(run.path("action").asInt(), 3));
        run.with("actionReceipts").put(receipt, true);
        save(id, state);
        return run;
    }

    /**
     * 在首领确已战败后揭开当前秘境的全图，保留场景事件及领奖状态。
     *
     * @param id 角色编号
     * @return 本层已揭开的位置，尚未满足条件时为空
     */
    @Transactional
    public JsonNode revealAfterBoss(String id) {
        LocalRole role = roles.lockRole(id);
        ObjectNode state = load(id);
        JsonNode current = state.path("run");
        if (!current.path("won").asBoolean() || current.path("map").asInt() != role.sceneId) {
            return null;
        }
        ObjectNode run = (ObjectNode) current;
        if (!run.path("allRevealed").asBoolean()) {
            run.set("open", terrain.all());
            run.put("allRevealed", true);
            save(id, state);
        }
        return run.get("open");
    }

    /** 按实际编队和当前秘境副本生命表生成队伍切换或重登数据。
     * @param id 角色编号
     * @param team 所选队号，零表示读取上次选择
     * @return 原副本 teamhp，非法切换为空
     */
    @Transactional
    public ObjectNode teamHealth(String id,int team) {
        LocalRole role=roles.lockRole(id);
        ObjectNode state=load(id);
        JsonNode node=state.path("run");
        if (!node.isObject() || node.path("map").asInt()!=role.sceneId)return null;
        ObjectNode run=(ObjectNode)node;
        boolean change=team!=0;
        if (!change)team=run.path("team").asInt(1);
        if(team<1 || team>3 || team>role.stage+1 || (change && run.has("fight")))return null;
        ObjectNode members=combat.health(id,team);
        ObjectNode report=json.createObjectNode().put("teamid",team);
        ArrayNode detail=report.putArray("detail");
        boolean living=false;
        for(JsonNode member:members) {
            String uid=member.path("uid").asText();
            JsonNode saved=run.path("hp").path(uid);
            ObjectNode actual=member.deepCopy();
            if(saved.has("crthp"))actual.put("crthp",Math.min(actual.path("maxhp").asInt(),saved.path("crthp").asInt()));
            living|=actual.path("crthp").asInt()>0;
            actual.remove("team");
            detail.add(actual);
        }
        if(change) {
            if(!living)return null;
            run.put("team",team);
            save(id,state);
        }
        return report;
    }

    /** 开始已经揭示格子上的原怪物战斗。
     * @param id 角色编号
     * @param uid 目标实例
     * @param team 出战队伍
     * @return 原开场包
     */
    @Transactional
    public ObjectNode start(String id, String uid, int team) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = load(id);
        JsonNode r = s.path("run");
        ObjectNode err = json.createObjectNode().put("errno", 1);
        if (r.path("map").asInt() != role.sceneId || team < 1 || team > 3 || team>role.stage+1 || team!=r.path("team").asInt(1))
            return err;
        if (r.has("fight"))
            return uid.equals(r.path("target").asText()) ? (ObjectNode) r.path("fight").deepCopy() : err;
        for (JsonNode u : r.path("units"))
            if (uid.equals(u.path("uid").asText()) && !u.path("dead").asBoolean()) {
                boolean visible = false;
                for (JsonNode p : r.path("open"))
                    if (p.equals(u.path("pos")))
                        visible = true;
                if (!visible)
                    return err;
                int group = u.path("sid").asInt();
                ObjectNode fight = combat.start(role, group, cfg.path("groups").path(Integer.toString(group)), team);
                if (fight.path("errno").asInt() == 0) {
                    boolean alive = false;
                    for (JsonNode a : fight.path("player").path("pokemon")) {
                        JsonNode h = r.path("hp").path(a.path("id").asText());
                        if (h.has("crthp"))
                            ((ObjectNode) a).put("hp", Math.min(a.path("hp_max").asInt(), h.path("crthp").asInt()));
                        alive |= a.path("hp").asInt() > 0;
                    }
                    if (!alive)
                        return err;
                    ((ObjectNode) r).put("team", team).put("target", uid).set("fight", fight);
                    save(id, s);
                }
                return fight;
            }
        return err;
    }

    /** 推进战斗，胜利后只标记真实被击败的对应实例。
     * @param id 角色编号
     * @param skill 技能
     * @param targets 目标
     * @param flag 操作类型
     * @param retreat 是否退出
     * @return 终局时的实例
     */
    @Transactional
    public JsonNode advance(String id, int skill, List<?> targets, int flag, boolean retreat) {
        roles.lockRole(id);
        ObjectNode s = load(id);
        JsonNode r = s.path("run");
        ObjectNode out = json.createObjectNode();
        if (!r.has("fight"))
            return out;
        int result = combat.advance((ObjectNode) r.get("fight"), skill, targets, flag, retreat);
        if (result < 0)
            return out;
        if (result > 0) {
            for (JsonNode a : r.path("fight").path("player").path("pokemon")) {
                String uid = a.path("id").asText();
                ((ObjectNode) r).with("hp").putObject(uid).put("uid", uid).put("crthp", a.path("hp").asInt())
                        .put("maxhp", a.path("hp_max").asInt()).put("team", r.path("team").asInt(1));
            }
            if (result == 1)
                for (JsonNode u : r.path("units"))
                    if (!u.path("dead").asBoolean() && r.path("target").asText().equals(u.path("uid").asText())) {
                        defeatUnit(id, (ObjectNode) r, (ObjectNode) u);
                        out.setAll((ObjectNode) u);
                        if (u.path("boss").asBoolean())
                            ((ObjectNode) r).put("won", true);
                    }
            ((ObjectNode) r).remove(Arrays.asList("fight", "target"));
        }
        save(id, s);
        if (out.path("boss").asBoolean())
            out.put("coins", claim(id));
        return out;
    }

    /**
     * 检查本局任务是否配置了普通怪击杀结算。
     * @param run 当前秘境存档
     * @return 是否支持秒杀该任务的普通怪
     */
    public boolean allowsInstantDefeat(JsonNode run) {
        return encounters.task(run.path("task").asLong()).path("monsterDrop").isObject();
    }

    /**
     * 登记死亡，以同一角色和怪物实例冻结普通掉落，直接入包或写入溢出邮件。
     * @param id 角色编号
     * @param run 本局秘境存档
     * @param unit 本局首次击败的怪物
     */
    private void defeatUnit(String id, ObjectNode run, ObjectNode unit) {
        unit.put("dead", true);
        JsonNode offer = cfg.path("offers").path(run.path("task").asText());
        if (unit.path("boss").asBoolean() || unit.path("sid").asInt() == offer.path("monster2").asInt()) {
            return;
        }
        JsonNode drop = encounters.task(run.path("task").asLong()).path("monsterDrop");
        int chance = drop.path("chance").asInt();
        if (chance <= 0) {
            return;
        }
        String uid = unit.path("uid").asText();
        UUID draw = UUID.nameUUIDFromBytes((id + ":" + uid).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (Math.floorMod(draw.hashCode(), 10000) >= chance) {
            return;
        }
        int itemId = drop.get("id").asInt();
        long quantity = drop.get("num").asLong();
        Map<Integer, Long> added = inventory.grantItemsOrMail(id, "secret-monster:" + uid,
                Collections.singletonMap(itemId, quantity));
        unit.putObject("drop").putArray("items").addObject().put("id", itemId).put("n", quantity);
        long received = added.getOrDefault(itemId, 0L);
        if (received > 0) {
            unit.putObject("added").putArray("items").addObject().put("id", itemId).put("n", received);
        }
    }

    /**
     * 在当前地图按原秒杀卡或钻石价格击败已揭开的普通怪，并记录击杀凭据。
     * @param id 当前角色编号
     * @param uid 本局怪物实例编号
     * @param template 请求中的怪物组模板
     * @param x 场景模型已经换算的横向格坐标
     * @param y 场景模型已经换算的纵向格坐标
     * @param payment 一为使用本局秒杀卡，二为即时支付钻石
     * @param receipt 连接和请求编号
     * @return 原请求的结算回执，重发沿用冻结的奖励，无效时返回空引用
     */
    @Transactional
    public ObjectNode instantDefeat(String id, String uid, long template, long x, long y, long payment, String receipt) {
        LocalRole role = roles.lockRole(id);
        ObjectNode state = load(id);
        JsonNode raw = state.path("run");
        if (!raw.isObject() || raw.path("map").asInt() != role.sceneId || raw.has("fight")
                || !allowsInstantDefeat(raw) || payment != 1 && payment != 2) {
            return null;
        }
        ObjectNode run = (ObjectNode) raw;
        JsonNode previous = run.path("instantKills").path(receipt);
        if (previous.isObject()) {
            boolean same = previous.path("uid").asText().equals(uid) && previous.path("sid").asLong() == template
                    && previous.path("x").asLong() == x && previous.path("y").asLong() == y
                    && previous.path("payment").asLong() == payment;
            return same ? ((ObjectNode) previous).deepCopy().put("replay", true) : null;
        }
        for (JsonNode unit : run.path("units")) {
            JsonNode pos = unit.path("pos");
            if (!unit.path("uid").asText().equals(uid) || unit.path("sid").asLong() != template
                    || pos.path("x").asLong() / TILE_SIZE != x || pos.path("y").asLong() / TILE_SIZE != y
                    || unit.path("dead").asBoolean() || unit.path("boss").asBoolean() || template == 230000) {
                continue;
            }
            boolean visible = false;
            for (JsonNode tile : run.path("open")) {
                if (tile.equals(pos)) {
                    visible = true;
                    break;
                }
            }
            if (!visible) {
                return null;
            }
            int cards = run.path("tools").path("1021").asInt();
            if (payment == 1) {
                if (cards <= 0) {
                    return null;
                }
                run.with("tools").put("1021", cards - 1);
            } else {
                if (cards > 0 || !inventory.spendMaterials(id, Collections.emptyMap(), 0, 50)) {
                    return null;
                }
                run.put("instantPurchases", run.path("instantPurchases").asInt() + 1);
            }
            defeatUnit(id, run, (ObjectNode) unit);
            ObjectNode result = run.with("instantKills").putObject(receipt).put("uid", uid).put("sid", template)
                    .put("x", x).put("y", y).put("payment", payment);
            if (unit.has("drop")) {
                result.set("drop", unit.get("drop"));
            }
            if (unit.has("added")) {
                result.set("added", unit.get("added"));
            }
            save(id, state);
            return result;
        }
        return null;
    }

    /** 购买原秘境包子卡，检查单局累计限购并在事务内扣钻。
     * @param id 角色编号
     * @param type 原道具配置编号
     * @param n 数量
     * @param receipt 连接请求收据
     * @return 是否入账
     */
    @Transactional
    public boolean buy(String id, int type, int n, String receipt) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = load(id);
        JsonNode r = s.path("run");
        if(type<2236||type>2239||n<1||r.path("map").asInt()!=role.sceneId||r.path("claimed").asBoolean())return false;
        ObjectNode run=(ObjectNode)r;if(run.path("buys").has(receipt))return true;
        int slot=type-2236,limit=slot<2?5:2;
        String key=Integer.toString(type);
        int bought=slot==3?run.path("purchased").asInt():run.path("bought").path(key).asInt();
        long[] prices={200,300,500,1000};
        if(n>limit-bought||!inventory.spendMaterials(id,Collections.emptyMap(),0,prices[slot]*n))return false;
        if(slot==3)run.put("purchased",bought+n).put("buns",run.path("buns").asInt()+n);
        else{run.with("bought").put(key,bought+n);ObjectNode tools=run.with("tools");tools.put(key,tools.path(key).asInt()+n);}
        run.with("buys").put(receipt,true);
        save(id, s);
        return true;
    }

    /** 使用飞行、炸弹或复活卡，按当前场景的真实位置、目标实例与存活状态校验。
     * @param id 角色编号
     * @param type 道具配置编号
     * @param uid 障碍或战败精灵实例
     * @param team 精灵所属队伍
     * @param receipt 请求唯一标记
     * @return 是否完成并扣除道具
     */
    @Transactional public boolean useTool(String id,int type,String uid,int team,String receipt){
        LocalRole role=roles.lockRole(id);ObjectNode state=load(id);JsonNode raw=state.path("run");
        if(raw.path("map").asInt()!=role.sceneId||raw.has("fight")||!raw.isObject())return false;
        ObjectNode run=(ObjectNode)raw;String key=Integer.toString(type);
        if(run.path("uses").has(receipt))return true;
        int available=run.path("tools").path(key).asInt();if(available<1)return false;
        if(type==2236){
            List<JsonNode> tiles=new ArrayList<>();
            for(JsonNode pos:run.path("open"))if(pos.path("x").asInt()!=role.x||pos.path("y").asInt()!=role.y)tiles.add(pos);
            if(tiles.isEmpty())return false;JsonNode pos=tiles.get(ThreadLocalRandom.current().nextInt(tiles.size()));
            roles.updatePosition(id,role.sceneId,pos.path("x").asInt(),pos.path("y").asInt(),null,null,null);
        }else if(type==2237){
            if(!run.path("walls").isArray())return false;
            ArrayNode walls=(ArrayNode)run.get("walls");int index=-1;
            for(int i=0;i<walls.size();i++)if(uid.equals(walls.get(i).path("uid").asText())){
                for(JsonNode pos:run.path("open"))if(pos.equals(walls.get(i).path("pos")))index=i;
            }
            if(index<0)return false;walls.remove(index);
        }else if(type==2238){
            JsonNode h=run.path("hp").path(uid);
            if(team<1||team>3||h.path("team").asInt(1)!=team||h.path("maxhp").asInt()<=0||h.path("crthp").asInt()!=0)return false;
            ((ObjectNode)h).put("crthp",(h.path("maxhp").asLong()+1)/2);
        }else return false;
        run.with("tools").put(key,available-1);run.with("uses").put(receipt,true);save(id,state);return true;
    }

    /** 领取地图钥匙或打开已揭开的宝箱，同局实例仅结算一次。
     * @param id 角色编号
     * @param uid 原场景事件实例
     * @param receipt 当前请求收据
     * @return 是否成功触发
     */
    @Transactional public boolean treasure(String id,String uid,String receipt){
        LocalRole role=roles.lockRole(id);ObjectNode s=load(id);JsonNode r=s.path("run");
        if(!r.isObject()||r.path("map").asInt()!=role.sceneId||r.has("fight"))return false;
        ObjectNode run=(ObjectNode)r;
        for(JsonNode event:run.path("events")){
            if(!uid.equals(event.path("uid").asText()))continue;
            if(event.path("done").asBoolean())return receipt.equals(event.path("receipt").asText());
            boolean seen=false;for(JsonNode pos:run.path("open"))if(pos.equals(event.path("pos")))seen=true;
            if(!seen)return false;
            int sid=event.path("sid").asInt(),keys=run.path("keys").asInt();
            if(sid==40045)run.put("keys",keys+1);
            else{
                int grade=sid-40000;if(grade<1||grade>3||keys<grade*2)return false;
                run.put("keys",keys-grade*2);
                int[] diamonds={10,25,50};roles.addCurrency(id,0,diamonds[grade-1],0);
            }
            ((ObjectNode)event).put("done",true).put("receipt",receipt);save(id,s);return true;
        }
        return false;
    }
    /** 使用一张包子卡，让选中队伍的存活精灵各恢复最大生命的20%。
     * @param id 角色编号
     * @param team 原队伍
     * @param receipt 连接请求收据
     * @return 是否完成治疗
     */
    @Transactional
    public boolean heal(String id, int team, String receipt) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = load(id);
        JsonNode r = s.path("run");
        if (r.path("map").asInt() != role.sceneId || r.has("fight") || team < 1 || team > 3)
            return false;
        ObjectNode run = (ObjectNode) r;
        if (run.path("uses").has(receipt))
            return true;
        if (run.path("buns").asInt() <= 0)
            return false;
        boolean any = false;
        for (JsonNode h : run.path("hp")) {
            int cur = h.path("crthp").asInt(), max = h.path("maxhp").asInt();
            if (h.path("team").asInt(1) != team || cur <= 0 || cur >= max)
                continue;
            ((ObjectNode) h).put("crthp", (int) Math.min(max, cur + Math.max(1, max / 5L)));
            any = true;
        }
        if (!any)
            return false;
        run.put("buns", run.path("buns").asInt() - 1).with("uses").put(receipt, true);
        save(id, s);
        return true;
    }

    /** 按本次首领胜利领取固定原金币。
     * @param id 角色编号
     * @return 实际领取或历史领取金额
     */
    @Transactional
    public int claim(String id) {
        roles.lockRole(id);
        ObjectNode s = load(id);
        JsonNode r = s.path("run");
        if (!r.path("won").asBoolean())
            return 0;
        int task = r.path("task").asInt(),
                amount = cfg.path("offers").path(Integer.toString(task)).path("rew2").get(2).asInt();
        if (!r.path("claimed").asBoolean()) {
            for (JsonNode row : s.path("offers"))
                if (row.path("id").asInt() == task) {
                    if (!r.has("rolled")) {
                        ((ObjectNode) r).put("rolled", true);
                        if (ThreadLocalRandom.current().nextInt(10000) < row.path("gailv").asInt()) {
                            ObjectNode stone = ((ObjectNode) r).putObject("stone").put("id", row.path("reward").asInt())
                                    .put("sid", UUID.randomUUID().toString()).put("n", 1).put("level", 0)
                                    .put("elfequ_star", 0).put("elfequ_starexp", 0).put("lock", false);
                            stone.set("elfequ_addattr", fusion.attributes(stone.path("id").asInt(), stone.path("sid").asText(), null));
                        }
                    }
                    if (r.has("stone")) {
                        if (inventory.ordinaryUsed(id) >= inventory.ordinaryCapacity(id)) {
                            save(id, s);
                            return 0;
                        }
                        JsonNode stone = r.path("stone");
                        jdbc.update("INSERT INTO local_secret_stone(sid,role_id,template_id) VALUES(?,?,?)",
                                stone.path("sid").asText(), id, stone.path("id").asInt());
                    }
                    ((ObjectNode) row).put("state", 2);
                }
            roles.addCurrency(id, amount, 0, 0);
            ((ObjectNode) r).put("claimed", true);
            save(id, s);
        }
        return amount;
    }
}
