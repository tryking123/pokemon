package com.pokemon.offline.activity;

import java.util.Optional;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import javax.sql.DataSource;
import com.pokemon.offline.platform.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 开服排行榜的结算进度 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:40
 */
@Repository
@DependsOnDatabaseInitialization
class OpeningRankMapper {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    /**
     * 绑定本地数据库
     *
     * @param jdbc JDBC 模板
     * @param dataSource 数据库连接源
     */
    OpeningRankMapper(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /**
     * 初始化榜单进度表
     */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-opening-rank.sql")).execute(dataSource);
    }

    /**
     * 读取最早尚未结算的周期截止点
     *
     * @param roleId 榜单角色编号
     * @param activityId 运营活动编号
     * @return Unix 秒，无记录为空
     */
    Optional<Long> find(String roleId, int activityId) {
        return jdbc.queryForList("SELECT next_end FROM local_opening_rank_cursor "
                + "WHERE role_id = ? AND activity_id = ?", Long.class, roleId, activityId).stream().findFirst();
    }

    /**
     * 角色锁内写入之后要处理的截止点
     *
     * @param roleId 榜单角色编号
     * @param activityId 运营活动编号
     * @param end 首个或下一个截止点 Unix 秒
     */
    void save(String roleId, int activityId, long end) {
        jdbc.update("MERGE INTO local_opening_rank_cursor(role_id, activity_id, next_end) KEY(role_id, activity_id) "
                + "VALUES (?, ?, ?)", roleId, activityId, end);
    }
}
