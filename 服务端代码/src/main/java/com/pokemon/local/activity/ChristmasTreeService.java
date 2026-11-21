package com.pokemon.local.activity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.ElfReleaseService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 春节狂欢的制作、兑换、档位奖励和周期积分 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-19 00:20
 */
@Service
@DependsOnDatabaseInitialization
public class ChristmasTreeService {
    public static final int ACTIVITY = 10073;
    private static final int ITEM = 1;
    private static final int ELF = 2;
    private static final int DIAMONDS = 104;
    private final ActivityService calendar;
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ElfService elfs;
    private final ElfReleaseService materials;
    private final Config config;

    /**
     * 读取底包原活动规则。
     *
     * @param calendar 活动日历
     * @param jdbc 周期状态存储
     * @param roles 角色事务锁
     * @param inventory 背包及货币
     * @param elfs 精灵奖励
     * @param materials 可用精灵实例的筛选与扣除
     * @param json 配置解析器
     * @throws IOException 配置无法读取
     */
    ChristmasTreeService(ActivityService calendar, JdbcTemplate jdbc, RoleService roles, InventoryService inventory,
                         ElfService elfs, ElfReleaseService materials, ObjectMapper json) throws IOException {
        this.materials = materials;
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.roles = roles;
        this.inventory = inventory;
        this.elfs = elfs;
        try (InputStream input = new ClassPathResource("activity/christmas-tree.json").getInputStream()) {
            config = json.readValue(input, Config.class);
        }
    }

