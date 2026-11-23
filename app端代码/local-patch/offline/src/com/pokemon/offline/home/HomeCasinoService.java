package com.pokemon.offline.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;

import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.pokemon.offline.platform.lifecycle.PostConstruct;

/**
 * 家园游戏厅的每日目标、二十一点牌局、宠物与游戏币商店。
 *
 * @author tongruiyuan
 * @since 2026-11-20 12:12
 */
@Service
public class HomeCasinoService {
    private final HomeService home;
    private final RoleService roles;
    private final InventoryService inventory;
    private final BreedingService breeding;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode config;

    /**
     * 加载家园游戏厅的原场次、商店与每日目标。
     *
     * @param home 饲育屋等级与操作回执
     * @param roles 角色事务锁
     * @param inventory 家园币余额
     * @param breeding 成宠仓库
     * @param jdbc 回环数据库
     * @param json 牌局和快照序列化器
     * @throws IOException 原表读取失败
     */
    public HomeCasinoService(
            HomeService home,
            RoleService roles,
            InventoryService inventory,
            BreedingService breeding,
            JdbcTemplate jdbc,
            ObjectMapper json)
            throws IOException {
        this.home = home;
        this.roles = roles;
        this.inventory = inventory;
        this.breeding = breeding;
        this.jdbc = jdbc;
        this.json = json;
        try (InputStream input = new ClassPathResource("home/casino.json").getInputStream()) {
            config = json.readTree(input);
        }
    }

    /** 创建牌局存档表。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("home/casino.sql"))
                .execute(jdbc.getDataSource());
    }

    /**
     * 解析已落库的状态。
     *
     * @param raw 记录内容
     * @return 解析完毕的状态
     */
    private ObjectNode parse(String raw) {
        try {
            return (ObjectNode) json.readTree(raw);
        } catch (IOException error) {
            throw new IllegalStateException("牌局存档无法读取", error);
        }
    }

    /**
     * 读取当天状态。在自然日交替时留存正在进行的牌局和未领箱。
     *
     * @param id 角色编号
     * @return 完整状态
     */
    private ObjectNode state(String id) {
        List<String> current =
                jdbc.queryForList(
                        "SELECT state_data FROM local_home_casino WHERE role_id=?",
                        String.class,
                        id);
        ObjectNode s = current.isEmpty() ? json.createObjectNode() : parse(current.get(0));
        long day = (Instant.now().getEpochSecond() + 28800) / 86400;
        if (s.path("day").asLong(-1) != day) {
            s.put("day", day);
            s.putObject("record");
            s.putObject("progress");
            ArrayNode lucky = s.putArray("lucky");
            List<Integer> cards = freshDeck();
            for (int i = 0; i < 3; i++) lucky.addObject().put("index", cards.get(i)).put("flag", 0);
            save(id, s);
        }
        return s;
    }

    /**
     * 保存角色锁内的牌局。
     *
     * @param id 角色编号
     * @param state 新状态
     */
    private void save(String id, ObjectNode state) {
        jdbc.update(
                "MERGE INTO local_home_casino(role_id,state_data) KEY(role_id) VALUES(?,?)",
                id,
                state.toString());
    }

    /**
     * 生成一副按原四花色、十三点数编码的洗匀扑克。
     *
     * @return 固定到该局的牌序
     */
    private List<Integer> freshDeck() {
        List<Integer> cards = new ArrayList<>(52);
        for (int suit = 1; suit <= 4; suit++)
            for (int rank = 1; rank <= 13; rank++) cards.add(suit * 100 + rank);
        Collections.shuffle(cards);
        return cards;
    }

