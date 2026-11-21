package com.pokemon.local.battle;

import com.pokemon.local.battle.api.entity.BattleAttempt;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;

/**
 * 本地副本尝试和道具存档 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:39
 */
@Repository
@DependsOn("roleMapper")
public class BattleMapper {

    private final JdbcTemplate jdbc;

    /** @param jdbc JDBC 操作模板 */
    public BattleMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 初始化精灵、副本与库存相关表。 */
    @PostConstruct
    public void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-battle.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 查询主队阵法，旧存档默认使用第一种阵法。
     * @param roleId 本地角色标识
     * @return 阵法类型
     */
    public int formation(String roleId) {
        List<Integer> rows = jdbc.queryForList("SELECT form_type FROM local_party_formation WHERE role_id=?", Integer.class, roleId);
        return rows.isEmpty() ? 1 : rows.get(0);
    }

    /** 读取角色保存的队伍选择，旧存档按第一队或第一种阵法处理。
     * @param roleId 角色编号
     * @param purpose 当前默认队、副本队或指定队阵法
     * @return 已保存的编号
     */
    public int choice(String roleId, String purpose) {
        List<Integer> values = jdbc.queryForList("SELECT team_value FROM local_team_choice WHERE role_id=? AND purpose=?", Integer.class, roleId, purpose);
        return values.isEmpty() ? 1 : values.get(0);
    }

    /** 在角色锁内更新队伍或阵法选项。
     * @param roleId 角色编号
     * @param purpose 选项名称
     * @param value 所选编号
     */
    public void saveChoice(String roleId, String purpose, int value) {
        if (jdbc.update("UPDATE local_team_choice SET team_value=? WHERE role_id=? AND purpose=?", value,roleId,purpose)==0) {
            jdbc.update("INSERT INTO local_team_choice(role_id,purpose,team_value) VALUES (?,?,?)",roleId,purpose,value);
        }
    }

    /** 读取普通编队各自选择的阵法。
     * @param roleId 角色编号
     * @param team 普通队号
     * @return 阵法类型
     */
    public int teamFormation(String roleId, int team) {
        return team==1 ? formation(roleId) : choice(roleId,"formation"+team);
    }

    /** 在角色锁内登记首副本实际战斗生命，精灵养成后保留已经损失的生命。
     * @param roleId 角色编号
     * @param elfId 精灵实例编号
     * @param max 当前真实战斗生命上限
     * @return 当前生命值
     */
    public int dungeonLife(String roleId,String elfId,int max) {
        List<int[]> stored=jdbc.query("SELECT current_hp,max_hp FROM local_dungeon_fighter_life WHERE role_id=? AND encounter_group=? AND elf_id=?",
                (rs,i)->new int[]{rs.getInt(1),rs.getInt(2)},roleId,FirstBattleData.GROUP,elfId);
        if(stored.isEmpty()) {
            jdbc.update("INSERT INTO local_dungeon_fighter_life(role_id,encounter_group,elf_id,current_hp,max_hp) VALUES(?,?,?,?,?)",roleId,FirstBattleData.GROUP,elfId,max,max);
            return max;
        }
        int[] previous=stored.get(0);
        if(max==previous[1])return previous[0];
        int current=previous[0]==0 ? 0 : Math.min(max,Math.max(0,max-(previous[1]-previous[0])));
        jdbc.update("UPDATE local_dungeon_fighter_life SET current_hp=?,max_hp=? WHERE role_id=? AND encounter_group=? AND elf_id=?",current,max,roleId,FirstBattleData.GROUP,elfId);
        return current;
    }

    /**
     * 读取战斗协议需要的完整阵法配置编号。
     * @param roleId 本地角色标识
     * @return 类型乘一百再加当前等级
     */
    public int formationId(String roleId) {
        return formationId(roleId, teamFormation(roleId, choice(roleId,"default")));
    }

