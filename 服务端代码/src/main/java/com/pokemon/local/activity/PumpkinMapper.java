package com.pokemon.local.activity;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 捣蛋南瓜轮次 Mapper。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:37
 */
@Repository
class PumpkinMapper {
    private final JdbcTemplate jdbc;

    /**
     * 绑定活动存档。
     *
     * @param jdbc 数据库模板
     */
    PumpkinMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取当前活动周期内的南瓜层数、选奖、随机种子和已翻牌位。
     *
     * @param roleId 当前角色编号
     * @param period 本轮起点
     * @return 已有南瓜进度
     */
    Optional<PumpkinService.Board> find(String roleId, long period) {
        return jdbc.query("SELECT floor,jackpot,seed,opened FROM local_pumpkin_round WHERE role_id=? AND period_start=?",
                (row, index) -> new PumpkinService.Board(period, row.getInt("floor"), row.getInt("jackpot"),
                        row.getLong("seed"), row.getInt("opened")), roleId, period).stream().findFirst();
    }

    /**
     * 在活动事务中保存已经生效的选奖、翻牌或换层结果。
     *
     * @param roleId 当前角色编号
     * @param board 最新棋盘状态
     */
    void save(String roleId, PumpkinService.Board board) {
        jdbc.update("MERGE INTO local_pumpkin_round(role_id,period_start,floor,jackpot,seed,opened) "
                        + "KEY(role_id,period_start) VALUES(?,?,?,?,?,?)", roleId, board.period, board.floor,
                board.jackpot, board.seed, board.opened);
    }
}