    /**
     * 返回持久化的游戏币余额。
     *
     * @param id 角色编号
     * @return 游戏币数量
     */
    public long jettons(String id) {
        List<Long> rows =
                jdbc.queryForList(
                        "SELECT balance FROM local_inventory_currency WHERE role_id=? AND"
                            + " currency=127",
                        Long.class,
                        id);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /**
     * 在角色锁内调整实际钱包余额。
     *
     * @param id 角色编号
     * @param kind 底包货币类型
     * @param delta 带正负号的金额
     */
    private void adjust(String id, int kind, long delta) {
        long current = kind == 118 ? inventory.homeCoins(id) : jettons(id);
        long value = Math.addExact(current, delta);
        if (value < 0) throw new IllegalStateException("家园资金不足");
        jdbc.update(
                "MERGE INTO local_inventory_currency(role_id,currency,balance)"
                    + " KEY(role_id,currency) VALUES(?,?,?)",
                id,
                kind,
                value);
    }

    /**
     * 返回客户端全局模型的原牌局状态。
     *
     * @param id 角色编号
     * @return S462 游戏厅状态
     */
    @Transactional
    public ObjectNode panel(String id) {
        ObjectNode out = json.createObjectNode();
        if (home.level(id) < 5) return out;
        roles.lockRole(id);
        ObjectNode s = state(id);
        out.set("lucky", s.path("lucky"));
        ArrayNode boxes = out.putArray("box"), record = out.putArray("record");
        s.with("boxes").elements().forEachRemaining(boxes::add);
        s.with("record")
                .fields()
                .forEachRemaining(
                        row ->
                                record.addObject()
                                        .put("index", Integer.parseInt(row.getKey()))
                                        .put("buy_num", row.getValue().asInt()));
        if (s.has("pet")) out.set("pet", s.get("pet"));
        if (s.has("round")) out.set("active", s.path("round").get("idx"));
        return out;
    }

    /**
     * 计算客户端显示的手牌点数，超出二十一时原模型将所有 A 同时按一分计。
     *
     * @param hand 玩家或庄家的真实手牌
     * @return 原面板点数
     */
    private int points(JsonNode hand) {
        int total = 0, aces = 0;
        for (JsonNode card : hand) {
            int rank = card.asInt() % 100;
            if (rank == 1) {
                total += 11;
                aces++;
            } else total += Math.min(10, rank);
        }
        return total > 21 ? total - 10 * aces : total;
    }

    /**
     * 将依次发出的下一张牌加入指定手牌。
     *
     * @param round 牌局
     * @param side 玩家或庄家字段
     * @return 本次发出的牌号
     */
    private int deal(ObjectNode round, String side) {
        int dealt = round.path("player").size() + round.path("banker").size();
        int card = round.path("deck").get(dealt).asInt();
        round.withArray(side).add(card);
        return card;
    }

    /**
     * 加入、恢复或开始原场次，并按原加成扣家园币。
     *
     * @param id 角色编号
     * @param s 存档
     * @param idx 原场次
     * @param out 响应与资产变动
     * @return 业务是否成功
     */
    private boolean enter(String id, ObjectNode s, int idx, ObjectNode out) {
        JsonNode spec = config.path("game").path(Integer.toString(idx));
        if (!spec.isObject()) return false;
        ObjectNode round;
        if (s.has("round")) {
            round = (ObjectNode) s.get("round");
            if (round.path("idx").asInt() != idx) return false;
        } else {
            if (((Instant.now().getEpochSecond() + 28800) / 3600) % 24 < 6
                    || inventory.homeCoins(id) < spec.path("condition").asLong()) return false;
            int base = spec.path("base").asInt();
            int price = base + base * spec.path("rebate").asInt() / 1000;
            adjust(id, 118, -price);
            out.putObject("loses").put("homecoin", price);
            round = s.putObject("round").put("idx", idx);
            round.set("deck", json.valueToTree(freshDeck()));
            for (int n = 0; n < 2; n++) {
                deal(round, "player");
                deal(round, "banker");
            }
        }
        if (!round.has("uid")) round.put("uid", UUID.randomUUID().toString());
        out.set("boundRound", round.get("uid"));
        out.put("idx", idx);
        out.set("player", round.path("player").deepCopy());
        ArrayNode bankers = out.putArray("banker");
        bankers.add(round.path("banker").get(0));
        bankers.add(round.path("banker").get(1));
        return true;
    }

    /**
     * 在已知的额外要牌中标记每日幸运牌，并在首次集齐时建立奖励箱。
     *
     * @param s 当日角色状态
     * @param round 当前牌局
     * @param out 原要牌回包与新增宝箱
     * @return 是否成功发牌
     */
    private boolean hit(ObjectNode s, ObjectNode round, ObjectNode out) {
        JsonNode hand = round.path("player");
        if (round.path("stopped").asBoolean() || hand.size() >= 5 || points(hand) >= 21)
            return false;
        int card = deal(round, "player");
        out.put("card", card).put("boom", points(hand) > 21);
        boolean found = false;
        int lit = 0;
        for (JsonNode entry : s.withArray("lucky")) {
            if (entry.path("index").asInt() == card && entry.path("flag").asInt() == 0) {
                ((ObjectNode) entry).put("flag", 1);
                found = true;
            }
            lit += entry.path("flag").asInt();
        }
        out.put("lucky", found);
        if (found && lit == 3) {
            createBox(s, round, out, 1, 500);
        }
        return true;
    }

    /**
     * 按本局带入的光环总等级生成一个可单独领取、可跨日保留的奖励箱。
     *
     * @param s 角色存档
     * @param round 已冻结宠物加成的牌局
     * @param out 附带原新增宝箱推送
     * @param kind 原箱型，一为今日幸运扑克，二为神秘惊喜
     * @param base 游戏币基础奖励
     */
    private void createBox(ObjectNode s, ObjectNode round, ObjectNode out, int kind, int base) {
        int index = Math.addExact(s.path("boxSequence").asInt(), 1);
        s.put("boxSequence", index);
        ObjectNode box =
                s.with("boxes")
                        .putObject(Integer.toString(index))
                        .put("index", index)
                        .put("flag", 0)
                        .put("itype", kind);
        int reward = (base * (100 + round.path("auras").asInt()) + 99) / 100;
        box.putArray("award").add(127).add(0).add(reward);
        out.set("box", box.deepCopy());
    }

    /**
     * 庄家按十七点停牌，最多五张，原停止动作可重读已揭的手牌。
     *
     * @param round 当前牌局
     * @param out 响应
     * @return 是否可停牌
     */
    private boolean stand(ObjectNode round, ObjectNode out) {
        if (points(round.path("player")) > 21) return false;
        ArrayNode cards = round.withArray("banker");
        if (!round.path("stopped").asBoolean()) {
            JsonNode player = round.path("player");
            if (player.size() != 2 || points(player) != 21) {
                while (cards.size() < 5 && points(cards) < 17) {
                    deal(round, "banker");
                }
            }
            round.put("stopped", true);
        }
        out.set("banker", cards.deepCopy());
        out.put("boom", points(cards) > 21);
        return true;
    }

    /**
     * 计算原界面的七个结算结果码。
     *
     * @param r 牌局双方最终手牌
     * @return 原始结算结果
     */
    private int outcome(ObjectNode r) {
        JsonNode user = r.path("player"), bank = r.path("banker");
        int a = points(user), b = points(bank);
        if (a > 21) return 1;
        if (b > 21) return 2;
        boolean black = a == 21 && user.size() == 2, dealerBlack = b == 21 && bank.size() == 2;
        if (black && dealerBlack) return 6;
        if (black) return 5;
        if (dealerBlack) return 0;
        int order = Boolean.compare(user.size() == 5, bank.size() == 5);
        if (order == 0) order = Integer.compare(a, b);
        return order == 0 ? 3 : order > 0 ? 4 : 0;
    }

    /**
     * 发布当日三条任务链并结算原有自动奖励。旧版按实战计数达标即发奖，
     * 同一天升级时据此跳过已经付过的档位。
     *
     * @param id 角色编号
     * @return 当日已完成的任务和本次实际增加的家园币
     */
    @Transactional
    public ObjectNode publishTasks(String id) {
        ObjectNode result = json.createObjectNode();
        ArrayNode tasks = result.putArray("tasks");
        if (home.level(id) < 5) return result;
        roles.lockRole(id);
        ObjectNode state = state(id);
        long day = state.path("day").asLong();
        boolean published = state.path("taskAwardDay").asLong(-1) == day;
        int bonus = published ? 0 : state.has("round")
                ? state.path("round").path("auras").asInt() : petBonus(id, state);
        long coins = 0;
        JsonNode targets = config.path("targets");
        for (int index = 0; index < targets.size(); index++) {
            JsonNode row = targets.get(index);
            int target = row.path("target").asInt();
            tasks.addObject().put("id", row.path("id").asInt()).put("progress", target).put("state", 2);
            int previouslyPaid = state.path("progress").path(Integer.toString(index / 3)).asInt();
            if (!published && previouslyPaid < target) {
                coins += (row.path("coins").asLong() * (100L + bonus) + 99) / 100;
            }
        }
        if (!published) {
            if (coins > 0) adjust(id, 118, coins);
            state.put("taskAwardDay", day);
            save(id, state);
        }
        return result.put("taskCoins", coins);
    }

    /**
     * 读取当前游戏厅出战成宠的家园币奖励加成。
     * @param id 角色编号
     * @param state 游戏厅存档
     * @return 出战光环的百分比加成之和
     */
    private int petBonus(String id, ObjectNode state) {
        if (!state.has("pet")) return 0;
        int bonus = 0;
        for (JsonNode pet : breeding.pets(id)) {
            if (state.path("pet").asText().equals(pet.path("uid").asText())) {
                for (JsonNode aura : pet.path("aureole_id")) bonus += aura.asInt() % 100;
            }
        }
        return bonus;
    }

    /**
     * 结算已停牌、爆牌或主动离桌的牌局。
     *
     * @param id 角色编号
     * @param s 状态
     * @param quit 玩家是否主动离桌
     * @param out 响应及真正发放的钱包变化
     * @return 是否成功结算
     */
    private boolean settle(String id, ObjectNode s, boolean quit, ObjectNode out) {
        if (!s.has("round")) return false;
        ObjectNode r = (ObjectNode) s.get("round");
        if (!quit && !r.path("stopped").asBoolean() && points(r.path("player")) <= 21) return false;
        int result = quit ? 0 : outcome(r);
        out.put("quit", quit).put("result", result);
        if (!quit) {
            if (result != 0) {
                JsonNode tier = config.path("game").path(r.path("idx").asText());
                JsonNode payout = tier.path("multiple" + result);
                int type = payout.get(0).asInt();
                long amount = (long) (tier.path("base").asInt() * payout.get(1).asDouble());
                adjust(id, type, amount);
                out.putObject("adds").put(type == 118 ? "homecoin" : "jetton", amount);
            }
            if (result == 4
                    && r.path("player").size() == 5
                    && s.path("surpriseDay").asLong(-1) != s.path("day").asLong()) {
                createBox(s, r, out, 2, 250);
                s.set("surpriseDay", s.get("day"));
            }
        }
        s.remove("round");
        return true;
    }

    /**
     * 收取一个尚未打开的箱子。
     *
     * @param id 角色编号
     * @param s 当天或此前存留的宝箱
     * @param idx 箱子实例编号
     * @param out 原回包与发放记录
     * @return 是否成功
     */
    private boolean collect(String id, ObjectNode s, int idx, ObjectNode out) {
        JsonNode box = s.with("boxes").remove(Integer.toString(idx));
        if (box == null) return false;
        int amount = box.path("award").get(2).asInt();
        adjust(id, 127, amount);
        out.put("idx", idx).putObject("adds").put("jetton", amount);
        return true;
    }

    /**
     * 使用游戏币换取原表的成宠，单笔和组合包数量分开计算。
     *
     * @param id 角色编号
     * @param s 当日商店记录
     * @param idx 原商品号
     * @param count 购买份数
     * @param out 原回包及新宠物
     * @return 是否成交
     */
    private boolean buy(String id, ObjectNode s, int idx, int count, ObjectNode out) {
        String key = Integer.toString(idx);
        JsonNode row = config.path("gamestore").path(key);
        if (!row.isObject() || count < 1 || count > 99) return false;
        int limit = row.path("limit").asInt(), used = s.with("record").path(key).asInt();
        long cost = (long) row.path("costnum").asInt() * count;
        if ((limit != 0 && count > limit - used) || jettons(id) < cost) return false;
        ArrayNode added =
                breeding.grantPets(id, row.path("goods").asInt(), row.path("num").asInt() * count);
        adjust(id, 127, -cost);
        if (limit != 0) s.with("record").put(key, used + count);
        out.put("idx", idx).put("count", count);
        out.putObject("adds").set("pet", added);
        out.putObject("loses").put("jetton", cost);
        return true;
    }

    /**
     * 改派实际闲置成宠，牌局进行中禁止切换。
     *
     * @param id 角色编号
     * @param s 角色状态
     * @param uid 成宠标识
     * @param team 原队伍编号
     * @param deploy 是否出战
     * @param out 客户端原回包
     * @return 修改是否生效
     */
    private boolean assign(
            String id, ObjectNode s, String uid, int team, boolean deploy, ObjectNode out) {
        if (s.has("round") || uid.isEmpty() || !deploy && !uid.equals(s.path("pet").asText()))
            return false;
        ArrayNode status = breeding.casinoPet(id, uid, deploy);
        if (status == null) return false;
        if (deploy) {
            if (s.has("pet")) out.set("pet_uid", s.get("pet"));
            s.put("pet", uid);
            out.put("id", uid).put("teamid", team);
        } else {
            s.remove("pet");
            out.set("status", status);
        }
        return true;
    }

    /**
     * 统一执行原请求，将游戏币、成宠、家园币、牌局及回执纳入角色事务。
     *
     * @param id 角色编号
     * @param type 原请求协议
     * @param idx 场次、宝箱或商品编号
     * @param count 原商品购买份数
     * @param quit 主动离桌标志
     * @param uid 出战或下阵的成宠编号
     * @param team 队伍号
     * @param openedRound 本连接实际入桌的牌局标识
     * @param receipt 原连接与请求序列去重键
     * @return 业务回复及待发送的资产和目标变化
     */
    @Transactional
    public ObjectNode handle(
            String id,
            int type,
            int idx,
            int count,
            boolean quit,
            String uid,
            int team,
            String openedRound,
            String receipt) {
        roles.lockRole(id);
        ObjectNode fail = json.createObjectNode().put("errno", 100021);
        if (home.level(id) < 5) return fail;
        String old = home.reply(id, receipt);
        if (old != null) return parse(old);
        ObjectNode s = state(id), out = json.createObjectNode().put("errno", 0);
        if (type >= 394
                && type <= 396
                && (openedRound == null
                        || !openedRound.equals(s.path("round").path("uid").asText()))) return fail;
        boolean ok;
        switch (type) {
            case 393:
                boolean fresh = !s.has("round");
                ok = enter(id, s, idx, out);
                if (ok && fresh && s.has("pet")) {
                    s.with("round").put("auras", petBonus(id, s));
                }
                break;
            case 394:
                ok = s.has("round") && hit(s, (ObjectNode) s.get("round"), out);
                break;
            case 395:
                ok = s.has("round") && stand((ObjectNode) s.get("round"), out);
                break;
            case 396:
                ok = settle(id, s, quit, out);
                break;
            case 397:
                ok = collect(id, s, idx, out);
                break;
            case 398:
                ok = buy(id, s, idx, count, out);
                break;
            case 10305:
            case 10306:
                ok = assign(id, s, uid, team, type == 10305, out);
                break;
            default:
                return fail;
        }
        if (!ok) return fail;
        save(id, s);
        ObjectNode wire = out.deepCopy();
        wire.remove(java.util.Arrays.asList("adds", "loses", "tasks", "box", "taskCoins"));
        home.record(id, receipt, wire.toString());
        return out;
    }
}
