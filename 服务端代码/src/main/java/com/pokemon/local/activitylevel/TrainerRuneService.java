package com.pokemon.local.activitylevel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 管理贴纸背包、装备刻印、碎片钱包与原贴纸商店。
 *
 * @author tongruiyuan
 * @since 2026-11-20 13:58
 */
@Service
public class TrainerRuneService {
    private final InventoryService inventory;
    private final RoleService roles;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode rules;
    private final List<Integer> storeIds = new ArrayList<>();

    /**
     * 加载底包刻印与商店原始数值。
     *
     * @param inventory 角色背包及交易能力
     * @param roles 角色存档及事务锁
     * @param jdbc 角色贴纸账本
     * @param json 配置及回执序列化
     * @throws IOException 随包资源读取失败
     */
    public TrainerRuneService(
            InventoryService inventory, RoleService roles, JdbcTemplate jdbc, ObjectMapper json)
            throws IOException {
        this.inventory = inventory;
        this.roles = roles;
        this.jdbc = jdbc;
        this.json = json;
        try (InputStream in =
                new ClassPathResource("catalog/trainer-runes.json").getInputStream()) {
            rules = json.readTree(in);
        }
        rules.required("store")
                .fieldNames()
                .forEachRemaining(k -> storeIds.add(Integer.parseInt(k)));
    }

    /**
     * 读取以角色原编号索引的贴纸背包。
     *
     * @param id 角色编号
     * @return 如实包含数量和锁定状态的原 type12 物件
     */
    public List<Map<String, Object>> bag(String id) {
        return jdbc.query(
                "SELECT sticker_id,amount,locked FROM local_trainer_sticker WHERE role_id=? ORDER"
                    + " BY sticker_id",
                (r, i) -> {
                    Map<String, Object> a = new LinkedHashMap<>();
                    int sid = r.getInt(1);
                    a.put("id", sid);
                    a.put("sid", String.valueOf(sid));
                    a.put("n", r.getInt(2));
                    a.put("lock", r.getBoolean(3));
                    a.put("type", 12);
                    return a;
                },
                id);
    }

    /**
     * 读取当前装备位刻印。
     *
     * @param id 角色编号
     * @return 原 eqitem 数组，uid 是稳定的槽位刻印编号
     */
    public ArrayNode marks(String id) {
        ArrayNode list = json.createArrayNode();
        jdbc.query(
                "SELECT slot,position_no,set_id FROM local_trainer_engraving WHERE role_id=? ORDER"
                    + " BY slot,position_no",
                r -> {
                    list.addObject()
                            .put("uid", 4 * r.getInt(1) + r.getInt(2))
                            .put("pos", r.getInt(1))
                            .put("id", r.getInt(3));
                },
                id);
        return list;
    }

    /**
     * 按实际穿戴位叠加已达成的每档套装效果。
     *
     * @param id 角色编号
     * @param attributes 战斗属性汇总
     */
    public void addBonuses(String id, Map<Integer, Integer> attributes) {
        jdbc.query(
                "SELECT m.set_id,COUNT(*) FROM local_trainer_engraving m JOIN"
                    + " local_trainer_equipped e ON m.role_id=e.role_id AND m.slot=e.slot WHERE"
                    + " m.role_id=? GROUP BY m.set_id",
                r -> {
                    JsonNode tiers = rules.path("sets").path(r.getString(1));
                    for (int i = 0; i < Math.min(tiers.size(), r.getInt(2)); i++) {
                        JsonNode entry = tiers.get(i);
                        attributes.merge(
                                entry.get(0).asInt(), entry.get(1).asInt(), Math::addExact);
                    }
                },
                id);
    }

    /**
     * 生成或读取当前四小时货架。
     *
     * @param id 角色编号
     * @return 带下次自动刷新时间及当前余额的商店
     */
    @Transactional
    public ObjectNode shop(String id) {
        LocalRole role = roles.lockRole(id);
        return loadShop(role);
    }

