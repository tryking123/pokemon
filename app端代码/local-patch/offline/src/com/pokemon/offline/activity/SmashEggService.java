package com.pokemon.offline.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 砸金蛋的金锤、原权重奖池、排行及结算邮件。
 * @author tongruiyuan
 * @since 2026-11-19 19:32
 */
@Service
public class SmashEggService {
    public static final int ACTIVITY = 10058;
    public static final int HAMMER = 34347;
    private final ActivityService calendar;
    private final ActivityMapper actions;
    private final InventoryService inventory;
    private final RoleService roles;
    private final JdbcTemplate jdbc;
    private final Rules rules;

    /**
     * 装载原奖池、个人排行门槛及十档奖品。
     * @param calendar 活动日历
     * @param actions 原请求回执
     * @param inventory 背包与邮件
     * @param roles 角色和货币
     * @param jdbc 活动存档
     * @param json 资源读取器
     * @throws IOException 配置无法读取
     */
    SmashEggService(ActivityService calendar, ActivityMapper actions, InventoryService inventory,
                    RoleService roles, JdbcTemplate jdbc, ObjectMapper json) throws IOException {
        this.calendar = calendar;
        this.actions = actions;
        this.inventory = inventory;
        this.roles = roles;
        this.jdbc = jdbc;
        try (InputStream stream = new ClassPathResource("activity/smash-egg.json").getInputStream()) {
            rules = json.readValue(stream, Rules.class);
        }
        int sum = 0;
        for (int i = 4; i < rules.award.size(); i += 5) {
            sum += rules.award.get(i);
        }
        if (sum != 10000 || rules.award2.size() != 90) {
            throw new IllegalArgumentException("砸金蛋原配置不完整");
        }
    }

