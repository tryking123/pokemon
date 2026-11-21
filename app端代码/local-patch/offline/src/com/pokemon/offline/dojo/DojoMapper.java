package com.pokemon.offline.dojo;

import com.pokemon.offline.dojo.api.entity.DojoPlacement;
import java.util.List;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.context.annotation.DependsOn;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 道馆进度、道馆阵容和助阵存档 Mapper。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:54
 */
@Repository
@DependsOn("elfMapper")
class DojoMapper {
    private final JdbcTemplate jdbc;

    /**
     * 检查精灵是否正在火箭队的独立编队。
     * @param roleId 当前角色
     * @param elfId 精灵实例
     * @return 是否已被占用
     */
    boolean rocketMember(String roleId,String elfId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_rocket_slot WHERE role_id=? AND elf_id=?",Integer.class,roleId,elfId)>0;
    }

    /**
     * 创建道馆数据访问。
     *
     * @param jdbc 本地数据库操作模板
     */
    DojoMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 初始化道馆和助阵存档。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-dojo.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 读取角色历史最高道馆与当前道馆的进度。
     *
     * @param roleId 角色标识
     * @return 道馆进度，新档时返回首座道馆
     */
    Progress progress(String roleId) {
        return jdbc.query("SELECT history,dojo_id,tactics,defeated_mask,attempt_day,failures,practices "
                        + "FROM local_dojo_progress WHERE role_id=?", (r, n) ->
                        new Progress(r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4), r.getLong(5),
                                r.getInt(6), r.getInt(7)), roleId).stream().findFirst()
                .orElseGet(() -> new Progress(0, 1001, 0, 0, 0, 0, 0));
    }

    /**
     * 写入角色的道馆进度。
     *
     * @param roleId 角色标识
     * @param progress 当前已验证进度
     * @return 更新行数
     */
    int saveProgress(String roleId, Progress progress) {
        return jdbc.update("MERGE INTO local_dojo_progress "
                        + "(role_id,history,dojo_id,tactics,defeated_mask,attempt_day,failures,practices) "
                        + "KEY(role_id) VALUES (?,?,?,?,?,?,?,?)", roleId, progress.history, progress.dojo,
                progress.tactics, progress.defeated, progress.day, progress.failures, progress.practices);
    }

    /**
     * 查询角色在北京自然日的同一座道馆扫荡次数。
     *
     * @param roleId 角色标识
     * @param dojoId 道馆编号
     * @param day 北京自然日序号
     * @return 已经结算的次数
     */
    int sweepCount(String roleId, int dojoId, long day) {
        return jdbc.query("SELECT sweep_count FROM local_dojo_sweep WHERE role_id=? AND dojo_id=? AND sweep_day=?",
                (row, index) -> row.getInt(1), roleId, dojoId, day).stream().findFirst().orElse(0);
    }

    /**
     * 保存角色对同一座道馆的今日扫荡计数。
     *
     * @param roleId 已锁定的角色标识
     * @param dojoId 道馆编号
     * @param day 北京自然日序号
     * @param count 当日已发放次数
     */
    void saveSweepCount(String roleId, int dojoId, long day, int count) {
        jdbc.update("MERGE INTO local_dojo_sweep (role_id,dojo_id,sweep_day,sweep_count) "
                + "KEY(role_id,dojo_id,sweep_day) VALUES (?,?,?,?)", roleId, dojoId, day, count);
    }

    /**
     * 获取角色已存的道馆阵容。
     *
     * @param roleId 角色标识
     * @return 按阵法位置排列的阵容
     */
    List<DojoPlacement> team(String roleId) {
        return jdbc.query("SELECT t.elf_id,e.config_id,t.position,t.battle_id FROM local_dojo_team t "
                        + "JOIN local_elf e ON e.id=t.elf_id AND e.role_id=t.role_id WHERE t.role_id=? ORDER BY t.position",
                (r, n) -> new DojoPlacement(r.getString(1), r.getInt(2), r.getInt(3), r.getInt(4)), roleId);
    }

    /** 核对精灵是否已派往旅行团。
     * @param roleId 角色标识
     * @param elfId 精灵实例
     * @return 是否已经派遣
     */
    boolean traveling(String roleId,String elfId){
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_travel_dispatch WHERE role_id=? AND elf_id=?",
                Integer.class,roleId,elfId)>0;
    }

    /**
     * 保存客户端提交并通过校验的整个新阵容。
     *
     * @param roleId 角色标识
     * @param team 完整的新阵容
     * @return 保存的成员数
     */
    int replaceTeam(String roleId, List<DojoPlacement> team) {
        jdbc.update("DELETE FROM local_dojo_team WHERE role_id=?", roleId);
        int count = 0;
        for (DojoPlacement elf : team) {
            count += jdbc.update("INSERT INTO local_dojo_team (role_id,position,battle_id,elf_id) VALUES (?,?,?,?)",
                    roleId, elf.position, elf.battleId, elf.elfId);
        }
        return count;
    }

    /**
     * 获取角色已存的助阵精灵。
     *
     * @param roleId 角色标识
     * @return 按槽位排列的助阵成员
     */
    List<DojoPlacement> aid(String roleId) {
        return jdbc.query("SELECT a.elf_id,e.config_id,a.slot FROM local_elf_aid a "
                        + "JOIN local_elf e ON e.id=a.elf_id AND e.role_id=a.role_id WHERE a.role_id=? ORDER BY a.slot",
                (r, n) -> new DojoPlacement(r.getString(1), r.getInt(2), r.getInt(3), 0), roleId);
    }

    /**
     * 清除指定助阵位置。
     *
     * @param roleId 角色标识
     * @param slot 助阵槽位
     * @return 删除行数
     */
    int deleteAid(String roleId, int slot) {
        return jdbc.update("DELETE FROM local_elf_aid WHERE role_id=? AND slot=?", roleId, slot);
    }

    /**
     * 保存助阵位置与所属精灵。
     *
     * @param roleId 角色标识
     * @param slot 助阵槽位
     * @param elfId 已验证所属的精灵编号
     * @return 插入行数
     */
    int addAid(String roleId, int slot, String elfId) {
        return jdbc.update("INSERT INTO local_elf_aid (role_id,slot,elf_id) VALUES (?,?,?)", roleId, slot, elfId);
    }

    /**
     * 读取还未结束的道馆战斗。
     *
     * @param roleId 角色标识
     * @return 当前战斗，无记录时为空
     */
    Attempt attempt(String roleId) {
        return jdbc.query("SELECT attempt_id,dojo_id,group_index,seed,practice,defeated_mask,started_at "
                + "FROM local_dojo_attempt WHERE role_id=?", (r, n) -> new Attempt(r.getString(1), r.getInt(2),
                r.getInt(3), r.getInt(4), r.getBoolean(5), r.getInt(6), r.getLong(7)), roleId)
                .stream().findFirst().orElse(null);
    }

    /**
     * 保存成功开启的正式道馆挑战，用于限时任务每日计数。
     *
     * @param roleId 当前角色
     * @param day 服务器时区自然日序
     */
    void recordChallengeDay(String roleId, long day) {
        com.pokemon.offline.activity.PassportProgress.record(jdbc, roleId, 46, 1);
        jdbc.update("INSERT INTO local_dojo_challenge_day (role_id,challenge_day) SELECT ?,? "
                + "WHERE NOT EXISTS (SELECT 1 FROM local_dojo_challenge_day WHERE role_id=? AND challenge_day=?)",
                roleId, day, roleId, day);
    }

    /**
     * 记录当前道馆战斗及已验证击败的目标。
     *
     * @param roleId 角色标识
     * @param attempt 本地战斗进度
     * @return 更新行数
     */
    int saveAttempt(String roleId, Attempt attempt) {
        return jdbc.update("MERGE INTO local_dojo_attempt "
                        + "(role_id,attempt_id,dojo_id,group_index,seed,practice,defeated_mask,started_at) "
                        + "KEY(role_id) VALUES (?,?,?,?,?,?,?,?)", roleId, attempt.id, attempt.dojo, attempt.group,
                attempt.seed, attempt.practice, attempt.defeated, attempt.started);
    }

    /**
     * 移除已完成或离开的道馆战斗。
     *
     * @param roleId 角色标识
     * @return 删除行数
     */
    int deleteAttempt(String roleId) {
        return jdbc.update("DELETE FROM local_dojo_attempt WHERE role_id=?", roleId);
    }

    /**
     * 已开始的道馆尝试。
     *
     * @author tongruiyuan
     * @since 2026-11-18 23:06
     */
    static final class Attempt {
        final String id;
        final int dojo;
        final int group;
        final int seed;
        final boolean practice;
        final int defeated;
        final long started;

        /**
         * 创建可恢复的道馆战斗记录。
         *
         * @param id 本次实例标识
         * @param dojo 道馆编号
         * @param group 怪物组下标
         * @param seed 客户端战斗种子
         * @param practice 是否为热身
         * @param defeated 已击败精灵位掩码
         * @param started 开始时间秒数
         */
        Attempt(String id, int dojo, int group, int seed, boolean practice, int defeated, long started) {
            this.id = id;
            this.dojo = dojo;
            this.group = group;
            this.seed = seed;
            this.practice = practice;
            this.defeated = defeated;
            this.started = started;
        }
    }

    /**
     * 当前道馆和历史通关纪录。
     *
     * @author tongruiyuan
     * @since 2026-11-18 22:54
     */
    static final class Progress {
        final int history;
        final int dojo;
        final int tactics;
        final int defeated;
        final long day;
        final int failures;
        final int practices;

        /**
         * 创建角色道馆进度快照。
         *
         * @param history 历史最高通关道馆
         * @param dojo 当前道馆
         * @param tactics 选用阵法类型
         * @param defeated 已击败怪物位掩码
         * @param day 次数所属日期
         * @param failures 当日失败次数
         * @param practices 当日热身次数
         */
        Progress(int history, int dojo, int tactics, int defeated, long day, int failures, int practices) {
            this.history = history;
            this.dojo = dojo;
            this.tactics = tactics;
            this.defeated = defeated;
            this.day = day;
            this.failures = failures;
            this.practices = practices;
        }
    }
}
