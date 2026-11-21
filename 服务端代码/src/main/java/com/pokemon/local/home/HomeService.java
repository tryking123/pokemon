package com.pokemon.local.home;

import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.util.List;
import javax.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 家园初次进入及可用于橘子群岛的心情余额。
 * @author tongruiyuan
 * @since 2026-11-19 19:51
 */
@Service
public class HomeService {
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final RoleService roles;

    /** 绑定角色与家园资产。
     * @param jdbc 本地数据库
     * @param inventory 真实道具库存
     * @param roles 角色钱包锁
     */
    public HomeService(JdbcTemplate jdbc, InventoryService inventory, RoleService roles) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.roles = roles;
    }

    /** 创建家园与幂等操作存档。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("home/schema.sql")).execute(jdbc.getDataSource());
    }

    /** 查询心情余额。
     * @param id 角色编号
     * @return 可用心情
     */
    public long mood(String id) {
        List<Long> rows = jdbc.queryForList("SELECT mood FROM local_home_state WHERE role_id=?", Long.class, id);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /** 查询真实饲育屋等级。
     * @param id 角色编号
     * @return 已解锁等级
     */
    public int level(String id) {
        List<Integer> rows = jdbc.queryForList("SELECT level FROM local_home_state WHERE role_id=?", Integer.class, id);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /** 初次从原地图入口进入后建立一级饲育屋。
     * @param id 角色编号
     */
    @Transactional
    public void enter(String id) {
        roles.lockRole(id);
        if (level(id) == 0)
            jdbc.update("MERGE INTO local_home_state(role_id,level,mood) KEY(role_id) VALUES(?,?,?)", id, 1, mood(id));
    }

    /** 序列化业务的成功回执。
     * @param id 角色编号
     * @param receipt 操作唯一键
     * @param result 已提交的原回包
     */
    public void record(String id, String receipt, String result) {
        jdbc.update("INSERT INTO local_home_operation(role_id,receipt,reply) VALUES(?,?,?)", id, receipt, result);
    }

    /** 读取先前的成功回执。
     * @param id 角色编号
     * @param receipt 操作唯一键
     * @return 协议应答或空值
     */
    public String reply(String id, String receipt) {
        List<String> r = jdbc.queryForList("SELECT reply FROM local_home_operation WHERE role_id=? AND receipt=?",
                String.class, id, receipt);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 原子扣除岛屿的心情消耗。
     * @param id 角色编号
     * @param cost 正数费用
     * @return 余额足够且已扣除
     */
    @Transactional
    public boolean spendMood(String id, int cost) {
        return cost > 0 && jdbc.update("UPDATE local_home_state SET mood=mood-? WHERE role_id=? AND mood>=?", cost, id,
                cost) == 1;
    }

    /** 按原物品说明将持有的星星糖饰换成心情。
     * @param id 角色编号
     * @param quantity 使用数量
     * @param receipt 本次操作回执
     * @return 零为已发放，负一为成功重放，其余为原错误码
     */
    @Transactional
    public int useCandy(String id, long quantity, String receipt) {
        roles.lockRole(id);
        if (!jdbc.queryForList("SELECT receipt FROM local_home_receipt WHERE role_id=? AND receipt=?", String.class, id,
                receipt).isEmpty())
            return -1;
        if (quantity <= 0 || quantity > Integer.MAX_VALUE)
            return 100021;
        if (!inventory.spendMaterials(id, 23046, quantity, 0, 0))
            return 100051;
        jdbc.update("MERGE INTO local_home_state(role_id,level,mood) KEY(role_id) VALUES(?,?,?)", id, level(id),
                Math.addExact(mood(id), quantity));
        jdbc.update("INSERT INTO local_home_receipt(role_id,receipt) VALUES(?,?)", id, receipt);
        return 0;
    }
}
