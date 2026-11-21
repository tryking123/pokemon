package com.pokemon.offline.inventory;

import java.time.Instant;
import com.pokemon.offline.platform.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 角色钻石消费流水 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-19 10:10
 */
@Repository
@DependsOnDatabaseInitialization
public class DiamondSpendMapper {
    private final JdbcTemplate jdbc;

    /**
     * 绑定本地角色数据库。
     *
     * @param jdbc 数据库模板
     */
    public DiamondSpendMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 在钱包结算事务内扣除钻石并保存成功的消费流水。
     *
     * @param roleId 角色编号
     * @param amount 实际钻石售价
     * @return 受影响的角色行数
     */
    public int debit(String roleId, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("钻石售价不能为负数");
        }
        int changed = jdbc.update("UPDATE local_game_role SET diamonds=diamonds-? WHERE id=? AND diamonds>=?",
                amount, roleId, amount);
        if (changed == 1) {
            record(roleId, amount);
        }
        return changed;
    }

    /**
     * 为已在同一事务内结算的钻石消费记录扣费金额。
     *
     * @param roleId 角色编号
     * @param amount 本次实际扣费的钻石数
     */
    public void record(String roleId, long amount) {
        if (amount > 0) {
            jdbc.update("INSERT INTO local_diamond_spend(role_id,amount,spent_at) VALUES (?,?,?)",
                    roleId, amount, Instant.now().toEpochMilli());
        }
    }

    /**
     * 统计指定业务时段的钻石支出。
     *
     * @param roleId 角色编号
     * @param startMillis 时段开始 Unix 毫秒，包含开始时间
     * @param endMillis 时段结束 Unix 毫秒
     * @return 该时段已提交的钻石扣费总数
     */
    public long totalBetween(String roleId, long startMillis, long endMillis) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM local_diamond_spend "
                + "WHERE role_id=? AND spent_at>=? AND spent_at<?", Long.class, roleId, startMillis, endMillis);
    }
}
