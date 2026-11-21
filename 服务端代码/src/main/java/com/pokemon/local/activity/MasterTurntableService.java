package com.pokemon.local.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地跨服大转盘的周奖池、抽取流水、积分商店和周榜结算。
 *
 * @author tongruiyuan
 * @since 2026-11-20 10:40
 */
@Service
public class MasterTurntableService {
    public static final int ACTIVITY = 10048;
    private static final int SLOTS = 20;
    private final ActivityService calendar;
    private final RoleService roles;
    private final InventoryService inventory;
    private final JdbcTemplate jdbc;
    private final Rules rules;

    /** 加载转盘数值及资产依赖。
     * @param calendar 原活动日历
     * @param roles 角色锁
     * @param inventory 资产及邮件服务
     * @param jdbc 本地存档
     * @param json 配置读取器
     * @throws IOException 配置无法读取
     */
    public MasterTurntableService(ActivityService calendar, RoleService roles, InventoryService inventory,
                                  JdbcTemplate jdbc, ObjectMapper json) throws IOException {
        this.calendar = calendar;
        this.roles = roles;
        this.inventory = inventory;
        this.jdbc = jdbc;
        try (InputStream stream = new ClassPathResource("activity/master-turntable.json").getInputStream()) {
            rules = json.readValue(stream, Rules.class);
        }
        if (rules.days != 7 || SLOTS % rules.pool.length != 0 ||
                Arrays.stream(rules.pool).mapToInt(entry -> entry[0]).sum() != 10000) {
            throw new IllegalArgumentException("转盘周奖池设置有误");
        }
    }

    /** 按已展示的抽取次数、活动周期及原协议办理查询、抽奖和兑换。
     * @param role 角色编号
     * @param protocol 原协议编号
     * @param value 抽取方式或货架序号
     * @param viewed 最近一次打开对应面板的周起点
     * @param expected 当时显示的当日剩余次数
     * @param key 原连接请求唯一键
     * @return 原回包及实际资产变动
     */
    @Transactional
    public Result process(String role, int protocol, long value, long viewed, int expected, String key) {
        roles.lockRole(role);
        lockRound();
        long now = System.currentTimeMillis();
        ActivityPeriod period = calendar.cycleAt(ACTIVITY, now / 1000);
        int[] bought = new int[rules.offers.length];
        int[] draws = new int[2];
        jdbc.query("SELECT action_index,event_day FROM local_turntable_event WHERE role_id=? AND period_start=?",
                row -> {
                    int offer = row.getInt(1);
                    if (offer == 0) {
                        draws[0]++;
                        if (row.getInt(2) == period.day) { draws[1]++; }
                    } else { bought[offer - 1]++; }
                }, role, period.start);
        int remaining = rules.dailyFree + rules.maxPaidPerDay - draws[1];
        int points = draws[0] * rules.roundScorePerSpin;
        for (int i = 0; i < bought.length; i++) { points -= bought[i] * rules.offers[i][0]; }
        Result result = new Result(0);
        result.period = period.start;
        result.remaining = remaining;
        if (protocol == 10021) {
            List<Map<String, Object>> ranks = standings(period.start);
            result.body.put("inrank", place(ranks, role));
            result.body.put("my_consumption", draws[0] * rules.roundScorePerSpin);
            while (ranks.size() < rules.rankRewards.length) { ranks.add(new LinkedHashMap<>()); }
            for (int i = 0; i < ranks.size(); i++) {
                ranks.get(i).put("reward", rankReward(i + 1));
            }
            result.body.put("info", ranks);
            return result;
        }
        if (protocol == 10022) {
            List<Map<String, Object>> preview = new ArrayList<>();
            for (int slot = 1; slot <= SLOTS; slot++) { preview.add(sector(period.start, slot)); }
            result.body.put("info", preview);
            result.body.put("my_info", Collections.emptyList());
            result.body.put("pool", remaining);
            result.body.put("cost", Arrays.asList(draws[1] < rules.dailyFree ? 0 : rules.diamondsPerPaidSpin,
                    rules.diamondsPerPaidSpin));
            result.body.put("log", recent(period.start));
            return result;
        }
        if (protocol == 10023) {
            List<Map<String, Object>> shop = new ArrayList<>();
            for (int i = 0; i < bought.length; i++) {
                int[] offer = rules.offers[i];
                shop.add(fields("integral", offer[0], "itype", 1, "id", offer[2], "num", offer[3],
                        "limit", offer[1], "buy", bought[i]));
            }
            result.body.put("shop", shop);
            result.body.put("my_integral", points);
            return result;
        }
        if (viewed != period.start) { return new Result(100013); }
        if ((protocol == 10024 && value != 1) ||
                (protocol == 10025 && (value < 1 || value > bought.length))) { return new Result(100021); }
        int action = protocol == 10024 ? 0 : (int) value;
        List<int[]> previous = jdbc.query("SELECT action_index,wheel_slot,remaining,points_after FROM local_turntable_event "
                        + "WHERE role_id=? AND request_key=? AND period_start=?",
                (r, n) -> new int[]{r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4)}, role, key, period.start);
        if (!previous.isEmpty()) {
            int[] event = previous.get(0);
            if (event[0] != action) { return new Result(100021); }
            return outcome(result, period.start, action, event[1], remaining, points);
        }
        int slot = 0;
        int item;
        int quantity;
        if (action == 0) {
            if (remaining <= 0 || remaining != expected) { return new Result(100013); }
            result.cost = draws[1] < rules.dailyFree ? 0 : rules.diamondsPerPaidSpin;
            if (result.cost > 0 && !inventory.spendMaterials(role, Collections.emptyMap(), 0, result.cost)) {
                return new Result(100012);
            }
            slot = randomSlot(period.start);
            int[] award = rules.pool[poolIndex(period.start, slot)];
            item = award[1];
            quantity = award[2];
            remaining--;
            points += rules.roundScorePerSpin;
        } else {
            int[] offer = rules.offers[action - 1];
            if (bought[action - 1] >= offer[1] || points < offer[0]) { return new Result(100013); }
            points -= offer[0];
            item = offer[2];
            quantity = offer[3];
        }
        jdbc.update("INSERT INTO local_turntable_event(role_id,request_key,period_start,event_day,action_index,"
                        + "wheel_slot,item_id,amount,remaining,points_after,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                role, key, period.start, period.day, action, slot, item, quantity, remaining, points, now);
        Map<Integer, Long> award = Collections.singletonMap(item, (long) quantity);
        result.granted = inventory.grantItemsOrMail(role, "turn:" + key, award);
        result.mailed = !award.equals(result.granted);
        result.changed = true;
        return outcome(result, period.start, action, slot, remaining, points);
    }

