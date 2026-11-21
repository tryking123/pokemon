package com.pokemon.local.payment;

import com.pokemon.local.payment.api.entity.GamePaymentOrder;
import java.util.Optional;
import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;

/**
 * 新手充值活动的已确认模拟订单与领取记录 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:25
 */
@Repository
@DependsOnDatabaseInitialization
class FirstRechargeMapper {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    /**
     * 绑定本地订单数据库
     *
     * @param jdbc JDBC 模板
     * @param dataSource 本地数据库连接源
     */
    FirstRechargeMapper(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /**
     * 在订单表初始化后创建返利领取表
     */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-first-recharge.sql")).execute(dataSource);
    }

    /**
     * 查询活动期首笔符合档位的已确认模拟订单
     *
     * @param roleId 当前角色编号
     * @param begin 活动期起点，包含该毫秒
     * @param end 活动期截止，不包含该毫秒
     * @return 无符合条件则为空
     */
    Optional<GamePaymentOrder> firstPaidOrder(String roleId, long begin, long end) {
        return jdbc.query("SELECT * FROM local_game_payment_order WHERE role_id = ? "
                        + "AND paysid BETWEEN 8 AND 11 AND confirmed_at >= ? AND confirmed_at < ? "
                        + "ORDER BY confirmed_at, id LIMIT 1",
                (row, index) -> new GamePaymentOrder(row.getString("id"), roleId, row.getInt("paysid"),
                        row.getLong("amount"), row.getLong("reward"), row.getLong("expires_at"), true),
                roleId, begin, end).stream().findFirst();
    }

    /**
     * 查询已经领取的活动期档位
     *
     * @param roleId 当前角色编号
     * @param periodStart 活动期起点 Unix 秒
     * @return 已领取档位，无记录则为空
     */
    Optional<Integer> claimed(String roleId, long periodStart) {
        return jdbc.queryForList("SELECT paysid FROM local_first_recharge_claim WHERE role_id = ? AND period_start = ?",
                Integer.class, roleId, periodStart).stream().findFirst();
    }

    /**
     * 在返利发放事务内锁定该期唯一一次领取
     *
     * @param roleId 当前角色编号
     * @param periodStart 活动期起点 Unix 秒
     * @param order 发放所依据的已确认模拟订单
     */
    void insertClaim(String roleId, long periodStart, GamePaymentOrder order) {
        jdbc.update("INSERT INTO local_first_recharge_claim(role_id, period_start, order_id, paysid) VALUES (?,?,?,?)",
                roleId, periodStart, order.id, order.paysid);
    }
}
