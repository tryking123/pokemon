package com.pokemon.offline.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 家园全国卷及竞赛卷的出题、改分和答对奖励。
 * @author tongruiyuan
 * @since 2026-11-19 21:39
 */
@Service
public class HomeExamService {
    private final HomeService home;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final JsonNode catalog;

    /**
     * 加载随包精灵、技能和逐题奖励。
     * @param home 饲育屋等级和回执
     * @param roles 角色锁和钱包
     * @param inventory 背包和家园币
     * @param json 存档编解码
     * @param jdbc 本地数据库
     * @throws IOException 题库不可读
     */
    public HomeExamService(HomeService home, RoleService roles, InventoryService inventory, ObjectMapper json,
            JdbcTemplate jdbc) throws IOException {
        this.home = home;
        this.roles = roles;
        this.inventory = inventory;
        this.json = json;
        this.jdbc = jdbc;
        try (InputStream input = new ClassPathResource("home/exam.json").getInputStream()) {
            catalog = json.readTree(input);
        }
    }

    /**
     * 解析已有存档或回执。
     * @param text 存档文本
     * @return 状态对象
     */
    private ObjectNode parse(String text) {
        try {
            return (ObjectNode) json.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException("家园答题存档损坏", e);
        }
    }

    /**
     * 读取本日答卷。
     * @param id 角色编号
     * @param day 北京时间自然日
     * @return 答卷
     */
    private ObjectNode state(String id, long day) {
        List<String> rows = jdbc.queryForList("SELECT state_json FROM local_home_exam WHERE role_id=?", String.class, id);
        if (!rows.isEmpty()) {
            ObjectNode saved = parse(rows.get(0));
            if (saved.path("day").asLong() == day) return saved;
        }
        return json.createObjectNode().put("day", day).put("today", 0).put("normal", 0)
                .put("extra", 0).put("fraud", false).put("cheat", false);
    }

    /**
     * 恢复原入口需要的当天进度。
     * @param id 角色编号
     * @return 已交卷题数及改分状态
     */
    public ObjectNode panel(String id) {
        ObjectNode s = state(id, (System.currentTimeMillis() / 1000 + 28800) / 86400);
        s.remove("day");
        s.remove("question");
        s.remove("hint");
        return s;
    }