    /**
     * 按玩法选用的阵法类型读取该角色的培养等级。
     * @param roleId 本地角色标识
     * @param type 玩法队伍独立选择的阵法类型
     * @return 战斗用完整阵法编号
     */
    public int formationId(String roleId, int type) {
        return type * 100 + progress(roleId, type)[0];
    }

    /**
     * 读取单类阵法的等级和经验。
     * @param roleId 本地角色标识
     * @param type 阵法类型
     * @return 等级和经验
     */
    int[] progress(String roleId, int type) {
        List<int[]> rows = jdbc.query("SELECT form_level,form_exp FROM local_formation_growth WHERE role_id=? AND form_type=?",
                (row, i) -> new int[]{row.getInt(1), row.getInt(2)}, roleId, type);
        return rows.isEmpty() ? new int[]{1, 0} : rows.get(0);
    }

    /**
     * 查询同一请求已结算的等级及经验。
     * @param roleId 本地角色标识
     * @param key 请求标识
     * @return 历史结果，首次请求为空
     */
    int[] upgradeReceipt(String roleId, String key) {
        List<int[]> rows = jdbc.query("SELECT form_level,form_exp FROM local_formation_receipt WHERE role_id=? AND request_id=?",
                (row, i) -> new int[]{row.getInt(1), row.getInt(2)}, roleId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 保存升级状态及该次消耗的回执。
     * @param roleId 本地角色标识
     * @param type 阵法类型
     * @param level 升级后的等级
     * @param exp 剩余经验
     * @param key 请求标识
     */
    void saveUpgrade(String roleId, int type, int level, int exp, String key) {
        jdbc.update("MERGE INTO local_formation_growth(role_id,form_type,form_level,form_exp) KEY(role_id,form_type) VALUES (?,?,?,?)",
                roleId, type, level, exp);
        jdbc.update("INSERT INTO local_formation_receipt(role_id,request_id,form_level,form_exp) VALUES (?,?,?,?)",
                roleId, key, level, exp);
    }

    /**
     * 保存主队阵法，调用方已锁定角色。
     * @param roleId 本地角色标识
     * @param type 阵法类型
     */
    public void saveFormation(String roleId, int type) {
        if (jdbc.update("UPDATE local_party_formation SET form_type=? WHERE role_id=?", type, roleId) == 0) {
            jdbc.update("INSERT INTO local_party_formation(role_id,form_type) VALUES (?,?)", roleId, type);
        }
    }
    /**
     * 读取已完成的遭遇。
     *
     * @param roleId 本地角色标识
     * @return 已结算的遭遇编号
     */
    public Set<Integer> cleared(String roleId) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT encounter FROM local_battle_clear WHERE role_id=? ORDER BY encounter", Integer.class, roleId));
    }

    /**
     * 统计自然日内已结算的普通副本扫荡和首次通关次数。
     * @param roleId 角色标识
     * @param start 北京时间当日零点的 Unix 秒
     * @return 当日消耗的普通副本机会
     */
    int dungeonUses(String roleId, long start) {
        long end = start + 86400;
        return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM local_dungeon_sweep WHERE role_id=? "
                + "AND settled_at>=? AND settled_at<?) + (SELECT COUNT(*) FROM local_battle_clear WHERE role_id=? "
                + "AND encounter=? AND cleared_at>=? AND cleared_at<?)", Integer.class,
                roleId, start, end, roleId, FirstBattleData.BOSS, start, end);
    }

