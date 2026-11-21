package com.pokemon.local.role;

import com.pokemon.local.inventory.DiamondSpendMapper;
import com.pokemon.local.role.api.entity.LocalRole;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 本地角色 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:42
 */
@Repository
@DependsOnDatabaseInitialization
public class RoleMapper {

    /** 获取本地同服实际存在的最高等级角色。
     * @param realmId 当前区服
     * @return 最高等级角色
     */
    Optional<LocalRole> highestRole(int realmId) {
        return jdbc.query("SELECT * FROM local_game_role WHERE realm_id=? ORDER BY level DESC,created_at,id LIMIT 1",
                (row, index) -> new LocalRole(row), realmId).stream().findFirst();
    }

    /** 读取本地区服首位角色的建角时刻。
     * @param realmId 区服
     * @return 本地可核验的最早开服时间
     */
    long firstRoleTime(int realmId) {
        return jdbc.queryForObject("SELECT MIN(created_at) FROM local_game_role WHERE realm_id=?", Long.class, realmId);
    }

    /** 读取当日已选择的经验补偿档。
     * @param roleId 当前角色
     * @param day 北京时间日期编号
     * @return 当日选择的档位，未领取时为零
     */
    int recoveryStatus(String roleId, long day) {
        return jdbc.query("SELECT choice FROM local_exp_recovery WHERE role_id=? AND business_day=?",
                (row, i) -> row.getInt(1), roleId, day).stream().findFirst().orElse(0);
    }

    /** 在当前角色事务中登记本次发放。
     * @param roleId 当前角色
     * @param day 北京时间日期编号
     * @param choice 领取档位
     * @param experience 实际发放经验
     * @return 插入记录数
     */
    int saveRecovery(String roleId, long day, int choice, long experience) {
        return jdbc.update("INSERT INTO local_exp_recovery(role_id,business_day,choice,experience) VALUES(?,?,?,?)",
                roleId, day, choice, experience);
    }

    private final JdbcTemplate jdbc;
    private final DiamondSpendMapper diamondSpend;

    /**
     * 初始化本地角色数据访问层
     *
     * @param jdbc JDBC 操作模板
     * @param diamondSpend 钻石消费流水
     */
    public RoleMapper(JdbcTemplate jdbc, DiamondSpendMapper diamondSpend) {
        this.jdbc = jdbc;
        this.diamondSpend = diamondSpend;
    }

    /**
     * 查询账号在指定区服的角色
     *
     * @param accountId 本地账号标识
     * @param realmId 区服编号
     * @return 已创建角色
     */
    public Optional<LocalRole> findRole(String accountId, int realmId) {
        List<LocalRole> roles = jdbc.query("SELECT * FROM local_game_role WHERE account_id = ? AND realm_id = ?",
                (row, index) -> new LocalRole(row), accountId, realmId);
        return roles.stream().findFirst();
    }

    /**
     * 按主键读取角色
     *
     * @param id 角色主键
     * @param lock 是否锁定记录供事务修改
     * @return 角色状态
     */
    public Optional<LocalRole> findById(String id, boolean lock) {
        String sql = "SELECT * FROM local_game_role WHERE id = ?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (row, index) -> new LocalRole(row), id).stream().findFirst();
    }

    /**
     * 在区服名称唯一约束下同时保存名字、改名次数和钻石余额。
     *
     * @param roleId 角色主键
     * @param name 新角色名
     * @param diamonds 本次消耗钻石
     * @return 更新记录数
     */
    public int rename(String roleId, String name, long diamonds) {
        int changed = jdbc.update("UPDATE local_game_role SET name=?,diamonds=diamonds-?,rename_count=rename_count+1 "
                + "WHERE id=? AND diamonds>=?", name, diamonds, roleId, diamonds);
        if (changed == 1) {
            diamondSpend.record(roleId, diamonds);
        }
        return changed;
    }

    /**
     * 查询该角色解锁且存档的头像。
     *
     * @param roleId 角色主键
     * @return 已解锁头像编号
     */
    public List<Integer> unlockedAvatars(String roleId) {
        return jdbc.queryForList("SELECT avatar_id FROM local_role_avatar WHERE role_id=? ORDER BY avatar_id",
                Integer.class, roleId);
    }