    /**
     * 读取活动展示或在原轮次处理买锤与抽奖。
     * @param roleId 角色
     * @param protocol 原协议
     * @param value 购买数或抽奖方式
     * @param auto 自动补锤标志
     * @param viewed 界面已展示的周期
     * @param key 请求唯一键
     * @return 原应答及资产变化
     */
    @Transactional
    public Result process(String roleId, int protocol, long value, long auto, long viewed, String key) {
        LocalRole role = roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(ACTIVITY, now);
        if (now < period.start) { return new Result(100013); }
        State state = state(roleId, period.start);
        Result result = new Result(0);
        result.period = period.start;
        switch (protocol) {
            case 10201:
                result.body.put("lucky_data", lucky(role, state));
                result.body.put("info", preview(true));
                return result;
            case 10202:
                int place = place(state.count);
                List<Map<String, Object>> ranks = new ArrayList<>();
                for (int i = 1; i <= 10; i++) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("reward", rankAward(i));
                    if (i == place) {
                        row.put("uid", role.id);
                        row.put("name", role.name);
                        row.put("level", state.count);
                        row.put("sex", role.sex);
                        row.put("icon", role.avatar);
                    }
                    ranks.add(row);
                }
                result.body.put("info", ranks);
                result.body.put("my_consumption", state.count);
                result.body.put("inrank", place);
                result.body.put("three_num", rules.limit1);
                result.body.put("ten_num", rules.limit2);
                return result;
            case 10203:
                result.body.put("info", preview(false));
                return result;
            default:
                if (now >= period.end || viewed != period.start) { return new Result(100013); }
                if (actions.hasOperatingRequest(roleId, ACTIVITY, key)) { return new Result(100011); }
                if (protocol == 10204) { return buy(roleId, period, value, key); }
                if (protocol == 10205) { return draw(roleId, period, state, value, auto, key); }
                throw new IllegalArgumentException("未定义的砸金蛋请求");
        }
    }

    /**
     * 按原快捷商店价格购买真实金锤。
     * @param roleId 角色
     * @param period 轮次
     * @param quantity 购买数量
     * @param key 防重键
     * @return 购买回执
     */
    private Result buy(String roleId, ActivityPeriod period, long quantity, String key) {
        if (quantity < 1 || quantity > Integer.MAX_VALUE) { return new Result(100021); }
        if (!inventory.canGrantItem(roleId, HAMMER, quantity)) { return new Result(100015); }
        long price = quantity * rules.price;
        if (!inventory.spendMaterials(roleId, Collections.emptyMap(), 0, price)) { return new Result(100012); }
        inventory.grant(roleId, HAMMER, quantity);
        actions.recordOperatingAction(roleId, period, 10204, key);
        Result result = new Result(0);
        result.items = Collections.singletonMap(HAMMER, quantity);
        result.cost = price;
        return result;
    }

    /**
     * 扣除实际持有的锤子及勾选补购的缺额，独立抽样并原子入账。
     * @param roleId 角色
     * @param period 活动
     * @param state 本轮砸蛋记录
     * @param mode 单次或十连
     * @param auto 是否自动买锤
     * @param key 原请求回执
     * @return 真实消耗、到账与开奖展示
     */
    private Result draw(String roleId, ActivityPeriod period, State state, long mode, long auto, String key) {
        if ((mode != 1 && mode != 2) || (auto != 0 && auto != 1)) { return new Result(100021); }
        int amount = mode == 1 ? rules.xiaohao1 : rules.xiaohao2;
        long owned = Math.min(amount, inventory.count(roleId, HAMMER));
        long shortage = amount - owned;
        long price = shortage * rules.price;
        if (shortage > 0 && auto == 0) { return new Result(100012); }
        if (!inventory.spendMaterials(roleId, Collections.singletonMap(HAMMER, owned), 0, price)) {
            return new Result(100012);
        }
        Result result = new Result(0);
        result.cost = price;
        result.hammers = owned;
        List<Integer> outcome = new ArrayList<>();
        Map<Integer, Long> items = new LinkedHashMap<>();
        for (int i = 0; i < amount; i++) {
            int offset = drawOffset();
            List<Integer> reward = rules.award.subList(offset, offset + 3);
            outcome.addAll(reward);
            int type = reward.get(0);
            int quantity = reward.get(2);
            if (type == 103) {
                result.coins += quantity;
            } else if (type == 104) {
                result.diamonds += quantity;
            } else {
                items.merge(reward.get(1), (long) quantity, Long::sum);
            }
            if (rules.award.get(offset + 3) == 1) {
                state.rare.add(0, String.valueOf(offset));
                if (state.rare.size() > 10) { state.rare.remove(10); }
            }
        }
        if (result.coins > 0 || result.diamonds > 0) {
            roles.addCurrency(roleId, result.coins, result.diamonds, 0);
        }
        result.items = inventory.grantItemsOrMail(roleId, "egg:" + key, items);
        result.mailed = !items.equals(result.items);
        state.count = Math.addExact(state.count, amount);
        jdbc.update("MERGE INTO local_smash_egg(role_id,period_start,hammer_count,rare_ids,mailed_rank) "
                + "KEY(role_id,period_start) VALUES(?,?,?,?,?)", roleId, period.start, state.count,
                String.join(",", state.rare), 0);
        actions.recordOperatingAction(roleId, period, 10205, key);
        result.body.put("reward", outcome);
        return result;
    }

    /**
     * 获取读档或空白进度。
     * @param roleId 角色
     * @param period 周期起点
     * @return 砸蛋次数及最近实际大奖下标
     */
    private State state(String roleId, long period) {
        return jdbc.query("SELECT hammer_count,rare_ids FROM local_smash_egg WHERE role_id=? AND period_start=?",
                (row, n) -> {
                    State state = new State();
                    state.count = row.getInt(1);
                    String history = row.getString(2);
                    if (!history.isEmpty()) { Collections.addAll(state.rare, history.split(",")); }
                    return state;
                }, roleId, period).stream().findFirst().orElseGet(State::new);
    }

    /**
     * 用一枚万分骰选取五元组的起始下标。
     * @return 原奖池条目下标
     */
    private int drawOffset() {
        int roll = ThreadLocalRandom.current().nextInt(10000);
        for (int i = 0; i < rules.award.size(); i += 5) {
            roll -= rules.award.get(i + 4);
            if (roll < 0) { return i; }
        }
        throw new IllegalStateException("砸金蛋权重未覆盖抽样区间");
    }

    /**
     * 生成首页大奖或完整预览的三元组数组。
     * @param featured 是否仅取原有大奖标记的行
     * @return 原展示数组
     */
    private List<Integer> preview(boolean featured) {
        List<Integer> entries = new ArrayList<>();
        for (int i = 0; i < rules.award.size(); i += 5) {
            if (!featured || rules.award.get(i + 3) == 1) {
                entries.addAll(rules.award.subList(i, i + 3));
            }
        }
        return entries;
    }

    /**
     * 转换最近十次实际大奖以供跑马灯显示。
     * @param role 真实角色
     * @param state 本期记录
     * @return 大奖列表
     */
    private List<Map<String, Object>> lucky(LocalRole role, State state) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String pos : state.rare) {
            int i = Integer.parseInt(pos);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", role.name);
            entry.put("serverid", role.realmId);
            entry.put("item_type", rules.award.get(i));
            entry.put("item_id", rules.award.get(i + 1));
            entry.put("item_num", rules.award.get(i + 2));
            rows.add(entry);
        }
        return rows;
    }

    /**
     * 按前十和前三的原最低消耗分配单机可得最高名次。
     * @param count 本轮有效消耗锤数
     * @return 未上榜为零
     */
    private int place(int count) {
        return count >= rules.limit1 ? 1 : count >= rules.limit2 ? 4 : 0;
    }

    /**
     * 读取原配置中每名的三件奖品。
     * @param place 排名
     * @return 附件三元组
     */
    private List<Integer> rankAward(int place) {
        int from = (place - 1) * 9;
        return rules.award2.subList(from, from + 9);
    }

    /**
     * 登录或活动转入展示时，补结所有已结束且未发榜奖的周期。
     * @param roleId 当前角色
     * @return 新发信件数
     */
    @Transactional
    public int settle(String roleId) {
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod current = calendar.cycleAt(ACTIVITY, now);
        long closed = now >= current.end ? current.start : current.start - (current.showUntil - current.start);
        List<Long> periods = jdbc.query("SELECT period_start FROM local_smash_egg WHERE role_id=? AND period_start<=? "
                + "AND mailed_rank=0 AND hammer_count>=?", (r, n) -> r.getLong(1), roleId, closed, rules.limit2);
        for (long period : periods) {
            int place = place(state(roleId, period).count);
            inventory.createRewardMail(roleId, "egg-rank:" + period + ":" + roleId, "砸金蛋排名奖励",
                    "本次砸金蛋第" + place + "名，附件为本期排行奖励。", now, now + 30 * 86400L, rankAward(place));
            jdbc.update("UPDATE local_smash_egg SET mailed_rank=? WHERE role_id=? AND period_start=?", place, roleId, period);
        }
        return periods.size();
    }

    /**
     * 当前角色的一期砸蛋状态。
     * @author tongruiyuan
     * @since 2026-11-19 19:32
     */
    private static final class State {
        int count;
        final List<String> rare = new ArrayList<>();
    }

    /**
     * 固定底包活动配置。
     * @author tongruiyuan
     * @since 2026-11-19 19:32
     */
    private static final class Rules {
        public int xiaohao1, xiaohao2, limit1, limit2, price;
        public List<Integer> award, award2;
    }

    /**
     * 一笔操作的回执及原生动画所需的实际资产变化。
     * @author tongruiyuan
     * @since 2026-11-19 19:32
     */
    public static final class Result {
        public final Map<String, Object> body = new LinkedHashMap<>();
        public Map<Integer, Long> items = Collections.emptyMap();
        public long cost, hammers, coins, diamonds, period;
        public boolean mailed;

        /** @param errno 原协议状态码 */
        Result(int errno) { body.put("errno", errno); }
    }
}