    /**
     * 读取当前日窗和货架；登录与多连接共用同一快照。
     *
     * @param role 已锁定的当前角色
     * @return 当前货架及到期时间
     */
    private ObjectNode loadShop(LocalRole role) {
        long now = Instant.now().getEpochSecond(),
                seconds = rules.path("cycleHours").asLong() * 3600;
        long period = (now + 28800) / seconds, day = (now + 28800) / 86400;
        List<ObjectNode> rows =
                jdbc.query(
                        "SELECT chips,period,day_no,manual,revision,shelf FROM"
                            + " local_trainer_rune_shop WHERE role_id=?",
                        (r, i) -> {
                            ObjectNode s =
                                    json.createObjectNode()
                                            .put("chips", r.getInt(1))
                                            .put("period", r.getLong(2))
                                            .put("day", r.getLong(3))
                                            .put("manaual", r.getInt(4))
                                            .put("rev", r.getLong(5));
                            s.set("list", decode(r.getString(6)));
                            return s;
                        },
                        role.id);
        ObjectNode s =
                rows.isEmpty()
                        ? json.createObjectNode().put("period", -1).put("chips", 0)
                        : rows.get(0);
        if (s.path("period").asLong() != period) {
            if (s.path("day").asLong() != day) s.put("manaual", 0);
            s.put("period", period).put("day", day);
            renew(s, role.level);
            if (rows.isEmpty())
                jdbc.update(
                        "INSERT INTO"
                            + " local_trainer_rune_shop(role_id,chips,period,day_no,manual,revision,shelf)"
                            + " VALUES(?,?,?,?,?,?,?)",
                        role.id,
                        0,
                        period,
                        day,
                        0,
                        s.path("rev").asLong(),
                        s.path("list").toString());
            else save(role.id, s);
        }
        return s.put("autotime", (period + 1) * seconds - 28800);
    }

