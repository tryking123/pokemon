package com.pokemon.offline.activity;

import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 本地区服的周期依据和活动排行榜 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:04
 */
@Repository
class ActivityMapper {
    private final JdbcTemplate jdbc;

    /**
     * 初始化活动数据访问
     *
     * @param jdbc 数据库模板
     */
    ActivityMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取此前已保存的周期起点
     *
     * @return 自然日起点 Unix 秒
     */
    Optional<Long> findAnchor() {
        return jdbc.query("SELECT starts_at FROM local_activity_calendar WHERE id = 1",
                (row, index) -> row.getLong("starts_at")).stream().findFirst();
    }

    /**
     * 为新数据库保存不会因重启变化的活动起点
     *
     * @param start 时间戳
     */
    void insertAnchor(long start) {
        jdbc.update("INSERT INTO local_activity_calendar(id, starts_at) VALUES(1, ?)", start);
    }

    /**
     * 查询角色是否属于本地排行榜参与账号
     *
     * @param roleId 当前角色编号
     * @return 角色是否属于 admin
     */
    boolean isRankedRole(String roleId) {
        Integer eligible = jdbc.queryForObject("SELECT COUNT(*) FROM local_game_role r "
                + "JOIN local_account a ON r.account_id = a.id WHERE r.id = ? AND a.username = 'admin'",
                Integer.class, roleId);
        return eligible != null && eligible > 0;
    }

    /**
     * 按本期结算时间累计真实模拟充值金额
     *
     * @param roleId 当前角色编号
     * @param begin 起点，Unix 毫秒
     * @param end 终点，Unix 毫秒
     * @return 总计金额，单位分
     */
    long rechargeSum(String roleId, long begin, long end) {
        Long amount = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM local_recharge_activity_order "
                        + "WHERE role_id = ? AND confirmed_at >= ? AND confirmed_at < ?",
                Long.class, roleId, begin, end);
        return amount != null ? amount : 0;
    }

    /**
     * 统计指定周期中各个活动动作的成功次数。
     *
     * @param roleId 当前角色编号
     * @param period 运营周期
     * @return 按动作编号索引的成功次数
     */
    Map<Integer, Integer> operatingActions(String roleId, ActivityPeriod period) {
        Map<Integer, Integer> actions = new HashMap<>();
        jdbc.query("SELECT action_index, COUNT(*) FROM local_operating_activity_action "
                        + "WHERE role_id=? AND activity_id=? AND period_start=? GROUP BY action_index",
                row -> { actions.put(row.getInt(1), row.getInt(2)); }, roleId, period.id, period.start);
        return actions;
    }

    /**
     * 查询同一游戏连接的兑换请求是否已经成功提交。
     *
     * @param roleId 当前角色编号
     * @param requestKey 本次请求编号
     * @return 角色已处理过该请求时为真
     */
    boolean hasExchangeRequest(String roleId, String requestKey) {
        return hasOperatingRequest(roleId, OperatingActivityRules.EXCHANGE, requestKey);
    }

    /**
     * 查询活动是否已结算同一个协议请求。
     *
     * @param roleId 当前角色编号
     * @param activityId 运营活动编号
     * @param requestKey 本次请求编号
     * @return 角色已处理过该请求时为真
     */
    boolean hasOperatingRequest(String roleId, int activityId, String requestKey) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM local_operating_activity_action "
                        + "WHERE role_id=? AND activity_id=? AND request_key=?",
                Integer.class, roleId, activityId, requestKey);
        return count != null && count != 0;
    }

    /**
     * 汇总当期普通和高级扭蛋实际扣除的钻石。
     *
     * @param roleId 当前角色编号
     * @param period 运营周期
     * @return 已提交的钻石消耗总量
     */
    long gachaDiamonds(String roleId, ActivityPeriod period) {
        Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(diamonds_spent), 0) FROM local_gacha_draw "
                        + "WHERE role_id=? AND draw_type IN (1,2,3) AND committed_at>=? AND committed_at<?",
                Long.class, roleId, period.start, period.end);
        return sum == null ? 0 : sum;
    }

    /**
     * 写入已完成资产结算的运营动作。
     *
     * @param roleId 当前角色编号
     * @param period 运营周期
     * @param action 动作编号
     * @param requestKey 协议重放识别键，周期内固定动作可用同一个键
     */
    void recordOperatingAction(String roleId, ActivityPeriod period, int action, String requestKey) {
        jdbc.update("INSERT INTO local_operating_activity_action(role_id,activity_id,period_start,action_index,request_key) "
                + "VALUES(?,?,?,?,?)", roleId, period.id, period.start, action, requestKey);
    }

    /**
     * 统计一个周期内已结算的高级扭蛋次数。
     *
     * @param roleId 当前角色编号
     * @param begin 周期开始的 Unix 秒
     * @param end 周期结束的 Unix 秒
     * @return 单抽与十连按实际抽数合计
     */
    long advancedDrawSum(String roleId, long begin, long end) {
        Long count = jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN draw_type=3 THEN 10 ELSE 1 END), 0) "
                        + "FROM local_gacha_draw WHERE role_id=? AND draw_type IN (2,3) "
                        + "AND committed_at>=? AND committed_at<?",
                Long.class, roleId, begin, end);
        return count == null ? 0 : count;
    }
}
