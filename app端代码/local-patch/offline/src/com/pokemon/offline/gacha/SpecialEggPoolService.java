package com.pokemon.offline.gacha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
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
 * 超级蛋池目标、刷新费用及高级扭蛋幸运值结算。
 *
 * @author tongruiyuan
 * @since 2026-11-19 21:08
 */
@Service
public class SpecialEggPoolService {
    private final ActivityService calendar;
    private final RoleService roles;
    private final GachaMapper gacha;
    private final JdbcTemplate jdbc;
    private final Map<Integer, int[]> targets = new LinkedHashMap<>();
    private final int[] prices;
    private final int[] redAwards;
    private final int luckPerDraw;

    /**
     * 加载原奖池、手动刷新价目和普通高级十抽保底池。
     * @param calendar 活动周历
     * @param roles 角色锁
     * @param gacha 扭蛋货币账本
     * @param jdbc 蛋池存档
     * @param json 配置解析器
     * @throws IOException 运行资源读取失败
     */
    SpecialEggPoolService(ActivityService calendar, RoleService roles, GachaMapper gacha,
                          JdbcTemplate jdbc, ObjectMapper json) throws IOException {
        this.calendar = calendar;
        this.roles = roles;
        this.gacha = gacha;
        this.jdbc = jdbc;
        try (InputStream input = new ClassPathResource("gacha/special.json").getInputStream()) {
            JsonNode config = json.readTree(input);
            for (int[] row : json.convertValue(config.get("pool"), int[][].class)) {
                targets.put(row[2], row);
            }
            prices = json.convertValue(config.get("price"), int[].class);
            redAwards = json.convertValue(config.get("redAwards"), int[].class);
            luckPerDraw = config.get("lucky").asInt();
        }
    }

    /**
     * 本次原协议请求正在使用的角色蛋池。
     * @author tongruiyuan
     * @since 2026-11-19 21:08
     */
    static final class Pool {
        final ActivityPeriod period;
        final long day;
        int target;
        int luck;
        int refreshes;

        /**
         * 还原当前自然日、轮次和角色已保存的目标。
         * @param period 活动轮次
         * @param day 北京时间自然日起点
         * @param target 目标精灵
         * @param luck 累计幸运值
         * @param refreshes 当日已刷新次数
         */
        Pool(ActivityPeriod period, long day, int target, int luck, int refreshes) {
            this.period = period;
            this.day = day;
            this.target = target;
            this.luck = luck;
            this.refreshes = refreshes;
        }
    }

    /**
     * 在已有角色锁内读取本轮蛋池，首开时按原权重选出目标。
     * @param roleId 角色编号
     * @return 已开放的蛋池，轮次尚未开始时为空
     */
    Pool load(String roleId) {
        ActivityPeriod period = calendar.cycle(10039);
        long now = Instant.now().getEpochSecond();
        if (now < period.start || now >= period.end) {
            return null;
        }
        long day = Math.floorDiv(now + 28800, 86400) * 86400 - 28800;
        List<Pool> rows = jdbc.query("SELECT target_id,luck,refresh_day,refresh_count FROM local_gacha_special "
                + "WHERE role_id=? AND period_start=?", (row,i) -> new Pool(period, day, row.getInt(1),
                row.getInt(2), row.getLong(3) == day ? row.getInt(4) : 0), roleId, period.start);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        Pool pool = new Pool(period, day, selectTarget(0), 0, 0);
        jdbc.update("INSERT INTO local_gacha_special(role_id,period_start,target_id,luck,refresh_day,refresh_count) "
                + "VALUES(?,?,?,?,?,?)", roleId, period.start, pool.target, 0, day, 0);
        return pool;
    }

    /**
     * 按原六元组首位作为相对权重，手动刷新时剔除原目标。
     * @param previous 已展示的精灵，新轮为零
     * @return 新目标编号
     */
    private int selectTarget(int previous) {
        int sum = targets.values().stream().filter(row -> row[2] != previous).mapToInt(row -> row[0]).sum();
        int ticket = ThreadLocalRandom.current().nextInt(sum);
        for (int[] row : targets.values()) {
            if (row[2] != previous && (ticket -= row[0]) < 0) {
                return row[2];
            }
        }
        throw new IllegalStateException("超级蛋池权重错误");
    }

