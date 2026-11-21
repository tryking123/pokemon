package com.pokemon.offline.hangup;

import com.pokemon.offline.hangup.api.entity.HangupState;
import java.util.List;
import java.util.Collections;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.context.annotation.DependsOn;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 冒险挂机收益 Mapper。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:15
 */
@Repository
@DependsOn("roleMapper")
class HangupMapper {
    private final JdbcTemplate jdbc;

    /**
     * 创建挂机收益数据访问。
     *
     * @param jdbc 本地数据库模板
     */
    HangupMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询当天已结算的超速闯关次数。
     * @param roleId 角色编号
     * @param day 北京时间业务日
     * @return 已提交批次数
     */
    int fastTimes(String roleId, String day) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_adventure_fast WHERE role_id=? AND reward_day=?",
                Integer.class, roleId, day);
    }

    /**
     * 查询当前角色是否已结算指定超速请求。
     * @param roleId 角色编号
     * @param key 请求回执
     * @return 已结算时为真
     */
    boolean usedFast(String roleId, String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_adventure_fast WHERE role_id=? AND request_id=?",
                Integer.class, roleId, key) > 0;
    }

    /**
     * 在角色事务中登记超速闯关的请求回执。
     * @param roleId 角色编号
     * @param day 北京时间业务日
     * @param key 请求回执
     * @return 首次登记时为真
     */
    boolean recordFast(String roleId, String day, String key) {
        return jdbc.update("INSERT INTO local_adventure_fast(role_id,reward_day,request_id) SELECT ?,?,? "
                + "WHERE NOT EXISTS (SELECT 1 FROM local_adventure_fast WHERE role_id=? AND request_id=?)",
                roleId, day, key, roleId, key) == 1;
    }

    /**
     * 读取此连接请求的业务回执。
     * @param roleId 角色编号
     * @param receipt 连接及请求编号
     * @return 是否已执行
     */
    boolean handled(String roleId,String receipt) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_hangup_action WHERE role_id=? AND receipt=?",
                Integer.class,roleId,receipt)>0;
    }

    /**
     * 与切换或领取在同一角色锁内写入回执。
     * @param roleId 角色编号
     * @param receipt 连接及请求编号
     */
    void record(String roleId,String receipt) {
        jdbc.update("INSERT INTO local_hangup_action(role_id,receipt) VALUES(?,?)",roleId,receipt);
    }

    /** 初始化挂机收益表。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-hangup.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 读取角色现存的挂机收益。
     *
     * @param roleId 角色编号
     * @return 已保存状态，尚未开始挂机时为空
     */
    HangupState load(String roleId) {
        return jdbc.query("SELECT map_id,credited_at,pending_seconds,coins,role_exp,elf_exp,elf_pool,dungeon_group,dungeon_seconds "
                        + "FROM local_adventure_hangup WHERE role_id=?", (row, index) ->
                        new HangupState(row.getInt(1), row.getLong(2), row.getInt(3), row.getLong(4),
                                row.getLong(5), row.getLong(6), row.getLong(7), Collections.emptyMap(), row.getInt(8), row.getInt(9)), roleId).stream().findFirst().orElse(null);
    }

    /**
     * 从已经领取的精灵经验池扣除本次升级消耗。
     *
     * @param roleId 角色编号
     * @param amount 消耗经验
     * @return 更新条数
     */
    int spendElfPool(String roleId, long amount) {
        return jdbc.update("UPDATE local_adventure_hangup SET elf_pool=elf_pool-? WHERE role_id=? AND elf_pool>=?",
                amount, roleId, amount);
    }

    /**
     * 在角色事务内保存收益及时间。
     *
     * @param roleId 角色编号
     * @param state 要保存的收益
     */
    void save(String roleId, HangupState state) {
        jdbc.update("MERGE INTO local_adventure_hangup (role_id,map_id,credited_at,pending_seconds,coins,role_exp,"
                        + "elf_exp,elf_pool,dungeon_group,dungeon_seconds) KEY(role_id) VALUES (?,?,?,?,?,?,?,?,?,?)", roleId, state.mapId, state.creditedAt,
                state.pendingSeconds, state.coins, state.roleExp, state.elfExp, state.elfPool, state.dungeonGroup, state.dungeonSeconds);
    }

    /**
     * 查询普通挂机已领取的累计计时，保留未凑满一抽的余量。
     *
     * @param roleId 角色编号
     * @return 以前结算过的挂机秒数
     */
    long settledSeconds(String roleId) {
        return jdbc.query("SELECT settled_seconds FROM local_adventure_drop_cursor WHERE role_id=?",
                (row, index) -> row.getLong(1), roleId).stream().findFirst().orElse(0L);
    }

    /**
     * 与普通挂机收益一起保存累计计时。
     *
     * @param roleId 已锁定角色
     * @param seconds 已结算的全部秒数
     */
    void saveSettledSeconds(String roleId, long seconds) {
        jdbc.update("MERGE INTO local_adventure_drop_cursor (role_id,settled_seconds) KEY(role_id) VALUES (?,?)",
                roleId, seconds);
    }

    /**
     * 查询已领取地图通关奖励的地图编号。
     *
     * @param roleId 角色编号
     * @return 原始地图编号
     */
    List<Integer> claimedMaps(String roleId) {
        return jdbc.query("SELECT map_id FROM local_adventure_map_reward WHERE role_id=? ORDER BY map_id",
                (row, index) -> row.getInt(1), roleId);
    }

    /**
     * 在角色事务内登记地图通关奖励。
     *
     * @param roleId 已锁定的角色编号
     * @param mapId 奖励对应地图
     */
    void claimMap(String roleId, int mapId) {
        jdbc.update("INSERT INTO local_adventure_map_reward (role_id,map_id) VALUES (?,?)", roleId, mapId);
    }

    /**
     * 统计快速战斗次数，或检查某个请求是否已经结算。
     *
     * @param roleId 角色编号
     * @param day 北京自然日
     * @param requestId 查询单次请求用的连接和请求号，查询当天全部时为空
     * @return 满足条件的已结算次数
     */
    int quickenCount(String roleId, String day, String requestId) {
        if (requestId == null) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM local_adventure_quicken WHERE role_id=? AND reward_day=?",
                    Integer.class, roleId, day);
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_adventure_quicken WHERE role_id=? AND request_id=?",
                Integer.class, roleId, requestId);
    }

    /**
     * 将已发放的快速战斗奖励写入同一角色事务。
     *
     * @param roleId 已锁定角色
     * @param day 北京自然日
     * @param requestId 当前连接中本次请求的唯一键
     */
    void recordQuicken(String roleId, String day, String requestId) {
        jdbc.update("INSERT INTO local_adventure_quicken (role_id,request_id,reward_day) VALUES (?,?,?)",
                roleId, requestId, day);
    }
}
