package com.pokemon.offline.guild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.guild.api.entity.LocalGuild;
import com.pokemon.offline.home.HomeService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;

import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 公会竞跑地图、碰撞、开箱与周榜结算。
 *
 * @author tongruiyuan
 * @since 2026-11-20 15:01
 */
@Service
public class GuildRunService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PersonalGuildService guilds;
    private final RoleService roles;
    private final com.pokemon.offline.role.RoleBattlePowerService power;
    private final InventoryService inventory;
    private final HomeService receipts;
    private final ActivityService activities;

    /**
     * 初始化角色、公会及跑图存档服务。
     *
     * @param jdbc 本地数据库
     * @param json 存档序列化
     * @param guilds 公会资料
     * @param roles 角色钱包
     * @param power 已保存编队的战力
     * @param inventory 背包与奖励邮件
     * @param receipts 角色操作回执
     * @param activities 活动窗口
     */
    public GuildRunService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            PersonalGuildService guilds,
            RoleService roles,
            com.pokemon.offline.role.RoleBattlePowerService power,
            InventoryService inventory,
            HomeService receipts,
            ActivityService activities) {
        this.jdbc = jdbc;
        this.json = json;
        this.guilds = guilds;
        this.roles = roles;
        this.power = power;
        this.inventory = inventory;
        this.receipts = receipts;
        this.activities = activities;
    }

    /**
     * 读取已提交的角色跑图状态。
     *
     * @param text 存档文本
     * @return 角色存档
     */
    private ObjectNode parse(String text) {
        try {
            return (ObjectNode) json.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("公会竞跑存档损坏", e);
        }
    }

    /**
     * 由存档种子重建每个地图段，原 x 为行、y 为道。
     *
     * @param run 当前跑图
     * @param segment 从零开始的绝对段号
     * @return 原地图事件列表
     */
    private ArrayNode map(ObjectNode run, int segment) {
        Random random = new Random(run.path("seed").asLong() + 1000003L * segment);
        ArrayNode cells = json.createArrayNode();
        for (int row = 1; row <= 10; row++) {
            int kind = 1 + random.nextInt(5);
            int variant = 1 + random.nextInt(kind == 3 ? 5 : 2);
            cells.addObject()
                    .put("x", row)
                    .put("y", 1 + random.nextInt(3))
                    .put("num", kind)
                    .put("type", variant);
        }
        return cells;
    }

    /**
     * 按本服已完赛成绩排序，同分按角色编号稳定排序。
     *
     * @param realm 本地分区
     * @param week 北京时间周一的历日编号
     * @return 有效成绩
     */
    private List<Map<String, Object>> ranks(int realm, long week) {
        return jdbc.query(
                "SELECT role_id,guild_name,player_name,best FROM local_guild_run_rank WHERE"
                    + " realm_id=? AND period_day=? ORDER BY best DESC,role_id",
                (row, index) -> {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("rid", row.getString(1));
                    entry.put("guild", row.getString(2));
                    entry.put("player", row.getString(3));
                    entry.put("score", row.getInt(4));
                    return entry;
                },
                realm,
                week);
    }

    /**
     * 结清角色历史周榜，事务同时入账金币和贡献。
     *
     * @param role 当前角色
     * @param week 当前周
     */
    private void settle(LocalRole role, long week) {
        for (Long previous :
                jdbc.queryForList(
                        "SELECT period_day FROM local_guild_run_rank WHERE role_id=? AND"
                            + " period_day<? AND settled=0",
                        Long.class,
                        role.id,
                        week)) {
            int place = 0;
            for (Map<String, Object> row : ranks(role.realmId, previous)) {
                place++;
                if (role.id.equals(row.get("rid"))) break;
            }
            int coins = place <= 10 ? 1000000 - (place - 1) * 50000 : place <= 20 ? 500000 : 0;
            int contribution = place <= 10 ? 1000 - (place - 1) * 100 : place <= 20 ? 50 : 0;
            jdbc.update(
                    "UPDATE local_guild_run_rank SET settled=1,contribution=? WHERE role_id=? AND"
                            + " period_day=?",
                    contribution,
                    role.id,
                    previous);
            if (coins > 0) roles.addCurrency(role.id, coins, 0, 0);
        }
    }

    /**
     * 处理原竞跑动作。读图与复活均使用当前游戏种子和已记录的格线。
     *
     * @param id 已登录角色
     * @param cmd 原协议编号
     * @param args 原请求参数
     * @param receipt 连接和原请求号
     * @param now 当前 Unix 秒
     * @return 原应答和客户端资产刷新信息
     */
    @Transactional
    public ObjectNode act(String id, int cmd, ObjectNode args, String receipt, long now) {
        LocalRole role = roles.lockRole(id);
        LocalDate date = Instant.ofEpochSecond(now).atOffset(ZoneOffset.ofHours(8)).toLocalDate();
        long day = date.toEpochDay(), week = day - date.getDayOfWeek().getValue() + 1;
        settle(role, week);
        LocalGuild guild = guilds.active(id);
        ObjectNode fail = json.createObjectNode().put("errno", 3);
        if (guild == null) return fail;
        boolean mutation = cmd == 303 || cmd == 304 || cmd == 306;
        if (mutation) {
            String old = receipts.reply(id, receipt);
            if (old != null) {
                ObjectNode cached = parse(old);
                if (cmd != cached.path("cmd").asInt()
                        || !args.toString().equals(cached.path("args").toString())) return fail;
                ObjectNode reply = (ObjectNode) cached.path("result");
                reply.remove("adds");
                reply.remove("loses");
                reply.remove("boxes");
                return reply;
            }
        }
        List<String> rows =
                jdbc.queryForList(
                        "SELECT state_json FROM local_guild_run WHERE role_id=?", String.class, id);
        ObjectNode state = rows.isEmpty() ? json.createObjectNode() : parse(rows.get(0));
        if (day != state.path("day").asLong(-1)) {
            state.put("day", day).put("used", 0);
            state.remove("run");
        }
        ObjectNode reply = json.createObjectNode().put("errno", 0);
        String owner = receipt.substring(0, Math.max(0, receipt.lastIndexOf(':')));
        switch (cmd) {
            case 303:
                if (!activities.overviewOpenAt(25, now) || state.path("used").asInt() >= 3)
                    return fail;
                if (state.path("run").path("owner").asText("~").equals(owner)) return fail;
                state.put("used", state.path("used").asInt() + 1);
                ObjectNode run =
                        state.putObject("run")
                                .put("guild", guild.id)
                                .put("owner", owner)
                                .put("seed", ThreadLocalRandom.current().nextLong())
                                .put("hp", 5)
                                .put("energy", 3)
                                .put("score", 100);
                for (int i = 0; i < 3; i++) reply.set("map" + (i + 1), map(run, i));
                break;
            case 304:
                if (!owner.equals(state.path("run").path("owner").asText())
                        || !state.has("run")
                        || !guild.id.equals(state.path("run").path("guild").asText())
                        || !advance(role, guild, state, args, reply, week, now)) return fail;
                break;
            case 305:
                reply.putArray("boxnum")
                        .add(5001)
                        .add(state.path("box1").asInt())
                        .add(5002)
                        .add(state.path("box2").asInt());
                break;
            case 306:
                int box = args.path("idx").asInt() - 5000, count = args.path("times").asInt();
                if (box < 1 || box > 2 || count <= 0 || count > state.path("box" + box).asInt())
                    return fail;
                int item = box == 1 ? 21082 : 21063;
                Map<Integer, Long> granted =
                        inventory.grantItemsOrMail(
                                id, receipt, Collections.singletonMap(item, (long) count));
                if (granted.containsKey(item))
                    reply.putObject("adds")
                            .putArray("items")
                            .addObject()
                            .put("id", item)
                            .put("n", granted.get(item));
                state.put("box" + box, state.path("box" + box).asInt() - count);
                reply.putArray("boxes")
                        .add(5001)
                        .add(state.path("box1").asInt())
                        .add(5002)
                        .add(state.path("box2").asInt());
                break;
            case 307:
            case 309:
                int index = args.path("idx").asInt();
                if (cmd == 307 && index != 1 && index != 2) return fail;
                ArrayNode list = json.createArrayNode();
                reply.put("name", guild.name)
                        .put("times", 3 - state.path("used").asInt())
                        .put("topscore", 0);
                for (Map<String, Object> entry : ranks(role.realmId, week)) {
                    int rank = list.size() + 1;
                    int score = ((Number) entry.get("score")).intValue();
                    boolean self = id.equals(entry.get("rid"));
                    list.addObject()
                            .put("isself", self ? 1 : 0)
                            .put(
                                    "power",
                                    cmd == 307 ? power.score(String.valueOf(entry.get("rid"))) : 0)
                            .put("rank", rank)
                            .put("score", score)
                            .put(
                                    "name",
                                    String.valueOf(entry.get(index == 2 ? "guild" : "player")));
                    if (self) reply.put("rank", rank).put("best", score).put("topscore", score);
                }
                if (cmd == 307) {
                    reply.removeAll();
                    reply.put("errno", 0).set("list", list);
                }
                break;
            default:
                return fail;
        }
        if (mutation) {
            jdbc.update(
                    "MERGE INTO local_guild_run(role_id,state_json) KEY(role_id) VALUES(?,?)",
                    id,
                    state.toString());
            ObjectNode cached = json.createObjectNode().put("cmd", cmd);
            cached.set("args", args);
            cached.set("result", reply);
            receipts.record(id, receipt, cached.toString());
        }
        return reply;
    }

    /**
     * 核对当前地图段的格线与物件碰撞，在合法结束时结算资产与成绩。
     *
     * @param role 已锁定的角色
     * @param guild 个人公会
     * @param state 角色跑图存档
     * @param args 本次动作
     * @param reply 回包
     * @param week 当前周
     * @param now 当前 Unix 秒
     * @return 本次动作是否合法
     */
    private boolean advance(
            LocalRole role,
            LocalGuild guild,
            ObjectNode state,
            ObjectNode args,
            ObjectNode reply,
            long week,
            long now) {
        ObjectNode run = (ObjectNode) state.get("run");
        int segment = run.path("segment").asInt(),
                rows = run.path("rows").asInt(),
                used = run.path("usedCells").asInt();
        int slot = 1 + segment % 3,
                newLife = args.path("newlife").asInt(),
                quit = args.path("quit").asInt();
        JsonNode ops = args.path("operation");
        ArrayNode cells = map(run, segment);
        if (ops.size() > 60
                || args.path("mapid").asInt(slot) != slot
                || (newLife != 0 && newLife != 1)
                || (quit != 0 && quit != 1)) return false;
        for (int i = 0; i < ops.size(); i++) {
            int row = ops.get(i).asInt();
            if (run.path("hp").asInt() == 0) return false;
            if (row == -1) {
                if (++rows > 10) return false;
                run.put("score", Math.addExact(run.path("score").asInt(), 100));
                continue;
            }
            if (row == -3 || row == -5) {
                int energy = run.path("energy").asInt() + row;
                if (energy < 0) return false;
                run.put("energy", energy);
                if (row == -5) run.put("shield", now + 5);
                continue;
            }
            if (row < 1
                    || row > 10
                    || Math.abs(row - rows) > 1
                    || i + 2 >= ops.size()
                    || (used & (1 << row)) != 0) return false;
            int lane = ops.get(++i).asInt(), guard = ops.get(++i).asInt();
            JsonNode cell = cells.get(row - 1);
            if (lane != cell.path("y").asInt()
                    || guard != 0 && (guard != 2 || now > run.path("shield").asLong()))
                return false;
            int kind = cell.path("num").asInt(), variant = cell.path("type").asInt();
            used |= 1 << row;
            switch (kind) {
                case 1:
                    if (guard == 0) run.put("hp", Math.min(5, run.path("hp").asInt() + variant));
                    break;
                case 2:
                    run.put("energy", Math.min(5, run.path("energy").asInt() + 3 - variant));
                    break;
                case 3:
                    if (guard == 0)
                        run.put("hp", Math.max(0, run.path("hp").asInt() - (variant <= 3 ? 1 : 2)));
                    break;
                case 4:
                    run.put("box" + variant, run.path("box" + variant).asInt() + 1);
                    break;
                case 5:
                    String key = variant == 1 ? "diamonds" : "coins";
                    run.put(key, run.path(key).asLong() + (variant == 1 ? 10 : 10000));
                    break;
                default:
                    return false;
            }
        }
        if (args.path("score").asLong(-1) != run.path("score").asLong()
                || rows != args.path("isall").asInt(-1)) return false;
        int hp = run.path("hp").asInt(), revives = run.path("revives").asInt();
        if (newLife == 1) {
            if (hp != 0 || quit != 0 || revives >= 3) return false;
            int cost = new int[] {200, 500, 800}[revives];
            if (!inventory.spendMaterials(role.id, Collections.emptyMap(), 0, cost)) return false;
            reply.putObject("loses").put("diamonds", cost);
            run.put("hp", 5)
                    .put("revives", revives + 1)
                    .put("shield", now + 3)
                    .put("usedCells", used)
                    .put("rows", rows);
        } else if (quit == 1 || hp == 0) {
            state.remove("run");
        } else {
            if (rows != 10) return false;
            run.put("segment", segment + 1).put("rows", 0).put("usedCells", 0);
            reply.set("map", map(run, segment + 3));
            reply.putArray("add");
        }
        // 每个通过校验的地图段同步结算，重开界面或退出应用保留已通过的格线和拾取。
        long coins = run.path("coins").asLong(), diamonds = run.path("diamonds").asLong();
        if (coins + diamonds > 0) roles.addCurrency(role.id, coins, diamonds, 0);
        run.remove("coins");
        run.remove("diamonds");
        if (coins + diamonds > 0)
            reply.putObject("adds").put("coins", coins).put("diamonds", diamonds);
        for (String key : new String[] {"box1", "box2"})
            state.put(key, state.path(key).asInt() + run.path(key).asInt());
        if (run.has("box1") || run.has("box2"))
            reply.putArray("boxes")
                    .add(5001)
                    .add(state.path("box1").asInt())
                    .add(5002)
                    .add(state.path("box2").asInt());
        run.remove("box1");
        run.remove("box2");
        int score = run.path("score").asInt();
        if (score >= 100 && (segment > 0 || rows > 0)) {
            List<Integer> old =
                    jdbc.queryForList(
                            "SELECT best FROM local_guild_run_rank WHERE role_id=? AND"
                                + " period_day=?",
                            Integer.class,
                            role.id,
                            week);
            if (old.isEmpty() || score > old.get(0))
                jdbc.update(
                        "MERGE INTO"
                            + " local_guild_run_rank(role_id,period_day,realm_id,guild_name,player_name,best,settled,contribution)"
                            + " KEY(role_id,period_day) VALUES(?,?,?,?,?,?,?,?)",
                        role.id,
                        week,
                        role.realmId,
                        guild.name,
                        role.name,
                        score,
                        0,
                        0);
        }
        reply.put("score", run.path("score").asInt());
        return true;
    }
}
