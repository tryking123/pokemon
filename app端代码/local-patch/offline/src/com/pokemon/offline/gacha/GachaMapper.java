package com.pokemon.offline.gacha;

import com.pokemon.offline.inventory.DiamondSpendMapper;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 本地扭蛋状态和请求结算记录 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:01
 */
@Repository
@DependsOnDatabaseInitialization
class GachaMapper {
    private final JdbcTemplate jdbc;
    private final DiamondSpendMapper diamondSpend;

    /**
     * 初始化扭蛋持久化对象
     *
     * @param jdbc JDBC 访问模板
     * @param diamondSpend 钻石消费流水
     */
    GachaMapper(JdbcTemplate jdbc, DiamondSpendMapper diamondSpend) {
        this.jdbc = jdbc;
        this.diamondSpend = diamondSpend;
    }

    /**
     * 建立依赖游戏角色的扭蛋存档表
     */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("gacha/schema.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 按已切换的业务日期读取普通次数及长期状态
     *
     * @param roleId 角色标识
     * @param day 当前北京时间业务日
     * @param dayStart 当天零点 UTC 秒数，用于识别旧存档已经领过的单抽
     * @param nextReset 次日零点 UTC 秒数
     * @param dailyAdvancedFree 当前是否配置每日高级免费单抽
     * @return 未创建记录时返回初始状态
     */
    GachaState state(String roleId, LocalDate day, long dayStart, long nextReset, boolean dailyAdvancedFree) {
        return jdbc.query("SELECT s.*, EXISTS (SELECT 1 FROM local_gacha_draw d WHERE d.role_id=s.role_id "
                + "AND d.draw_type=2 AND d.committed_at>=? AND d.committed_at<?) AS legacy_draw_today "
                + "FROM local_gacha_state s WHERE s.role_id=?", (row, index) -> {
            String savedDay = row.getString("normal_day");
            int used = day.toString().equals(savedDay) ? row.getInt("normal_used") : 0;
            String savedFreeDay = row.getString("advanced_free_day");
            LocalDate freeDay = savedFreeDay != null ? LocalDate.parse(savedFreeDay)
                    : row.getBoolean("legacy_draw_today") ? day : null;
            return new GachaState(day, used, row.getLong("normal_last"), row.getInt("normal_total"),
                    row.getInt("advanced_total"), freeDay, dailyAdvancedFree, row.getInt("friend_total"),
                    row.getLong("friend_points"), nextReset);
        }, dayStart, nextReset, roleId).stream().findFirst().orElseGet(() ->
                new GachaState(day, 0, 0, 0, 0, null, dailyAdvancedFree, 0, 0, nextReset));
    }

    /**
     * 保存当前抽奖状态
     *
     * @param roleId 角色标识
     * @param state 更新后的次数和时间
     * @return 写入行数
     */
    int save(String roleId, GachaState state) {
        return jdbc.update("MERGE INTO local_gacha_state "
                        + "(role_id,normal_day,normal_used,normal_last,normal_total,advanced_total,"
                        + "advanced_free_day,friend_total,friend_points) KEY (role_id) VALUES (?,?,?,?,?,?,?,?,?)",
                roleId, Date.valueOf(state.day.toString()), state.used, state.normalTime, state.normalTotal, state.advancedTotal,
                state.advancedFreeDay == null ? null : Date.valueOf(state.advancedFreeDay.toString()), state.friendTotal, state.friendPoints);
    }

    /**
     * 查询角色是否已经领取本批初始友情点。
     *
     * @param roleId 角色标识
     * @param grantKey 初始发放批次
     * @return 是否已发放
     */
    boolean hasFriendshipGrant(String roleId, String grantKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_gacha_friendship_grant WHERE role_id=? AND grant_key=?",
                Integer.class, roleId, grantKey) != 0;
    }

    /**
     * 在同一角色事务中登记本批友情点发放。
     *
     * @param roleId 角色标识
     * @param grantKey 初始发放批次
     */
    void recordFriendshipGrant(String roleId, String grantKey) {
        jdbc.update("INSERT INTO local_gacha_friendship_grant(role_id,grant_key) VALUES (?,?)", roleId, grantKey);
    }

    /**
     * 查找同一连接同一请求号的已结算记录
     *
     * @param roleId 角色标识
     * @param requestId 本地网关生成的唯一请求键
     * @return 既有抽奖记录
     */
    Optional<DrawReceipt> receipt(String roleId, String requestId) {
        return jdbc.query("SELECT * FROM local_gacha_draw WHERE role_id=? AND request_id=?", (row, index) -> {
            List<GachaDraw.Reward> rewards = new ArrayList<>();
            rewards.add(new GachaDraw.Reward(row.getInt("reward_type"), row.getInt("reward_id")));
            String additionalRewards = row.getString("reward_batch");
            if (!additionalRewards.isEmpty()) {
                for (String reward : additionalRewards.split(",")) {
                    String[] fields = reward.split(":");
                    rewards.add(new GachaDraw.Reward(Integer.parseInt(fields[0]), Integer.parseInt(fields[1])));
                }
            }
            return new DrawReceipt(row.getInt("draw_type"), rewards,
                    row.getInt("diamonds_spent"), row.getBoolean("coupon_spent"));
        }, roleId, requestId).stream().findFirst();
    }

    /**
     * 记录原子提交的奖励和扣费结果
     *
     * @param roleId 角色标识
     * @param requestId 连接和会话组成的唯一键
     * @param type 客户端抽奖类型
     * @param draw 已结算抽奖
     * @param now 提交时间 UTC 秒数
     * @return 写入行数
     */
    int saveReceipt(String roleId, String requestId, long type, GachaDraw draw, long now) {
        GachaDraw.Reward first = draw.rewards.get(0);
        String remaining = draw.rewards.stream().skip(1).map(reward -> reward.type + ":" + reward.id)
                .collect(Collectors.joining(","));
        return jdbc.update("INSERT INTO local_gacha_draw (role_id,request_id,draw_type,reward_type,reward_id,"
                        + "diamonds_spent,coupon_spent,committed_at,reward_batch) VALUES (?,?,?,?,?,?,?,?,?)",
                roleId, requestId, type, first.type, first.id, draw.diamondsSpent, draw.couponSpent, now, remaining);
    }

    /**
     * 原子扣除角色钻石，保护最低余额
     *
     * @param roleId 角色标识
     * @param cost 原配置要求的钻石数量
     * @return 更新行数，一行表示扣费
     */
    int spendDiamonds(String roleId, int cost) {
        return diamondSpend.debit(roleId, cost);
    }

    /**
     * 已提交扭蛋的原始抽取及奖励字段
     *
     * @author tongruiyuan
     * @since 2026-11-18 19:01
     */
    static final class DrawReceipt {
        final int type;
        final List<GachaDraw.Reward> rewards;
        final int spent;
        final boolean coupon;

        /**
         * 保存重复请求应当读取的已结算结果
         *
         * @param type 原抽取类型
         * @param rewards 按抽取顺序保存的奖励
         * @param spent 扣除钻石数
         * @param coupon 是否消耗券
         */
        DrawReceipt(int type, List<GachaDraw.Reward> rewards, int spent, boolean coupon) {
            this.type = type;
            this.rewards = rewards;
            this.spent = spent;
            this.coupon = coupon;
        }
    }
}