    /**
     * 生成 S2039 使用的当前轮次完整快照。
     * @param roleId 角色编号
     * @return 目标、幸运值、结束秒数和刷新次数
     */
    @Transactional
    public Map<String, Object> snapshot(String roleId) {
        roles.lockRole(roleId);
        Pool pool = load(roleId);
        if (pool == null) {
            return Collections.emptyMap();
        }
        int[] row = targets.get(pool.target);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itype", row[1]);
        result.put("id", row[2]);
        result.put("num", row[3]);
        result.put("guarante", row[5]);
        result.put("next_time", pool.period.end);
        result.put("cur_guarante", pool.luck);
        result.put("min_guarante", row[4]);
        result.put("refresh", pool.refreshes);
        return result;
    }

    /**
     * 按界面已显示的轮次和每日阶梯价更换目标，保存钻石流水与唯一回执。
     * @param roleId 角色编号
     * @param expectedEnd 已推送的活动结束时刻
     * @param expectedTarget 已显示的神兽编号
     * @param expectedRefresh 已显示的刷新次数
     * @param requestKey 本次请求回执
     * @return 原协议错误码
     */
    @Transactional
    public int refresh(String roleId, long expectedEnd, int expectedTarget, int expectedRefresh, String requestKey) {
        roles.lockRole(roleId);
        Pool pool = load(roleId);
        if (pool == null || pool.period.end != expectedEnd) {
            return 100013;
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM local_gacha_special_refresh WHERE role_id=? AND request_key=?",
                Integer.class, roleId, requestKey) > 0) {
            return 100011;
        }
        if (pool.target != expectedTarget || pool.refreshes != expectedRefresh) {
            return 100013;
        }
        if (gacha.spendDiamonds(roleId, prices[Math.min(pool.refreshes, prices.length - 1)]) != 1) {
            return 100006;
        }
        pool.target = selectTarget(pool.target);
        pool.refreshes = Math.addExact(pool.refreshes, 1);
        save(roleId, pool);
        jdbc.update("INSERT INTO local_gacha_special_refresh(role_id,request_key) VALUES(?,?)", roleId, requestKey);
        return 0;
    }

    /**
     * 单抽增加原幸运值，按目标阈值区间结算神兽或当前十抽红宠保底。
     * @param pool 当前角色的周蛋池
     * @param drawNumber 本次高级扭蛋的一基历史序号
     * @param standard 原本次抽取奖励
     * @return 实际发放的精灵或物品
     */
    GachaDraw.Reward advance(Pool pool, int drawNumber, GachaDraw.Reward standard) {
        int[] row = targets.get(pool.target);
        pool.luck = Math.addExact(pool.luck, luckPerDraw);
        if (pool.luck >= row[4] && (pool.luck >= row[5]
                || ThreadLocalRandom.current().nextInt(row[5] - pool.luck + 1) == 0)) {
            pool.luck = 0;
            return new GachaDraw.Reward(GachaService.ELF_REWARD, pool.target);
        }
        if (drawNumber % 10 == 0) {
            return new GachaDraw.Reward(GachaService.ELF_REWARD,
                    redAwards[ThreadLocalRandom.current().nextInt(redAwards.length)]);
        }
        return standard;
    }

    /**
     * 与实际扣费、奖励和抽卡回执同时提交池状态。
     * @param roleId 角色编号
     * @param pool 已完成本次行动的池
     */
    void save(String roleId, Pool pool) {
        jdbc.update("UPDATE local_gacha_special SET target_id=?,luck=?,refresh_day=?,refresh_count=? "
                        + "WHERE role_id=? AND period_start=?", pool.target, pool.luck, pool.day, pool.refreshes,
                roleId, pool.period.start);
    }
}