    /**
     * 初始化按活动周期持久保存的积分和领奖记录。
     */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("activity/christmas-tree.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 返回当前轮的玩法、奖励和已有积分。
     *
     * @param roleId 当前角色
     * @return 原协议活动对象
     */
    public Map<String, Object> status(String roleId) {
        ActivityPeriod period = calendar.cycle(ACTIVITY);
        if (period.start > Instant.now().getEpochSecond()) {
            return fields("errno", 100013);
        }
        Map<Integer, Integer> state = state(roleId, period.start);
        List<Map<String, Object>> buttons = new ArrayList<>();
        for (Button button : config.buttons) {
            buttons.add(fields("consume", button.cost, "get", button.reward, "base", button.odds.get(0)));
        }
        List<Map<String, Object>> boxes = new ArrayList<>();
        for (int i = 0; i < config.treasure.size(); i += 4) {
            boxes.add(fields("need", config.treasure.get(i + 3), "award", config.treasure.subList(i, i + 3), "flag",
                    state.getOrDefault(boxKey(i / 4 + 1), 0)));
        }
        List<Map<String, Object>> shop = new ArrayList<>();
        for (int i = 0; i < config.shop.size(); i++) {
            Offer offer = config.shop.get(i);
            shop.add(fields("redeem", offer.cost, "exchange", offer.reward, "num", offer.limit, "refresh", 0,
                    "record", state.getOrDefault(i + 1, 0)));
        }
        int points = state.getOrDefault(0, 0);
        return fields("errno", 0, "buttons", buttons, "box", boxes, "shop", shop, "myscore", points, "totalscore", points);
    }

    /**
     * 购买爆竹或制作烟花，扣除原始成本并结算本次积分。
     *
     * @param roleId 当前角色
     * @param idx 按钮序号
     * @return 原协议操作结果
     */
    @Transactional
    public Map<String, Object> use(String roleId, int idx) {
        if (idx < 1 || idx > config.buttons.size()) {
            return fields("errno", 100021);
        }
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(ACTIVITY, now);
        if (now < period.start) {
            return fields("errno", 100013);
        }
        Map<Integer, Integer> state = state(roleId, period.start);
        int dayKey = period.day + 100;
        int dailyUses = state.getOrDefault(dayKey, 0);
        if (idx == 2 && dailyUses >= config.dailyCandyLimit) {
            return fields("errno", 100012);
        }
        Button button = config.buttons.get(idx - 1);
        int error = redeem(roleId, button.cost, button.reward);
        if (error != 0) {
            return fields("errno", error);
        }
        int weight = 0;
        for (int i = 1; i < button.odds.size(); i += 2) {
            weight += button.odds.get(i);
        }
        int draw = ThreadLocalRandom.current().nextInt(weight);
        int ratio = 1;
        for (int i = 1; i < button.odds.size(); i += 2) {
            draw -= button.odds.get(i);
            if (draw < 0) {
                ratio = button.odds.get(i + 1);
                break;
            }
        }
        int points = button.odds.get(0) * ratio;
        int total = Math.addExact(state.getOrDefault(0, 0), points);
        save(roleId, period.start, 0, total);
        if (idx == 2) {
            save(roleId, period.start, dayKey, dailyUses + 1);
        }
        return fields("errno", 0, "idx", idx, "addScore", points, "ratio", ratio, "totalScore", total);
    }

    /**
     * 兑换当前周期内允许购买的活动商品。
     *
     * @param roleId 当前角色
     * @param idx 活动商城序号
     * @param confirmation 玩家确认刚刚展示的实例名单后提交的交易凭据
     * @return 原协议状态和购买记录
     */
    @Transactional
    public Map<String, Object> purchase(String roleId, int idx, String confirmation) {
        if (idx < 1 || idx > config.shop.size()) {
            return fields("errno", 100021);
        }
        Offer offer = config.shop.get(idx - 1);

        roles.lockRole(roleId);
        ActivityPeriod period = calendar.cycle(ACTIVITY);
        if (period.start > Instant.now().getEpochSecond()) {
            return fields("errno", 100013);
        }
        int count = state(roleId, period.start).getOrDefault(idx, 0);
        if (count >= offer.limit) {
            return fields("errno", 100011);
        }
        if (offer.cost.get(0) == ELF) {
            return tradeElves(roleId, idx, offer, period.start, count, confirmation);
        }
        int error = redeem(roleId, offer.cost, offer.reward);
        if (error != 0) {
            return fields("errno", error);
        }
        save(roleId, period.start, idx, count + 1);
        return fields("errno", 0, "idx", idx, "nowcount", count + 1);
    }

    /**
     * 第一次只展示将被永久消耗的具体实例，确认后在同一角色锁内复查名单、容量和交易凭据。
     * @param role 角色编号
     * @param index 当前商品序号
     * @param offer 原商品的需求、奖励与限购
     * @param period 本轮起点
     * @param count 已成交次数
     * @param confirmation 已确认的预览凭据，首次查看时为空
     * @return 预览说明、成交结果或业务错误
     */
    private Map<String, Object> tradeElves(String role, int index, Offer offer, long period, int count,
                                           String confirmation) {
        int quantity = offer.cost.get(2);
        List<LocalElf> batch = materials.exchangeCandidates(role, offer.cost.get(1), quantity);
        if (batch.size() != quantity) {
            return fields("errno", 100051);
        }
        if (!inventory.canGrantItem(role, offer.reward.get(1), offer.reward.get(2))) {
            return fields("errno", 100015);
        }
        StringBuilder preview = new StringBuilder("下列百变怪及其培养进度将永久消耗，兑换")
                .append(offer.reward.get(1) == 21057 ? "橙色万能觉醒卡" : "红色万能觉醒卡").append("×1。");
        StringBuilder signature = new StringBuilder();
        for (LocalElf elf : batch) {
            int star = elf.training.path("star").asInt(1);
            preview.append("\n").append(elf.level).append("级 ").append(star).append("星 · ").append(elf.id);
            signature.append(elf.id).append('/').append(elf.level).append('/').append(elf.training).append(';');
        }
        String snapshot = java.util.UUID.nameUUIDFromBytes(signature.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        if (confirmation == null) {
            String token = java.util.UUID.randomUUID().toString();
            jdbc.update("MERGE INTO local_christmas_elf_quote(role_id,offer_index,period_start,token,snapshot) "
                    + "KEY(role_id,offer_index) VALUES(?,?,?,?,?)", role, index, period, token, snapshot);
            return fields("errno", 0, "idx", index, "preview", preview.toString(), "quote", token);
        }
        if (confirmation.length() != 36 || jdbc.update("DELETE FROM local_christmas_elf_quote WHERE role_id=? "
                + "AND offer_index=? AND period_start=? AND token=? AND snapshot=?",
                role, index, period, confirmation, snapshot) != 1) {
            return fields("errno", 100021);
        }
        materials.consumeExchangeCandidates(role, batch);
        inventory.grant(role, offer.reward.get(1), offer.reward.get(2));
        save(role, period, index, count + 1);
        return fields("errno", 0, "idx", index, "nowcount", count + 1);
    }

    /**
     * 领取活动积分宝箱，一轮内每档仅可发放一次。
     *
     * @param roleId 当前角色
     * @param idx 宝箱原序号
     * @return 原协议领取结果
     */
    @Transactional
    public Map<String, Object> claim(String roleId, int idx) {
        if (idx < 1 || idx > config.treasure.size() / 4) {
            return fields("errno", 100021);
        }
        roles.lockRole(roleId);
        ActivityPeriod period = calendar.cycle(ACTIVITY);
        if (period.start > Instant.now().getEpochSecond()) {
            return fields("errno", 100013);
        }
        Map<Integer, Integer> state = state(roleId, period.start);
        int offset = (idx - 1) * 4;
        if (state.containsKey(boxKey(idx))) {
            return fields("errno", 100011);
        }
        if (state.getOrDefault(0, 0) < config.treasure.get(offset + 3)) {
            return fields("errno", 100012);
        }
        int error = redeem(roleId, Collections.emptyList(), config.treasure.subList(offset, offset + 3));
        if (error != 0) {
            return fields("errno", error);
        }
        save(roleId, period.start, boxKey(idx), 1);
        return fields("errno", 0, "idx", idx);
    }

    /**
     * 读指定周期的单人实际积分，供活动榜单结算。
     *
     * @param roleId 当前角色
     * @param start 周期开始 Unix 秒
     * @return 已累积的积分
     */
    public int score(String roleId, long start) {
        return state(roleId, start).getOrDefault(0, 0);
    }

    /**
     * 完整检查发放容量和交易成本，原子结算兑换。
     *
     * @param roleId 当前角色
     * @param cost 成本三元组，空列表表示免费
     * @param reward 奖励三元组
     * @return 原协议事务错误码
     */
    private int redeem(String roleId, List<Integer> cost, List<Integer> reward) {
        int kind = reward.get(0);
        int id = reward.get(1);
        int amount = reward.get(2);
        if (kind == ITEM && !inventory.canGrantItem(roleId, id, amount)
                || kind == ELF && elfs.list(roleId).size() >= inventory.elfCapacity(roleId)) {
            return 100015;
        }
        if (!cost.isEmpty()) {
            int quantity = cost.get(2);
            boolean paid = cost.get(0) == DIAMONDS
                    ? inventory.spendMaterials(roleId, Collections.emptyMap(), 0, quantity)
                    : inventory.spendMaterials(roleId, cost.get(1), quantity, 0, 0);
            if (!paid) {
                return cost.get(0) == DIAMONDS ? 100006 : 100051;
            }
        }
        if (kind == ELF) {
            elfs.grant(roleId, id);
        } else {
            inventory.grant(roleId, id, amount);
        }
        return 0;
    }

    /**
     * 读取周期内各类状态。
     *
     * @param roleId 当前角色
     * @param start 周期开始 Unix 秒
     * @return 积分、每日次数、购买次数和宝箱状态
     */
    private Map<Integer, Integer> state(String roleId, long start) {
        Map<Integer, Integer> state = new HashMap<>();
        jdbc.query("SELECT entry_key,entry_value FROM local_christmas_tree WHERE role_id=? AND period_start=?",
                row -> { state.put(row.getInt(1), row.getInt(2)); }, roleId, start);
        return state;
    }

    /**
     * 在角色事务锁内保存状态。
     *
     * @param roleId 当前角色
     * @param start 周期开始 Unix 秒
     * @param key 存档字段编号
     * @param value 积分或领取状态
     */
    private void save(String roleId, long start, int key, int value) {
        if (jdbc.update("UPDATE local_christmas_tree SET entry_value=? WHERE role_id=? AND period_start=? AND entry_key=?",
                value, roleId, start, key) == 0) {
            jdbc.update("INSERT INTO local_christmas_tree(role_id,period_start,entry_key,entry_value) VALUES(?,?,?,?)",
                    roleId, start, key, value);
        }
    }

    /**
     * 使用负编号与活动购买项区分宝箱记录。
     *
     * @param index 宝箱序号
     * @return 宝箱存档键
     */
    private int boxKey(int index) {
        return -index;
    }

    /**
     * 设置原协议输出字段。
     *
     * @param pairs 交替的字段名和字段值
     * @return 结构化应答
     */
    private Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    /**
     * 底包春节狂欢静态配置。
     *
     * @author tongruiyuan
     * @since 2026-11-19 00:20
     */
    public static class Config {
        /** 制作和购买按钮。 */
        public List<Button> buttons;
        /** 以奖励三元组和积分门槛交替排列的宝箱列表。 */
        public List<Integer> treasure;
        /** 每日制作限制。 */
        @JsonProperty("daily_candy_limit")
        public int dailyCandyLimit;
        /** 原活动商品。 */
        public List<Offer> shop;
    }

    /**
     * 扣减资产换取活动奖励的动作。
     *
     * @author tongruiyuan
     * @since 2026-11-19 00:20
     */
    public static class Offer {
        /** 成本三元组。 */
        public List<Integer> cost;
        /** 奖励三元组。 */
        public List<Integer> reward;
        /** 本轮最多购买次数。 */
        public int limit;
    }

    /**
     * 带积分倍率的活动按钮。
     *
     * @author tongruiyuan
     * @since 2026-11-19 00:20
     */
    public static class Button extends Offer {
        /** 基础积分，之后为权重和倍率的配对。 */
        public List<Integer> odds;
    }
}
