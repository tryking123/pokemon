package com.pokemon.local.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 家园荒尘遗迹的可解滑块、计时、周榜与原奖励。
 * @author tongruiyuan
 * @since 2026-11-19 20:09
 */
@Service
public class RelicPuzzleService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final InventoryService inventory;
    private final com.pokemon.local.guild.PersonalGuildService guilds;
    private final HomeService home;
    private final RoleService roles;
    private static final int[][] REWARDS = { { 90, 5000, 100, 10 }, { 120, 4000, 80, 8 }, { 180, 2500, 50, 5 },
            { 300, 1500, 30, 3 }, { 600, 1000, 20, 2 }, { 1200, 500, 10, 0 } };

    /** 绑定家园及实际资产服务。
     * @param guilds 真实徽章套装状态
     * @param jdbc 数据库
     * @param json 状态投影
     * @param inventory 背包和邮件
     * @param home 饲育屋
     * @param roles 角色与钻石
     */
    public RelicPuzzleService(com.pokemon.local.guild.PersonalGuildService guilds, JdbcTemplate jdbc, ObjectMapper json, InventoryService inventory, HomeService home,
            RoleService roles) {
        this.guilds = guilds;
        this.jdbc = jdbc;
        this.json = json;
        this.inventory = inventory;
        this.home = home;
        this.roles = roles;
    }

    /** 读取自然日次数及存档。
     * @param id 角色编号
     * @return 当前状态
     */
    private ObjectNode state(String id) {
        List<String> rows = jdbc.queryForList("SELECT state_json FROM local_relic_state WHERE role_id=?", String.class,
                id);
        ObjectNode s = json.createObjectNode();
        if (!rows.isEmpty())
            try {
                s = (ObjectNode) json.readTree(rows.get(0));
            } catch (IOException e) {
                throw new IllegalStateException("拼图存档损坏", e);
            }
        long day = (Instant.now().getEpochSecond() + 28800) / 86400;
        if (day != s.path("day").asLong())
            s.put("day", day).put("times", 0).put("buytimes", 0);
        return s;
    }

    /** 持久化盘面、次数与回执。
     * @param id 角色编号
     * @param s 状态
     */
    private void save(String id, ObjectNode s) {
        jdbc.update("MERGE INTO local_relic_state(role_id,state_json) KEY(role_id) VALUES(?,?)", id, s.toString());
    }

    /** 查询原 S820。
     * @param id 角色编号
     * @return 剩余次数所依据的用量
     */
    public ObjectNode snapshot(String id) {
        ObjectNode s = state(id);
        return json.createObjectNode().put("times", s.path("times").asInt()).put("buytimes", s.path("buytimes").asInt()).put("history",
                s.path("best").asInt());
    }

    /** 两个格点是否相邻。
     * @param left 一维位置
     * @param right 一维位置
     * @return 是否允许滑动
     */
    private boolean neighbors(int left, int right) {
        return Math.abs(left / 4 - right / 4) + Math.abs(left % 4 - right % 4) == 1;
    }

    /** 从已解盘面按合法动作打乱。
     * @return 含一个零空格的可解盘面
     */
    private List<Integer> puzzle() {
        List<Integer> board = new ArrayList<>();
        for (int i = 1; i <= 15; i++)
            board.add(i);
        board.add(0);
        int previous = -1, empty = 15;
        for (int step = 0; step < 21; step++) {
            List<Integer> choices = new ArrayList<>();
            for (int i = 0; i < 16; i++)
                if (i != previous && neighbors(i, empty))
                    choices.add(i);
            int next = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
            Collections.swap(board, next, empty);
            previous = empty;
            empty = next;
        }
        return board;
    }

    /** 本服本周真实最短通关排名。
     * @param realm 区服
     * @return 原榜单
     */
    private ArrayNode ranking(int realm) {
        long week = ((Instant.now().getEpochSecond() + 28800) / 86400 + 3) / 7;
        ArrayNode entries = json.createArrayNode();
        for (String id : jdbc.queryForList(
                "SELECT role_id FROM local_relic_rank WHERE week_id=? ORDER BY best_seconds,role_id", String.class,
                week)) {
            LocalRole role = roles.findById(id).orElse(null);
            if (role == null || role.realmId != realm)
                continue;
            Integer best = jdbc.queryForObject(
                    "SELECT best_seconds FROM local_relic_rank WHERE role_id=? AND week_id=?", Integer.class, id, week);
            entries.addObject().put("uid", id).put("name", role.name).put("icon", role.avatar).put("alltime", best);
            if (entries.size() >= 100)
                break;
        }
        return entries;
    }

    /** 处理看盘、开始、移动、退出与周排行榜。
     * @param role 当前角色
     * @param type 协议编号
     * @param tile 点击的块面编号
     * @param connection 当前连接编号
     * @param session 请求编号
     * @return 原协议应答和实际到账
     */
    @Transactional
    public ObjectNode handle(LocalRole role, int type, int tile, String connection, long session) {
        role=roles.lockRole(role.id);
        ObjectNode s = state(role.id), r = json.createObjectNode().put("errno", 1);
        if (role.sceneId != 80011 || home.level(role.id) < 1)
            return r;
        String key = connection + ":" + session + ":" + type;
        String previous = home.reply(role.id, "puzzle:" + key);
        if (previous != null) {
            try {
                return (ObjectNode) json.readTree(previous);
            } catch (IOException e) {
                throw new IllegalStateException("拼图回执无法解析", e);
            }
        }
        if (key.equals(s.path("receipt").asText())) {
            ObjectNode duplicate = (ObjectNode) s.path("reply").deepCopy();
            duplicate.remove("adds");
            return duplicate;
        }
        if (type == 348) {
            r.set("list", ranking(role.realmId));
            return r.put("errno", 0);
        }
        int bought = s.path("buytimes").asInt();
        if (type == 368) {
            if (bought >= 3 || s.path("times").asInt() < 2 + bought
                    || !inventory.spendMaterials(role.id, 0, 0, 0, 50L * (bought + 1))) return r;
            s.put("buytimes", bought + 1);
        } else if (type == 411) {
            if (s.path("times").asInt() >= 2 + bought || guilds.stoneLeagueSuitLevel(role.id) < 6
                    || s.has("board") && connection.equals(s.path("connection").asText())) {
                return r;
            }
            s.remove(Arrays.asList("board","start"));
            s.put("times",s.path("times").asInt()+1);
            award(role,r,key,REWARDS[ThreadLocalRandom.current().nextInt(4)][0]);
        } else if (type == 344) {
            if (!connection.equals(s.path("connection").asText()) || !s.has("board")) {
                if (s.path("times").asInt() >= 2 + bought)
                    return r;
                s.put("times", s.path("times").asInt() + 1).put("connection", connection);
                s.remove("start");
                s.set("board", json.valueToTree(puzzle()));
            }
            r.set("board", s.path("board").deepCopy());
            ArrayNode ranks = ranking(role.realmId);
            if (!ranks.isEmpty())
                r.set("first", ranks.get(0));
            else
                r.putObject("first").put("alltime", 0);
        } else {
            if (!connection.equals(s.path("connection").asText()) || !s.has("board"))
                return r;
            if (type == 347) {
                s.remove(Arrays.asList("start", "board"));
            } else if (type == 345) {
                if (!s.has("start"))
                    s.put("start", Instant.now().getEpochSecond());
                r.put("startime", s.path("start").asLong());
            } else if (type == 346) {
                if (!s.has("start") || tile < 1 || tile > 15)
                    return r;
                ArrayNode board = (ArrayNode) s.get("board");
                int empty = -1, chosen = -1;
                for (int i = 0; i < 16; i++) {
                    int n = board.get(i).asInt();
                    if (n == 0)
                        empty = i;
                    if (n == tile)
                        chosen = i;
                }
                if (!neighbors(empty, chosen))
                    return r;
                board.set(empty, json.getNodeFactory().numberNode(tile));
                board.set(chosen, json.getNodeFactory().numberNode(0));
                r.put("idx", tile);
                boolean won = true;
                for (int i = 0; i < 16; i++)
                    if (board.get(i).asInt() != (i + 1) % 16)
                        won = false;
                if (won)
                    finish(role, s, r, key);
            } else
                return r;
        }
        r.put("errno", 0);
        ObjectNode wire = r.deepCopy();
        wire.remove("adds");
        home.record(role.id, "puzzle:" + key, wire.toString());
        s.put("receipt", key).set("reply", r.deepCopy());
        save(role.id, s);
        return r;
    }

    /** 记录实际完成时长并按原档发钻石及道具。
     * @param role 角色
     * @param s 当前盘面
     * @param r 应答
     * @param key 通关回执
     */
    private void finish(LocalRole role, ObjectNode s, ObjectNode r, String key) {
        long now = Instant.now().getEpochSecond(), week = ((now + 28800) / 86400 + 3) / 7;
        int elapsed = (int) Math.min(Integer.MAX_VALUE, Math.max(1, now - s.path("start").asLong()));
        List<Integer> previous = jdbc.queryForList(
                "SELECT best_seconds FROM local_relic_rank WHERE role_id=? AND week_id=?", Integer.class, role.id,
                week);
        int best = previous.isEmpty() ? elapsed : Math.min(previous.get(0), elapsed);
        jdbc.update("MERGE INTO local_relic_rank(role_id,week_id,best_seconds) KEY(role_id,week_id) VALUES(?,?,?)",
                role.id, week, best);
        s.put("best", Math.min(s.path("best").asInt(Integer.MAX_VALUE), elapsed));
        s.remove(Arrays.asList("board", "start"));
        r.put("alltime", elapsed).put("delta", previous.isEmpty() ? 0 : previous.get(0) - elapsed);
        int rank = 0;
        for (JsonNode row : ranking(role.realmId)) {
            rank++;
            if (row.path("uid").asText().equals(role.id)) {
                r.put("rank", rank);
                break;
            }
        }
        award(role,r,key,elapsed);
    }

    /** 发放本局或扫荡抽取档位的原钻石和家园道具。
     * @param role 角色
     * @param r 协议响应
     * @param key 结算回执
     * @param elapsed 对应奖励档的用时
     */
    private void award(LocalRole role,ObjectNode r,String key,int elapsed) {
        ObjectNode adds = r.putObject("adds");
        for (int[] tier : REWARDS)
            if (elapsed <= tier[0]) {
                roles.addCurrency(role.id, 0, tier[1], 0);
                adds.put("diamonds", tier[1]);
                Map<Integer, Long> items = new LinkedHashMap<>();
                items.put(23046, (long) tier[2]);
                if (tier[3] > 0)
                    items.put(23042, (long) tier[3]);
                ArrayNode sent = adds.putArray("items");
                inventory.grantItemsOrMail(role.id, "relic:" + key, items)
                        .forEach((item, n) -> sent.addObject().put("id", item).put("n", n));
                break;
            }
    }
}
