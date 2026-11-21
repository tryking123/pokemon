package com.pokemon.local.activity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 七日目标的秘境挑战、个体值洗炼与装备熔炼计数 Mapper。
 *
 * @author tongruiyuan
 * @since 2026-11-19 19:37
 */
@Repository
public class CarnivalProgressMapper {
    private final JdbcTemplate jdbc;

    /**
     * 绑定活动事件数据库。
     * @param jdbc 数据库访问模板
     */
    public CarnivalProgressMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 在业务操作持有角色锁的事务内，累计北京时间当天的成功次数或件数。
     * @param roleId 角色编号
     * @param task 原任务类别
     * @param quantity 实际挑战次数、洗炼次数或熔炼件数
     */
    public void record(String roleId, int task, int quantity) {
        long start = Math.floorDiv(Instant.now().getEpochSecond() + 28800, 86400) * 86400 - 28800;
        if (jdbc.update("UPDATE local_carnival_progress SET quantity=quantity+? WHERE role_id=? "
                + "AND task_type=? AND event_day=?", quantity, roleId, task, start) == 0) {
            jdbc.update("INSERT INTO local_carnival_progress(role_id,task_type,event_day,quantity) VALUES(?,?,?,?)",
                    roleId, task, start, quantity);
        }
    }

    /**
     * 汇总活动时间窗内各项已记录的动作。
     * @param roleId 当前角色
     * @param start 开始秒数
     * @param end 结束秒数
     * @return 按原任务类别索引的数量
     */
    Map<Integer, Long> totals(String roleId, long start, long end) {
        Map<Integer, Long> values = new HashMap<>();
        jdbc.query("SELECT task_type,SUM(quantity) FROM local_carnival_progress WHERE role_id=? AND "
                        + "event_day>=? AND event_day<? GROUP BY task_type",
                row -> { values.put(row.getInt(1), row.getLong(2)); }, roleId, start, end);
        return values;
    }
}