    /**
     * 生成四个没有歧义的选项。
     * @param index 题号
     * @param now 当前秒时间戳
     * @return 原协议题目
     */
    private ObjectNode question(int index, long now) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int type = index > 10 ? 4 : rng.nextInt(1, 4);
        JsonNode pool = catalog.path(type == 4 ? "skills" : "species");
        JsonNode row = pool.get(rng.nextInt(pool.size()));
        int sid = row.get(0).asInt();
        int answer;
        List<Integer> candidates = new ArrayList<>();
        if (type == 1) {
            answer = sid;
            List<JsonNode> shuffled = new ArrayList<>();
            pool.forEach(shuffled::add);
            Collections.shuffle(shuffled);
            Set<Integer> names = new HashSet<>();
            names.add(row.get(4).asInt());
            for (JsonNode species : shuffled) {
                if (names.add(species.get(4).asInt())) candidates.add(species.get(0).asInt());
                if (candidates.size() == 3) break;
            }
        } else if (type == 2) {
            int first = row.get(1).asInt(), second = row.get(2).asInt();
            answer = second > 0 && rng.nextBoolean() ? second : first;
            for (int i = 1; i <= 18; i++) if (i != first && i != second) candidates.add(i);
        } else if (type == 3) {
            answer = row.get(3).asInt();
            for (int i = 1; i <= 6; i++) if (i != answer) candidates.add(i);
        } else {
            answer = row.get(1).asInt();
            int step = catalog.path("powerStep").asInt();
            for (int offset = -3; offset <= 3; offset++) {
                int power = answer + offset * step;
                if (offset != 0 && power >= 0) candidates.add(power);
            }
        }
        Collections.shuffle(candidates);
        List<Integer> options = new ArrayList<>(candidates.subList(0, 3));
        options.add(answer);
        Collections.shuffle(options);
        ObjectNode q = json.createObjectNode().put("index", index).put("itype", type).put("sid", sid)
                .put("answer", answer).put("overtime", now + 60);
        q.set("option", json.valueToTree(options));
        return q;
    }

    /**
     * 按原逐题权重抽一份奖励，背包不足时由邮件交付。
     * @param id 角色编号
     * @param state 本日答卷
     * @param index 题号
     * @return 实际增加的资产
     */
    private ObjectNode reward(String id, ObjectNode state, int index) {
        int ticket = ThreadLocalRandom.current().nextInt(10000);
        for (JsonNode row : catalog.path("rewards").get(index > 10 ? 1 : 0)) {
            ticket -= row.get(3).asInt();
            if (ticket >= 0) continue;
            int type = row.get(0).asInt(), item = row.get(1).asInt(), n = row.get(2).asInt();
            ObjectNode adds = json.createObjectNode();
            if (type == 118) {
                inventory.grantHomeCoins(id, n);
                adds.put("homecoin", n);
            } else if (type == 104) {
                roles.addCurrency(id, 0, n, 0);
                adds.put("diamonds", n);
            } else {
                ArrayNode items = adds.putArray("items");
                inventory.grantItemsOrMail(id, "home-exam:" + state.path("day").asLong() + ":" + index,
                        Collections.singletonMap(item, (long) n)).forEach((k, v) -> items.addObject().put("id", k).put("n", v));
            }
            return adds;
        }
        throw new IllegalStateException("答题奖励权重不完整");
    }

    /**
     * 处理原入口并在同一事务保存答卷、资产与请求回执。
     * @param id 角色编号
     * @param type 原协议号
     * @param option 客户端选择的答案值或改分方式
     * @param shown 当前连接最后取得的试题标识
     * @param receipt 本次连接和请求序号
     * @return 原回包和需要推送的资产变更
     */
    @Transactional
    public ObjectNode handle(String id, int type, long option, long shown, String receipt) {
        roles.lockRole(id);
        String saved = home.reply(id, receipt);
        if (saved != null) return parse(saved);
        long now = System.currentTimeMillis() / 1000, local = now + 28800, day = local / 86400;
        ObjectNode result = json.createObjectNode().put("errno", 100021);
        if (local % 86400 < 21600 || local % 86400 >= 82800 || home.level(id) < 4) return result;
        ObjectNode s = state(id, day);
        int done = s.path("today").asInt(), normal = s.path("normal").asInt();
        ObjectNode q = (ObjectNode) s.get("question");
        long token = day * 16 + done + 1;
        switch (type) {
        case 373:
            if (done >= 15 || done == 10 && normal < 9) return result;
            if (q == null) {
                q = question(done + 1, now);
                s.set("question", q);
                s.put("hint", false);
            }
            result.set("riddle", q);
            result.put("token", token);
            break;
        case 374:
            if (q == null || shown != token || now >= q.path("overtime").asLong() || s.path("hint").asBoolean()
                    || s.path("cheat").asBoolean()) return result;
            if (!inventory.spendMaterials(id, Collections.emptyMap(), 0, 700)) return result.put("errno", 100006);
            s.put("hint", true);
            result.put("spent", 700);
            break;
        case 375:
            if (q == null || shown != token) return result;
            boolean offered = false;
            for (JsonNode choice : q.path("option")) if (choice.asLong() == option) offered = true;
            if (!offered) return result;
            boolean won = option == q.path("answer").asLong() && now < q.path("overtime").asLong();
            if (won) {
                String counter = done < 10 ? "normal" : "extra";
                s.put(counter, s.path(counter).asInt() + 1);
                result.set("adds", reward(id, s, done + 1));
            }
            s.put("today", done + 1);
            s.remove("question");
            s.remove("hint");
            result.put("option", option).put("win", won).put("normal", s.path("normal").asInt())
                    .put("extra", s.path("extra").asInt());
            break;
        case 376:
            if (done != 10 || normal >= 9 || s.path("fraud").asBoolean() || (option != 1 && option != 2)) return result;
            if (option == 2) {
                if (!inventory.spendMaterials(id, Collections.emptyMap(), 0, 3500)) return result.put("errno", 100006);
                s.put("cheat", true);
                result.put("spent", 3500);
            }
            boolean pass = option == 2 || ThreadLocalRandom.current().nextInt(100) < 10;
            s.put("fraud", true);
            if (pass) s.put("normal", option == 1 ? 9 : 10);
            result.put("option", option).put("result", pass);
            break;
        default:
            return result;
        }
        result.put("errno", 0);
        jdbc.update("MERGE INTO local_home_exam(role_id,state_json) KEY(role_id) VALUES(?,?)", id, s.toString());
        ObjectNode replay = result.deepCopy();
        replay.remove("adds");
        replay.remove("spent");
        home.record(id, receipt, replay.toString());
        return result;
    }
}
