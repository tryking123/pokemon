package com.pokemon.offline.activitydaily;

import java.time.Instant;
import java.util.List;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.dao.DuplicateKeyException;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Propagation;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 每日活跃的本地行为流水与领取记录。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:04
 */
@Service
@DependsOnDatabaseInitialization
public class DailyVitalityEvents {
    /** 邮箱附件直接增加的当日积分，与按完成次数折算的原任务分别记录。 */
    public static final int MAIL_POINTS = 1000106;
    private final JdbcTemplate jdbc;

    /**
     * 绑定本地数据库访问。
     *
     * @param jdbc 数据库访问模板
     */
    public DailyVitalityEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 初始化活动事件及领取表。 */
    @PostConstruct
    void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_vitality_migration(role_id VARCHAR(36) NOT NULL,day_start BIGINT NOT NULL,points BIGINT NOT NULL,PRIMARY KEY(role_id,day_start))");
        new ResourceDatabasePopulator(new ClassPathResource("schema-daily-vitality.sql"))
                .execute(jdbc.getDataSource());
    }

    /**
     * 在已确认业务动作的同一事务内登记活跃。同一来源跨日重放也只保留第一次。
     *
     * @param roleId 当前角色编号
     * @param task 底包活跃任务编号
     * @param receipt 已提交动作的唯一键
     * @param quantity 本次实际完成数量
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String roleId, int task, String receipt, int quantity) {
        if (task <= 0 || quantity <= 0 || receipt == null || receipt.isEmpty() || receipt.length() > 128) {
            throw new IllegalArgumentException("活跃事件缺少有效任务、来源或数量");
        }
        try {
            jdbc.update("INSERT INTO local_vitality_event(role_id,task_id,source_key,occurred_at,quantity) "
                    + "VALUES (?,?,?,?,?)", roleId, task, receipt, Instant.now().getEpochSecond(), quantity);
        } catch (DuplicateKeyException alreadyRecorded) {
            // 保留源事件首次实际完成的时间与数量。
        }
    }

    /**
     * 首次进入新日活规则时保留旧规则已累计的当日积分。
     * @param roleId 角色编号
     * @param day 当日开始秒
     * @param points 旧规则已累计积分
     * @return 只保存一次的旧规则结转积分
     */
    @Transactional
    public long preservePoints(String roleId,long day,long points) {

        List<Long> old=jdbc.queryForList("SELECT points FROM local_vitality_migration WHERE role_id=? AND day_start=?",Long.class,roleId,day);
        if(!old.isEmpty())return old.get(0);
        try {jdbc.update("INSERT INTO local_vitality_migration(role_id,day_start,points) VALUES(?,?,?)",roleId,day,points);}
        catch(DuplicateKeyException repeated) {return jdbc.queryForObject("SELECT points FROM local_vitality_migration WHERE role_id=? AND day_start=?",Long.class,roleId,day);}
        return points;
    }

    /**
     * 按任务读取一个自然日实际完成的数量。
     *
     * @param roleId 当前角色编号
     * @param start 当日开始 Unix 秒
     * @param end 次日开始 Unix 秒
     * @return 每个任务的有效流水数量合计
     */
    List<long[]> counts(String roleId, long start, long end) {
        return jdbc.query("SELECT task_id,SUM(quantity) AS total FROM local_vitality_event "
                        + "WHERE role_id=? AND occurred_at>=? AND occurred_at<? GROUP BY task_id",
                (row, index) -> new long[]{row.getInt("task_id"), row.getLong("total")}, roleId, start, end);
    }

    /**
     * 读取当日已经领取的档位。
     *
     * @param roleId 当前角色编号
     * @param dayStart 当日开始 Unix 秒
     * @return 按号码排列的档位
     */
    List<Integer> claims(String roleId, long dayStart) {
        return jdbc.queryForList("SELECT tier FROM local_vitality_claim WHERE role_id=? AND day_start=? ORDER BY tier",
                Integer.class, roleId, dayStart);
    }

    /**
     * 在角色锁内记录已核实的领奖资格。
     *
     * @param roleId 当前角色编号
     * @param dayStart 当日开始 Unix 秒
     * @param tier 领取档位
     */
    void claim(String roleId, long dayStart, int tier) {
        jdbc.update("INSERT INTO local_vitality_claim(role_id,day_start,tier) VALUES (?,?,?)", roleId, dayStart, tier);
    }

    /**
     * 读取当前自然日真实完成的快速战斗和扭蛋次数。
     *
     * @param roleId 当前角色编号
     * @param businessDay 北京时间日期字符串
     * @param start 当日开始 Unix 秒
     * @param end 次日开始 Unix 秒
     * @return 快速战斗记录数及实际扭蛋数，十连计十次
     */
    long[] baseCounts(String roleId, String businessDay, long start, long end) {
        long fast = jdbc.queryForObject("SELECT COUNT(*) FROM local_adventure_quicken WHERE role_id=? AND reward_day=?",
                Long.class, roleId, businessDay);
        long eggs = jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN draw_type=3 THEN 10 ELSE 1 END),0) "
                + "FROM local_gacha_draw WHERE role_id=? AND committed_at>=? AND committed_at<?",
                Long.class, roleId, start, end);
        return new long[]{fast, eggs};
    }
}
