package com.pokemon.local.activitylogin;

import java.util.List;
import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;

/**
 * 七日登录到访、领奖记录及奖励时装所有权 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:03
 */
@Repository
@DependsOnDatabaseInitialization
class LoginActivityMapper {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    /**
     * 绑定本地角色数据库
     *
     * @param jdbc JDBC 模板
     * @param dataSource 角色数据库连接源
     */
    LoginActivityMapper(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /**
     * 在角色数据表就绪后初始化七日登录活动表
     */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-activity-login.sql")).execute(dataSource);
    }

    /**
     * 保存当天登录，调用方持有角色事务锁
     *
     * @param roleId 本地角色编号
     * @param periodStart 当前活动期起点
     * @param day 当前自然日在本期的序号
     */
    void registerDay(String roleId, long periodStart, int day) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM local_activity_login_day "
                        + "WHERE role_id = ? AND period_start = ? AND day_number = ?",
                Integer.class, roleId, periodStart, day);
        if (count == 0) {
            jdbc.update("INSERT INTO local_activity_login_day (role_id, period_start, day_number) VALUES (?, ?, ?)",
                    roleId, periodStart, day);
        }
    }

    /**
     * 查询当前周期已经到访的累计自然日数
     *
     * @param roleId 本地角色编号
     * @param periodStart 当前活动期起点
     * @return 去重后的到访自然日数量
     */
    int countDays(String roleId, long periodStart) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_activity_login_day WHERE role_id = ? AND period_start = ?",
                Integer.class, roleId, periodStart);
    }

    /**
     * 查询当前周期已领取的奖励序号
     *
     * @param roleId 本地角色编号
     * @param periodStart 当前活动期起点
     * @return 升序排列的奖励序号
     */
    List<Integer> findClaims(String roleId, long periodStart) {
        return jdbc.queryForList("SELECT reward_number FROM local_activity_login_claim "
                + "WHERE role_id = ? AND period_start = ? ORDER BY reward_number",
                Integer.class, roleId, periodStart);
    }

    /**
     * 在奖励发放事务内记录领取
     *
     * @param roleId 本地角色编号
     * @param periodStart 当前活动期起点
     * @param reward 奖励序号
     */
    void insertClaim(String roleId, long periodStart, int reward) {
        jdbc.update("INSERT INTO local_activity_login_claim (role_id, period_start, reward_number) VALUES (?, ?, ?)",
                roleId, periodStart, reward);
    }

    /**
     * 保存角色永久时装所有权，调用方持有角色事务锁
     *
     * @param roleId 本地角色编号
     * @param dressId 时装配置编号
     */
    void grantDress(String roleId, int dressId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM local_activity_login_dress "
                + "WHERE role_id = ? AND dress_id = ?", Integer.class, roleId, dressId);
        if (count == 0) {
            jdbc.update("INSERT INTO local_activity_login_dress (role_id, dress_id) VALUES (?, ?)", roleId, dressId);
        }
    }

    /**
     * 读取七日奖励获得的永久时装
     *
     * @param roleId 本地角色编号
     * @return 时装配置编号列表
     */
    List<Integer> dresses(String roleId) {
        return jdbc.queryForList("SELECT dress_id FROM local_activity_login_dress WHERE role_id = ? ORDER BY dress_id",
                Integer.class, roleId);
    }
}