    /**
     * 保存新解锁的头像。
     *
     * @param roleId 角色主键
     * @param avatarId 头像编号
     * @return 插入记录数
     */
    public int unlockAvatar(String roleId, int avatarId) {
        return jdbc.update("INSERT INTO local_role_avatar(role_id,avatar_id) VALUES (?,?)", roleId, avatarId);
    }

    /**
     * 保存角色当前佩戴的头像。
     *
     * @param roleId 角色主键
     * @param avatarId 头像编号
     * @return 更新记录数
     */
    public int selectAvatar(String roleId, int avatarId) {
        return jdbc.update("UPDATE local_game_role SET avatar=? WHERE id=?", avatarId, roleId);
    }

    /**
     * 保存场景位置与副本返回点
     *
     * @param roleId 角色主键
     * @param sceneId 当前场景编号
     * @param x 当前横坐标
     * @param y 当前纵坐标
     * @param returnSceneId 返回场景编号
     * @param returnX 返回横坐标
     * @param returnY 返回纵坐标
     * @return 更新记录数
     */
    public int updatePosition(String roleId, int sceneId, int x, int y,
                              Integer returnSceneId, Integer returnX, Integer returnY) {
        return jdbc.update("UPDATE local_game_role SET scene_id=?,x=?,y=?,return_scene_id=?,return_x=?,return_y=? WHERE id=?",
                sceneId, x, y, returnSceneId, returnX, returnY, roleId);
    }

    /**
     * 查询旧版可能积累了未消耗经验的角色
     *
     * @return 存有经验的角色
     */
    public List<LocalRole> findExperiencedRoles() {
        return jdbc.query("SELECT * FROM local_game_role WHERE exp > 0", (row, index) -> new LocalRole(row));
    }

    /**
     * 仅修正角色的级内经验和当前等级，不修改当前位置或资产
     *
     * @param roleId 角色主键
     * @param level 新的等级
     * @param exp 新的级内经验
     * @return 更新记录数
     */
    public int updateProgression(String roleId, int level, long exp) {
        return jdbc.update("UPDATE local_game_role SET level=?,exp=? WHERE id=?", level, exp, roleId);
    }

    /**
     * 累加角色货币并写入验算后的等级与级内经验
     *
     * @param roleId 角色主键
     * @param coins 金币增量
     * @param diamonds 钻石增量
     * @param level 本次结算后的等级
     * @param exp 本次结算后剩余的级内经验
     * @return 更新记录数
     */
    public int addCurrency(String roleId, long coins, long diamonds, int level, long exp) {
        int changed = jdbc.update("UPDATE local_game_role SET coins=coins+?,diamonds=diamonds+?,level=?,exp=? WHERE id=?",
                coins, diamonds, level, exp, roleId);
        if (changed == 1 && coins < 0) com.pokemon.local.activity.PassportProgress.record(jdbc, roleId, 48, -coins);
        return changed;
    }

    /**
     * 写入新角色和初始位置
     *
     * @param id 角色标识
     * @param accountId 本地账号标识
     * @param realmId 区服编号
     * @param name 角色名
     * @param sex 性别
     * @param sceneId 场景编号
     * @param x 初始横坐标
     * @param y 初始纵坐标
     * @param createdAt 建角 UTC 秒数
     * @param adminLevel 本地 admin 的初始等级
     * @param adminStage 本地 admin 的初始晋升等阶
     * @return 插入记录数
     */
    public int insert(String id, String accountId, int realmId, String name, int sex,
                      int sceneId, int x, int y, long createdAt, int adminLevel, int adminStage) {
        return jdbc.update("INSERT INTO local_game_role (id,account_id,realm_id,name,sex,scene_id,x,y,created_at,level,stage) "
                + "SELECT ?,id,?,?,?,?,?,?,?,CASE WHEN username='admin' THEN ? ELSE 1 END,"
                + "CASE WHEN username='admin' THEN ? ELSE 1 END FROM local_account WHERE id=?",
                id, realmId, name, sex, sceneId, x, y, createdAt, adminLevel, adminStage, accountId);
    }
}