    /**
     * 统计当日普通副本购买笔数。
     * @param roleId 角色标识
     * @param dayStart 北京时间当日零点秒数
     * @return 已购买次数
     */
    int dungeonPurchases(String roleId, long dayStart) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_dungeon_purchase WHERE role_id=? AND day_start=?",
                Integer.class, roleId, dayStart);
    }

    /**
     * 查询由额外体力兑换的当日普通副本机会。
     * @param roleId 角色标识
     * @param dayStart 北京时间当日零点
     * @return 当日兑换机会数
     */
    int staminaRuns(String roleId, long dayStart) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_stamina_ledger WHERE role_id=? AND day_start=? AND delta=-100",
                Integer.class, roleId, dayStart);
    }

    /**
     * 检查该购买请求是否已经成功。
     * @param roleId 角色标识
     * @param key 连接和请求号
     * @return 已成功时为真
     */
    boolean boughtDungeonRun(String roleId, String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_dungeon_purchase WHERE role_id=? AND request_key=?",
                Integer.class, roleId, key) > 0;
    }

    /**
     * 登记一次已付款的普通副本机会。
     * @param roleId 角色标识
     * @param dayStart 北京时间当日零点秒数
     * @param key 连接和请求号
     * @param price 实扣钻石
     */
    void recordDungeonPurchase(String roleId, long dayStart, String key, int price) {
        jdbc.update("INSERT INTO local_dungeon_purchase(role_id,request_key,day_start,price) VALUES(?,?,?,?)",
                roleId, key, dayStart, price);
    }

    /**
     * 检查同一连接的扫荡请求是否已结算。
     * @param roleId 角色标识
     * @param key 连接和请求号
     * @return 是否已有结算记录
     */
    boolean swept(String roleId, String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_dungeon_sweep WHERE role_id=? AND request_key=?",
                Integer.class, roleId, key) != 0;
    }

    /**
     * 保存当次扫荡和抽取结果，参与角色发奖事务。
     * @param roleId 角色标识
     * @param key 连接和请求号
     * @param now 结算 Unix 秒
     * @param item 实际道具
     */
    void recordSweep(String roleId, String key, long now, int item) {
        jdbc.update("INSERT INTO local_dungeon_sweep(role_id,request_key,dungeon_id,settled_at,item_id) VALUES(?,?,?,?,?)",
                roleId, key, FirstBattleData.GROUP, now, item);
    }

    /**
     * 登记遭遇首次通关。
     *
     * @param roleId 本地角色标识
     * @param encounter 遭遇编号
     * @param now 当前时间戳
     * @return 新增记录数
     */
    public int addClear(String roleId, int encounter, long now) {
        return jdbc.update("INSERT INTO local_battle_clear VALUES (?,?,?)", roleId, encounter, now);
    }

    /**
     * 查询角色最近的副本战斗。
     *
     * @param roleId 本地角色标识
     * @return 当前尝试，首次未挑战时为空
     */
    public BattleAttempt findAttempt(String roleId) {
        List<BattleAttempt> attempts = jdbc.query("SELECT * FROM local_battle_attempt WHERE role_id=?",
                (row, index) -> new BattleAttempt(row.getString("attempt_id"), row.getInt("encounter"),
                        row.getInt("seed"), row.getInt("defeated_mask"), row.getLong("started_at"),
                        row.getString("status")), roleId);
        return attempts.isEmpty() ? null : attempts.get(0);
    }

    /**
     * 保存一条新尝试并替代上一次尝试。
     *
     * @param roleId 本地角色标识
     * @param attempt 新尝试
     * @return 写入记录数
     */
    public int start(String roleId, BattleAttempt attempt) {
        return jdbc.update("MERGE INTO local_battle_attempt KEY(role_id) VALUES (?,?,?,?,?,?,?)",
                roleId, attempt.id, attempt.encounter, attempt.seed, attempt.defeatedMask, attempt.startedAt, attempt.status);
    }

    /**
     * 更新当前尝试的指令执行结果。
     *
     * @param roleId 本地角色标识
     * @param attempt 已执行指令的尝试
     * @return 更新记录数
     */
    public int update(String roleId, BattleAttempt attempt) {
        return jdbc.update("UPDATE local_battle_attempt SET defeated_mask=?,status=? WHERE role_id=? AND attempt_id=?",
                attempt.defeatedMask, attempt.status, roleId, attempt.id);
    }
}
