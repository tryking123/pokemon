package com.pokemon.offline.role;

import com.pokemon.offline.role.api.entity.RoleCosmeticState;
import java.util.List;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.context.annotation.DependsOn;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 本地角色外观及称号请求回执 Mapper。
 *
 * @author tongruiyuan
 * @since 2026-11-19 13:34
 */
@Repository
@DependsOn("roleMapper")
class RoleCosmeticMapper {
    private final JdbcTemplate jdbc;

    /**
     * 注入本地数据库操作模板。
     *
     * @param jdbc 访问角色存档的模板
     */
    RoleCosmeticMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 为已有或新建存档准备外观表和请求回执表。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-role-cosmetics.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 读取同一角色已提交的外观快照。
     *
     * @param roleId 角色主键
     * @return 当前选择，旧档无记录时使用默认外观
     */
    RoleCosmeticState find(String roleId) {
        List<RoleCosmeticState> rows = jdbc.query(
                "SELECT equipped_title,displayed_title,worn_dress FROM local_role_cosmetic WHERE role_id=?",
                (row, i) -> new RoleCosmeticState(row.getInt(1), row.getInt(2), row.getInt(3)), roleId);
        return rows.isEmpty() ? new RoleCosmeticState(0, 0, 0) : rows.get(0);
    }

    /**
     * 在角色锁保护下写入整份外观快照。
     *
     * @param roleId 角色主键
     * @param state 已核验的外观状态
     * @return 插入或更新的行数
     */
    int save(String roleId, RoleCosmeticState state) {
        int changed = jdbc.update("UPDATE local_role_cosmetic SET equipped_title=?,displayed_title=?,worn_dress=?"
                        + " WHERE role_id=?", state.equippedTitle, state.displayedTitle, state.wornDress, roleId);
        return changed != 0 ? changed : jdbc.update(
                "INSERT INTO local_role_cosmetic(role_id,equipped_title,displayed_title,worn_dress) VALUES (?,?,?,?)",
                roleId, state.equippedTitle, state.displayedTitle, state.wornDress);
    }

    /**
     * 查询称号切换请求是否已经结算。
     *
     * @param roleId 角色主键
     * @param requestId 同一连接内的请求标识
     * @return 已保存回执时为真
     */
    boolean hasReceipt(String roleId, String requestId) {
        return !jdbc.queryForList("SELECT request_id FROM local_role_cosmetic_receipt WHERE role_id=? AND request_id=?",
                String.class, roleId, requestId).isEmpty();
    }

    /**
     * 保存已结算请求回执，连接重放将沿用已提交外观。
     *
     * @param roleId 角色主键
     * @param requestId 同一连接内的请求标识
     * @return 插入的行数
     */
    int record(String roleId, String requestId) {
        return jdbc.update("INSERT INTO local_role_cosmetic_receipt(role_id,request_id) VALUES (?,?)", roleId, requestId);
    }
}