    /**
     * 从符合等级的原货物中按权重依次抽取，候选不足一轮后才允许重复。
     *
     * @param s 同一事务中的商店与钱包状态
     * @param level 实际角色等级
     */
    private void renew(ObjectNode s, int level) {
        List<Integer> eligible = new ArrayList<>();
        for (int sid : storeIds)
            if (rule("store", sid).path("level").asInt() <= level) eligible.add(sid);
        ArrayNode list = s.putArray("list");
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < rules.path("slots").asInt() && !eligible.isEmpty(); i++) {
            if (pool.isEmpty()) pool.addAll(eligible);
            int total = 0;
            for (int sid : pool) total += rule("store", sid).path("weight").asInt();
            int roll = ThreadLocalRandom.current().nextInt(total);
            for (int j = 0; j < pool.size(); j++) {
                int sid = pool.get(j);
                roll -= rule("store", sid).path("weight").asInt();
                if (roll < 0) {
                    pool.remove(j);
                    list.addObject().put("id", sid).put("times", 1);
                    break;
                }
            }
        }
        s.put("rev", s.path("rev").asLong() + 1);
    }

    /**
     * 持久化同一事务内的货架、余额和刷新计数。
     *
     * @param id 当前角色编号
     * @param s 同一事务中的商店与钱包状态
     */
    private void save(String id, ObjectNode s) {
        jdbc.update(
                "UPDATE local_trainer_rune_shop SET"
                    + " chips=?,period=?,day_no=?,manual=?,revision=?,shelf=? WHERE role_id=?",
                s.path("chips").asInt(),
                s.path("period").asLong(),
                s.path("day").asLong(),
                s.path("manaual").asInt(),
                s.path("rev").asLong(),
                s.path("list").toString(),
                id);
    }

    /**
     * 结算原协议操作，重发同一回执只读取已提交结果。
     *
     * @param id 角色编号
     * @param action 原请求编号
     * @param p 原协议数据
     * @param revision 客户端看过的货架版本
     * @param receipt 本连接原请求唯一回执
     * @return 原响应字段及重复提交标识
     */
    @Transactional
    public ObjectNode act(String id, int action, JsonNode p, long revision, String receipt) {
        LocalRole role = roles.lockRole(id);
        List<String> old =
                jdbc.queryForList(
                        "SELECT reply FROM local_trainer_rune_receipt WHERE role_id=? AND"
                            + " receipt=?",
                        String.class,
                        id,
                        receipt);
        if (!old.isEmpty()) return ((ObjectNode) decode(old.get(0))).put("replayed", true);
        ObjectNode s = loadShop(role), r = json.createObjectNode();
        int error;
        switch (action) {
            case 2401:
                error = gift(id, p, s, r);
                break;
            case 2528:
                error = engrave(role, p, r);
                break;
            case 2529:
                error = sellMaterial(role, p);
                break;
            case 2530:
            case 2531:
                error = trade(role, action, p, revision, s, r);
                break;
            case 2532:
                error = lock(id, p, r);
                break;
            case 2533:
                error = split(id, p, s, r);
                break;
            default:
                error = 100012;
        }
        if (error == 0) save(id, s);
        r.put("errno", error);
        jdbc.update(
                "INSERT INTO local_trainer_rune_receipt(role_id,receipt,reply) VALUES(?,?,?)",
                id,
                receipt,
                r.toString());
        return r;
    }

    /**
     * 校验原道具使用后增加自选贴纸或原文承诺的碎片。
     *
     * @param id 当前角色编号
     * @param p 原协议业务参数
     * @param s 同一事务中的商店与钱包状态
     * @param r 本次原协议响应
     * @return 道具使用结果码
     */
    private int gift(String id, JsonNode p, ObjectNode s, ObjectNode r) {
        int item = integer(p, "id"), n = p.has("num") ? integer(p, "num") : 1;
        if (n <= 0 || !isGift(item)) return 100012;
        if (item == 34024) {
            int option = integer(p, "giftid");
            int sid = option == 41046 ? 1 : option == 41047 ? 3 : option == 41048 ? 6 : 0;
            if (sid == 0 || !canAdd(id, sid, n, inventory.count(id, item) == n ? 1 : 0))
                return 100015;
            if (!inventory.spendMaterials(id, item, n, 0, 0)) return 100012;
            gain(id, sid, n);
            r.putObject("adds")
                    .putArray("suit_bag")
                    .addObject()
                    .put("type", 12)
                    .put("id", sid)
                    .put("n", n);
            return 0;
        }
        long value = (long) n * (item == 34290 ? 10 : item == 34291 ? 50 : 100);
        if (value > Integer.MAX_VALUE - s.path("chips").asInt()
                || !inventory.spendMaterials(id, item, n, 0, 0)) return 100012;
        s.put("chips", s.path("chips").asInt() + (int) value);
        r.put("chipGain", value);
        return 0;
    }

    /**
     * 原贴纸福袋和三种贴纸券是否由此系统处理。
     *
     * @param id 普通道具编号
     * @return 是否走贴纸账本
     */
    public static boolean isGift(long id) {
        return id == 34024 || id >= 34290 && id <= 34292;
    }

    /**
     * 校验贴纸格位和可堆叠数量。
     *
     * @param id 当前角色编号
     * @param sid 底包贴纸编号
     * @param n 经过校验的数量
     * @param freed 本次同时释放的格数
     * @return 容量及数量是否允许
     */
    private boolean canAdd(String id, int sid, int n, int freed) {
        int amount = amount(id, sid);
        return n > 0
                && n <= Integer.MAX_VALUE - amount
                && (amount > 0
                        || inventory.ordinaryUsed(id) - freed < inventory.ordinaryCapacity(id));
    }

    /**
     * 将贴纸记到相同模板的实际背包格。
     *
     * @param id 当前角色编号
     * @param sid 底包贴纸编号
     * @param n 经过校验的数量
     */
    private void gain(String id, int sid, int n) {
        if (jdbc.update(
                        "UPDATE local_trainer_sticker SET amount=amount+? WHERE role_id=? AND"
                            + " sticker_id=?",
                        n,
                        id,
                        sid)
                == 0)
            jdbc.update(
                    "INSERT INTO local_trainer_sticker(role_id,sticker_id,amount,locked)"
                        + " VALUES(?,?,?,FALSE)",
                    id,
                    sid,
                    n);
    }

    /**
     * 读取某种贴纸的持有数量。
     *
     * @param id 当前角色编号
     * @param sid 底包贴纸编号
     * @return 当前堆叠数量
     */
    private int amount(String id, int sid) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM local_trainer_sticker WHERE role_id=? AND"
                    + " sticker_id=?",
                Integer.class,
                id,
                sid);
    }

    /**
     * 按原请求确认锁定或解锁。
     *
     * @param id 当前角色编号
     * @param p 原协议业务参数
     * @param r 本次原协议响应
     * @return 锁定结果码
     */
    private int lock(String id, JsonNode p, ObjectNode r) {
        int sid = integer(p, "sid");
        if (!p.path("lock").isBoolean() || amount(id, sid) == 0) return 100012;
        boolean flag = p.path("lock").asBoolean();
        jdbc.update(
                "UPDATE local_trainer_sticker SET locked=? WHERE role_id=? AND sticker_id=?",
                flag,
                id,
                sid);
        r.put("sid", sid).put("lock", flag);
        return 0;
    }

    /**
     * 批量预检所有待分解贴纸，全部合法后再扣除并结算。
     *
     * @param id 当前角色编号
     * @param p 原协议业务参数
     * @param s 同一事务中的商店与钱包状态
     * @param r 本次原协议响应
     * @return 分解结果码
     */
    private int split(String id, JsonNode p, ObjectNode s, ObjectNode r) {
        JsonNode list = p.path("list");
        if (!list.isArray() || list.size() == 0 || list.size() > rules.path("stickers").size())
            return 100012;
        Map<Integer, Integer> amounts = new HashMap<>();
        long total = 0;
        for (JsonNode row : list) {
            int sid = integer(row, "sid"), n = integer(row, "num");
            if (n <= 0 || amounts.put(sid, n) != null || !hasUnlocked(id, sid, n)) return 100012;
            total += (long) rule("stickers", sid).path("split").asInt() * n;
        }
        if (total <= 0 || total > Integer.MAX_VALUE - s.path("chips").asInt()) return 100012;
        amounts.forEach((sid, n) -> take(id, sid, n));
        s.put("chips", s.path("chips").asInt() + (int) total);
        r.put("finalchips", s.path("chips").asInt());
        return 0;
    }

    /**
     * 将背包中尚未刻印的套装贴纸按原分解值折算为金币。
     *
     * @param role 已锁定的本地角色
     * @param p 原协议的材料编号与出售数量
     * @return 出售结果码
     */
    private int sellMaterial(LocalRole role, JsonNode p) {
        int sid = integer(p, "material"), count = integer(p, "num");
        int split = rule("stickers", sid).path("split").asInt();
        if (split <= 0 || count <= 0 || !hasUnlocked(role.id, sid, count)) return 100012;
        long coins = (long) split * 100 * count;
        if (role.coins > Long.MAX_VALUE - coins) return 100012;
        take(role.id, sid, count);
        roles.addCurrency(role.id, coins, 0, 0);
        return 0;
    }

    /**
     * 检查未锁定且数量充足的现有格。
     *
     * @param id 当前角色编号
     * @param sid 底包贴纸编号
     * @param n 经过校验的数量
     * @return 是否可用
     */
    private boolean hasUnlocked(String id, int sid, int n) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM local_trainer_sticker WHERE role_id=? AND"
                            + " sticker_id=? AND locked=FALSE AND amount>=?",
                        Integer.class,
                        id,
                        sid,
                        n)
                == 1;
    }

    /**
     * 扣除已校验的贴纸，移除空格以释放容量。
     *
     * @param id 当前角色编号
     * @param sid 底包贴纸编号
     * @param n 经过校验的数量
     */
    private void take(String id, int sid, int n) {
        jdbc.update(
                "UPDATE local_trainer_sticker SET amount=amount-? WHERE role_id=? AND sticker_id=?",
                n,
                id,
                sid);
        jdbc.update(
                "DELETE FROM local_trainer_sticker WHERE role_id=? AND sticker_id=? AND amount=0",
                id,
                sid);
    }

    /**
     * 按实际已经展示的商店货架确认购买或手动刷新。
     *
     * @param role 已锁定的当前角色
     * @param action 原请求编号
     * @param p 原协议业务参数
     * @param revision 本连接实际展示的货架版本
     * @param s 同一事务中的商店与钱包状态
     * @param r 本次原协议响应
     * @return 原交易结果码
     */
    private int trade(
            LocalRole role, int action, JsonNode p, long revision, ObjectNode s, ObjectNode r) {
        if (revision != s.path("rev").asLong()) return 100012;
        if (action == 2530) {
            int n = s.path("manaual").asInt();
            JsonNode prices = rules.path("refresh");
            int price = prices.get(Math.min(n, prices.size() - 1)).asInt();
            if (n == Integer.MAX_VALUE
                    || !inventory.spendMaterials(role.id, Collections.emptyMap(), 0, price))
                return 100012;
            renew(s, role.level);
            s.put("manaual", n + 1);
            r.put("manaual", n + 1);
            r.set("list", s.path("list"));
            return 0;
        }
        int idx = integer(p, "idx");
        JsonNode row = s.path("list").path(idx - 1);
        if (row.path("times").asInt() != 1) return 100012;
        int sid = row.path("id").asInt(),
                cost = rule("store", sid).path("cost").asInt(),
                balance = s.path("chips").asInt();
        if (balance < cost) return 100012;
        if (!canAdd(role.id, sid, 1, 0)) return 100015;
        gain(role.id, sid, 1);
        ((ObjectNode) row).put("times", 0);
        s.put("chips", balance - cost);
        r.put("idx", idx).put("chips", balance - cost);
        return 0;
    }

    /**
     * 按原基础概率开启新位或覆盖同槽现有刻印。
     *
     * @param role 已锁定的当前角色
     * @param p 原协议业务参数
     * @param r 本次原协议响应
     * @return 原刻印结果码
     */
    private int engrave(LocalRole role, JsonNode p, ObjectNode r) {
        int slot = integer(p, "pos"), sid = integer(p, "material");
        JsonNode piece = rule("stickers", sid);
        int set = piece.path("set").asInt();
        if (set == 0 || role.level < piece.path("level").asInt() || !hasUnlocked(role.id, sid, 1))
            return 100012;
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM local_trainer_equipped WHERE role_id=? AND slot=?",
                        Integer.class,
                        role.id,
                        slot)
                != 1) return 100012;
        List<Integer> local =
                jdbc.queryForList(
                        "SELECT set_id FROM local_trainer_engraving WHERE role_id=? AND slot=?"
                            + " ORDER BY position_no",
                        Integer.class,
                        role.id,
                        slot);
        int setCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM local_trainer_engraving WHERE role_id=? AND set_id=?",
                        Integer.class,
                        role.id,
                        set);
        if (local.size() > 3 || local.contains(set) || setCount >= rule("sets", set).size())
            return 100012;
        JsonNode cfg = rules.path("engrave").get(local.size());
        int prob = cfg.get(0).asInt();
        if (integer(p, "probability") != prob
                || !inventory.spendMaterials(
                        role.id, Collections.emptyMap(), cfg.get(1).asLong(), 0)) return 100012;
        take(role.id, sid, 1);
        if (ThreadLocalRandom.current().nextInt(100) < prob)
            jdbc.update(
                    "INSERT INTO local_trainer_engraving(role_id,slot,position_no,set_id)"
                        + " VALUES(?,?,?,?)",
                    role.id,
                    slot,
                    local.size() + 1,
                    set);
        else
            jdbc.update(
                    "UPDATE local_trainer_engraving SET set_id=? WHERE role_id=? AND slot=? AND"
                        + " position_no=?",
                    set,
                    role.id,
                    slot,
                    1 + ThreadLocalRandom.current().nextInt(local.size()));
        r.set("suit_data", marks(role.id));
        return 0;
    }

    /**
     * 读取原配置字典的对应行。
     *
     * @param kind 原表名称
     * @param id 当前角色编号
     * @return 配置行，未知编号为空
     */
    private JsonNode rule(String kind, int id) {
        return rules.path(kind).path(String.valueOf(id));
    }

    /**
     * 拒绝超出原资产账本范围的数字。
     *
     * @param n 待解析的原协议对象
     * @param key 原协议字段名
     * @return 有效整数，非法输入为负一
     */
    private static int integer(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isIntegralNumber() && v.canConvertToInt() ? v.intValue() : -1;
    }

    /**
     * 解码已经持久化的货架或回执。
     *
     * @param text 本地持久化的 JSON
     * @return 反序列化结果
     */
    private JsonNode decode(String text) {
        try {
            return json.readTree(text);
        } catch (IOException ex) {
            throw new IllegalStateException("贴纸存档损坏", ex);
        }
    }
}