    /** 构造已提交操作的回执。
     * @param result 本次变动
     * @param period 周起点
     * @param action 操作类型
     * @param slot 中奖格
     * @param remaining 余次
     * @param points 可兑换积分
     * @return 原协议回包
     */
    private Result outcome(Result result, long period, int action, int slot, int remaining, int points) {
        result.remaining = remaining;
        if (action == 0) {
            result.body.put("reward", Collections.singletonList(sector(period, slot)));
            result.body.put("pool_num", remaining);
            result.body.put("my_info", Collections.emptyList());
            result.body.put("log", recent(period));
        } else { result.body.put("integral", points); }
        return result;
    }

    /** 根据本周起点轮换原转盘二十个显示格中的奖品顺序。
     * @param period 周起点
     * @param slot 从一开始的显示格
     * @return 原奖池的奖品下标
     */
    private int poolIndex(long period, int slot) {
        return (int) Math.floorMod(period / 604800L + slot - 1, (long) rules.pool.length);
    }

    /** 先按万分权重选奖，再等概率落到显示该奖的原格子。
     * @param period 周起点
     * @return 从一开始的中奖格
     */
    private int randomSlot(long period) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int roll = rng.nextInt(10000);
        int slot = 1;
        while ((roll -= rules.pool[poolIndex(period, slot)][0]) >= 0) { slot++; }
        return slot + rules.pool.length * rng.nextInt(SLOTS / rules.pool.length);
    }

    /** 原界面的一格物品及动画目标。
     * @param period 周起点
     * @param slot 从一开始的格号
     * @return class 为实物的格子
     */
    private Map<String, Object> sector(long period, int slot) {
        int[] award = rules.pool[poolIndex(period, slot)];
        return fields("class", 1, "subclass_itype", 1, "subclass_id", award[1], "subclass_num", award[2],
                "index", slot, "add_num", 0);
    }

    /** 查询本周最近十次真实抽奖。
     * @param period 周起点
     * @return 原跑马灯日志
     */
    private List<Map<String, Object>> recent(long period) {
        return jdbc.query("SELECT r.name,r.realm_id,e.wheel_slot FROM local_turntable_event e "
                + "JOIN local_game_role r ON r.id=e.role_id WHERE e.period_start=? AND e.action_index=0 "
                + "ORDER BY e.occurred_at DESC,e.request_key DESC LIMIT 10",
                (r, n) -> fields("name", r.getString(1), "svrid", r.getInt(2), "log", sector(period, r.getInt(3))), period);
    }

    /** 按真实抽取次数、达到该成绩的时间、角色编号排序。
     * @param period 周起点
     * @return 本周全部参与者的名次及累计积分
     */
    private List<Map<String, Object>> standings(long period) {
        return jdbc.query("SELECT r.id,r.name,r.realm_id,r.sex,r.avatar,COUNT(*) AS draws "
                        + "FROM local_turntable_event e JOIN local_game_role r ON r.id=e.role_id "
                        + "WHERE e.period_start=? AND e.action_index=0 GROUP BY r.id,r.name,r.realm_id,r.sex,r.avatar "
                        + "ORDER BY COUNT(*) DESC,MAX(e.occurred_at),r.id",
                (r, n) -> fields("uid", r.getString(1), "name", r.getString(2), "serverid", r.getInt(3),
                        "sex", r.getInt(4), "icon", r.getInt(5), "dress", 0,
                        "level", r.getInt(6) * rules.roundScorePerSpin), period);
    }

    /** 查找角色的真实名次。
     * @param ranks 真实榜单
     * @param role 角色编号
     * @return 未参与时返回零
     */
    private int place(List<Map<String, Object>> ranks, String role) {
        for (int i = 0; i < ranks.size(); i++) { if (role.equals(ranks.get(i).get("uid"))) { return i + 1; } }
        return 0;
    }

    /** 取指定名次的邮件及预览奖励。
     * @param rank 从一开始的名次
     * @return 原奖励三元组
     */
    private List<Integer> rankReward(int rank) {
        if (rank < 1 || rank > rules.rankRewards.length) { return Collections.emptyList(); }
        int[] row = rules.rankRewards[rank - 1];
        return Arrays.asList(row[0], row[1], row[2]);
    }

    /** 将已结束各周的真实名次固定并为得奖者补发邮件。
     * @param role 当前角色
     * @return 新增榜奖信件数
     */
    @Transactional
    public int settle(String role) {
        roles.lockRole(role);
        lockRound();
        long now = System.currentTimeMillis() / 1000;
        long current = calendar.cycleAt(ACTIVITY, now).start;
        List<Long> pending = jdbc.query("SELECT DISTINCT e.period_start FROM local_turntable_event e "
                        + "WHERE e.role_id=? AND e.action_index=0 AND e.period_start<? "
                        + "AND NOT EXISTS(SELECT 1 FROM local_turntable_settlement s WHERE s.role_id=e.role_id "
                        + "AND s.period_start=e.period_start)", (r, n) -> r.getLong(1), role, current);
        int mails = 0;
        for (long period : pending) {
            int rank = place(standings(period), role);
            List<Integer> reward = rankReward(rank);
            if (!reward.isEmpty()) {
                inventory.createRewardMail(role, "turn-rank:" + period + ":" + role, "跨服大转盘周榜",
                        "本周累计积分排名第" + rank + "，请查收附件。", now, now + 30L * 86400, reward);
                mails++;
            }
            jdbc.update("INSERT INTO local_turntable_settlement(role_id,period_start,rank_no) VALUES(?,?,?)",
                    role, period, rank);
        }
        return mails;
    }

    /** 让周末结算等待所有在途抽取提交，再确定该周最终名次。 */
    private void lockRound() {
        jdbc.queryForObject("SELECT starts_at FROM local_activity_calendar WHERE id=1 FOR UPDATE", Long.class);
    }

    /** 按原协议字段构造回包。
     * @param pairs 成对的键与值
     * @return 回包字段
     */
    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) { map.put((String) pairs[i], pairs[i + 1]); }
        return map;
    }

    /** 转盘周规则及数值配置。
     * @author tongruiyuan
     * @since 2026-11-20 10:40
     */
    private static final class Rules {
        public int days, dailyFree, maxPaidPerDay, diamondsPerPaidSpin, roundScorePerSpin;
        public int[][] pool, offers, rankRewards;
    }

    /** 原面板回包和需同步的资产变动。
     * @author tongruiyuan
     * @since 2026-11-20 10:40
     */
    public static final class Result {
        public final Map<String, Object> body;
        public long period;
        public int remaining, cost;
        public boolean changed, mailed;
        public Map<Integer, Long> granted = Collections.emptyMap();

        /** 建立原协议结果。
         * @param errno 结果码
         */
        Result(int errno) { body = fields("errno", errno); }
    }
}
