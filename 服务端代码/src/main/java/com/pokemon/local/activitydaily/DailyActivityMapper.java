package com.pokemon.local.activitydaily;

import java.util.List;
import javax.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;

/**
 * 在线计时和签到领取记录 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:12
 */
@Repository
@DependsOn("roleMapper")
public class DailyActivityMapper {

    private final JdbcTemplate jdbc;

    /**
     * 初始化签到活动数据访问模板。
     *
     * @param jdbc JDBC 操作模板
     */
    DailyActivityMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询初始体力和全部购买、兑换流水形成的当前余额。
     * @param role 角色编号
     * @return 实际体力余额
     */
    public long stamina(String role) {
        return jdbc.queryForObject("SELECT 100 + COALESCE(SUM(delta),0) FROM local_stamina_ledger WHERE role_id=?",
                Long.class, role);
    }

    /**
     * 查询同一天、同一方向的体力流水笔数。
     * @param role 角色编号
     * @param day 北京时间当日零点
     * @param delta 每笔增减量
     * @return 成交次数
     */
    public int staminaCount(String role, long day, int delta) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_stamina_ledger WHERE role_id=? AND day_start=? AND delta=?",
                Integer.class, role, day, delta);
    }

    /**
     * 查询同一笔操作的回执。
     * @param role 角色编号
     * @param receipt 连接和请求号
     * @return 历史已提交的变动量
     */
    List<Integer> staminaReceipt(String role, String receipt) {
        return jdbc.queryForList("SELECT delta FROM local_stamina_ledger WHERE role_id=? AND receipt=?",
                Integer.class, role, receipt);
    }

    /**
     * 保存一次购买或兑换；负流水同时作为普通副本的当日额外机会。
     * @param role 角色编号
     * @param receipt 请求流水号
     * @param day 北京时间当日零点
     * @param delta 每笔增减量
     */
    void recordStamina(String role, String receipt, long day, int delta) {
        jdbc.update("INSERT INTO local_stamina_ledger(role_id,receipt,day_start,delta) VALUES(?,?,?,?)", role, receipt, day, delta);
    }

    /** 创建计时和领取记录表。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-activity-daily.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 对同一角色累积去除重叠后的心跳区间。
     *
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @param from 已核实区间起点
     * @param to 已核实区间终点
     */
    void creditTime(String roleId, long cycle, long from, long to) {
        int changed = jdbc.update("UPDATE local_online_clock SET online_seconds=online_seconds+"
                + "GREATEST(0, ?-GREATEST(counted_until,?)),counted_until=GREATEST(counted_until,?) "
                + "WHERE role_id=? AND cycle_start=?", to, from, to, roleId, cycle);
        if (changed == 0) {
            jdbc.update("INSERT INTO local_online_clock (role_id,cycle_start,online_seconds,counted_until) "
                    + "VALUES (?,?,?,?)", roleId, cycle, to - from, to);
        }
    }

    /**
     * 查询当天已结算的在线秒数。
     *
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @return 已计入的在线秒数
     */
    int onlineSeconds(String roleId, long cycle) {
        List<Integer> times = jdbc.queryForList("SELECT online_seconds FROM local_online_clock "
                + "WHERE role_id=? AND cycle_start=?", Integer.class, roleId, cycle);
        return times.isEmpty() ? 0 : times.get(0);
    }

    /**
     * 查询已领取的在线档位数量。
     *
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @return 按顺序已领的档位数量
     */
    int onlineClaims(String roleId, long cycle) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_online_claim WHERE role_id=? AND cycle_start=?",
                Integer.class, roleId, cycle);
    }

    /**
     * 检查该连接的原请求是否已发过奖励。
     *
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @param receipt 连接和请求流水
     * @return 流水是否已提交
     */
    boolean hasReceipt(String roleId, long cycle, String receipt) {
        return !jdbc.queryForList("SELECT stage FROM local_online_claim WHERE role_id=? AND cycle_start=? AND receipt=?",
                Integer.class, roleId, cycle, receipt).isEmpty();
    }

    /**
     * 保存已发放的在线档位和请求去重凭证。
     *
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @param stage 奖励档位
     * @param receipt 连接和请求流水
     */
    void claimOnline(String roleId, long cycle, int stage, String receipt) {
        jdbc.update("INSERT INTO local_online_claim (role_id,cycle_start,stage,receipt) VALUES (?,?,?,?)",
                roleId, cycle, stage, receipt);
    }

    /**
     * 读取周期内已签到自然日。
     *
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @return 已签到的日序
     */
    List<Integer> signDays(String roleId, long cycle) {
        return jdbc.queryForList("SELECT day_index FROM local_attendance_day WHERE role_id=? AND cycle_start=?"
                + " ORDER BY day_index", Integer.class, roleId, cycle);
    }

    /**
     * 登记一条本地签到记录。
     *
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @param day 周期内日序
     * @param patchReceipt 补签请求唯一标识，当天签到传空
     */
    void sign(String roleId, long cycle, int day, String patchReceipt) {
        jdbc.update("INSERT INTO local_attendance_day (role_id,cycle_start,day_index,patch_receipt) VALUES (?,?,?,?)",
                roleId, cycle, day, patchReceipt);
        com.pokemon.local.activity.PassportProgress.record(jdbc, roleId, 49, 1);
    }

    /**
     * 检查指定补签请求是否已造成资产变更。
     *
     * @param roleId 角色编号
     * @param receipt 补签请求唯一标识
     * @return 请求是否已提交
     */
    boolean hasPatchReceipt(String roleId, String receipt) {
        return !jdbc.queryForList("SELECT day_index FROM local_attendance_day WHERE role_id=? AND patch_receipt=?",
                Integer.class, roleId, receipt).isEmpty();
    }

    /**
     * 统计本轮已经补齐的漏签日期。
     *
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @return 补签自然日数量
     */
    int patchCount(String roleId, long cycle) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_attendance_day WHERE role_id=? AND cycle_start=?"
                + " AND patch_receipt IS NOT NULL", Integer.class, roleId, cycle);
    }

    /**
     * 读取本周期内已发放的累签档位。
     *
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @return 已发放的累签档位编号
     */
    List<Integer> signClaims(String roleId, long cycle) {
        return jdbc.queryForList("SELECT reward_index FROM local_attendance_claim "
                + "WHERE role_id=? AND cycle_start=? ORDER BY reward_index", Integer.class, roleId, cycle);
    }

    /**
     * 保存本周期累签奖励的领取记录。
     *
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @param index 奖励档位
     */
    void claimSign(String roleId, long cycle, int index) {
        jdbc.update("INSERT INTO local_attendance_claim (role_id,cycle_start,reward_index) VALUES (?,?,?)",
                roleId, cycle, index);
    }
    /**
     * 读取本周期已领取的 VIP 追加签到序号。
     * @param roleId 角色编号
     * @param cycle 活动周期
     * @return 已领签到序号
     */
    List<Integer> vipClaims(String roleId, long cycle) {
        return jdbc.queryForList("SELECT reward_index FROM local_attendance_vip WHERE role_id=? AND cycle_start=? ORDER BY reward_index", Integer.class, roleId, cycle);
    }

    /**
     * 保存已结算的 VIP 签到奖档。
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @param index 签到序号
     */
    void recordVip(String roleId, long cycle, int index) {
        jdbc.update("INSERT INTO local_attendance_vip(role_id,cycle_start,reward_index) VALUES(?,?,?)", roleId, cycle, index);
    }
}
