package com.pokemon.offline.elf;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.api.entity.LocalElf;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import com.pokemon.offline.platform.context.annotation.DependsOn;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.core.RowMapper;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 初始精灵与队伍 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:42
 */
@Repository
@DependsOnDatabaseInitialization
@DependsOn("battleMapper")
public class ElfMapper {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WITH_STONE = "SELECT e.*,s.sid AS stone_sid,s.template_id AS stone_id,"
            + "s.stars AS stone_stars,s.locked AS stone_lock,s.lucky AS stone_lucky,s.attrs AS stone_attrs "
            + "FROM local_elf e LEFT JOIN local_secret_stone s ON s.elf_id=e.id AND s.role_id=e.role_id ";
    private static final RowMapper<LocalElf> ELF = (row, index) -> {
        LocalElf elf = new LocalElf(row.getString("id"), row.getInt("config_id"), row.getInt("level"),
                row.getInt("team"), row.getInt("slot"), readTraining(row.getString("training_data")));
        if (row.getString("stone_sid") != null) {
            elf.stone.put("sid", row.getString("stone_sid")).put("id", row.getInt("stone_id"))
                    .put("n", 1).put("level", 0).put("elfequ_star", row.getInt("stone_stars"))
                    .put("elfequ_starexp", 0).put("lucky", row.getInt("stone_lucky"))
                    .put("lock", row.getInt("stone_lock") != 0);
            try {
                String saved = row.getString("stone_attrs");
                elf.stone.set("elfequ_addattr", saved == null ? JSON.createArrayNode() : JSON.readTree(saved));
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("已穿戴的奥义石附加属性损坏", ex);
            }
        }
        return elf;
    };

    /** 读取已提交的 PCL 原请求及其应答。
     * @param roleId 当前角色
     * @param receipt 原连接回执
     * @return 保存结果，未处理时为空
     */
    Optional<ObjectNode> pclReceipt(String roleId, String receipt) {
        return jdbc.query("SELECT payload FROM local_elf_pcl_receipt WHERE role_id=? AND receipt=?",
                (row,i) -> readTraining(row.getString(1)), roleId,receipt).stream().findFirst();
    }

    /** 记录已提交的 PCL 进度变动。
     * @param roleId 当前角色
     * @param receipt 连接回执
     * @param payload 原请求与原应答
     */
    void recordPcl(String roleId, String receipt, ObjectNode payload) {
        jdbc.update("INSERT INTO local_elf_pcl_receipt(role_id,receipt,payload) VALUES(?,?,?)",
                roleId,receipt,payload.toString());
    }

    private final JdbcTemplate jdbc;
    private final com.pokemon.offline.home.PetCombatService pets;

    /**
     * 查询同一精灵训练请求是否已扣费并保存个体值。
     * @param roleId 角色编号
     * @param receipt 洗炼请求回执
     * @return 已结算时为真
     */
    boolean hasIndividualReceipt(String roleId, String receipt) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_elf_individual_receipt WHERE role_id=? AND receipt=?",
                Integer.class, roleId, receipt) > 0;
    }

    /**
     * 在洗炼、扣费及七日计数所在事务中保存回执。
     * @param roleId 角色编号
     * @param receipt 洗炼请求回执
     */
    void recordIndividualReceipt(String roleId, String receipt) {
        jdbc.update("INSERT INTO local_elf_individual_receipt(role_id,receipt) VALUES(?,?)", roleId, receipt);
    }

    /**
     * 绑定精灵及普通队伍宠物快照。
     * @param jdbc 私有数据库操作模板
     * @param pets 普通队伍宠物战斗快照
     */
    public ElfMapper(JdbcTemplate jdbc,com.pokemon.offline.home.PetCombatService pets) {
        this.jdbc = jdbc;
        this.pets = pets;
    }

    /**
     * 将旧版本按角色唯一的主键迁移到按精灵唯一，保留已有初始精灵
     */
    @PostConstruct
    public void migrateStarterTable() {
        if (constraints("PRIMARY KEY", "ID").isEmpty()) {
            // 旧库的 role_id 外键与主键共用索引，先移除外键才能释放旧索引。
            constraints("FOREIGN KEY", "ROLE_ID").forEach(this::dropConstraint);
            constraints("PRIMARY KEY", "ROLE_ID").forEach(this::dropConstraint);
            jdbc.execute("ALTER TABLE local_elf ADD PRIMARY KEY (id)");
        }
        if (constraints("FOREIGN KEY", "ROLE_ID").isEmpty()) {
            jdbc.execute("ALTER TABLE local_elf ADD CONSTRAINT local_elf_role_fk "
                    + "FOREIGN KEY (role_id) REFERENCES local_game_role(id)");
        }
    }

    /**
     * 读取指定字段上的旧库约束名称
     *
     * @param type 约束类型
     * @param column 字段名称
     * @return 数据库记录的约束标识符
     */
    private List<String> constraints(String type, String column) {
        return jdbc.queryForList("SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                        + "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE ku "
                        + "ON ku.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND ku.CONSTRAINT_NAME=tc.CONSTRAINT_NAME "
                        + "WHERE tc.TABLE_SCHEMA=SCHEMA() AND tc.TABLE_NAME='LOCAL_ELF' "
                        + "AND tc.CONSTRAINT_TYPE=? AND ku.COLUMN_NAME=?", String.class, type, column);
    }

    /**
     * 按数据库提供的名称移除一个需要重建的约束
     *
     * @param name 从结构表读取的约束名称
     */
    private void dropConstraint(String name) {
        jdbc.execute("ALTER TABLE local_elf DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
    }

    /**
     * 查询拥有的初始精灵。
     *
     * @param roleId 角色标识
     * @return 已有精灵
     */
    public Optional<LocalElf> find(String roleId) {
        List<LocalElf> elfs = jdbc.query(WITH_STONE + "WHERE e.role_id=? AND e.starter=TRUE", ELF, roleId);
        return attachGold(roleId, elfs).stream().findFirst();
    }

    /**
     * 查询全部精灵，先返回初始精灵和当前槽位
     *
     * @param roleId 角色标识
     * @return 角色精灵列表
     */
    public List<LocalElf> list(String roleId) {
        return attachGold(roleId, jdbc.query(WITH_STONE + "WHERE e.role_id=? ORDER BY e.starter DESC,e.slot,e.id", ELF, roleId));
    }

    /**
     * 按角色和实例编号读取精灵。
     *
     * @param roleId 角色编号
     * @param elfId 精灵实例编号
     * @return 属于角色的精灵
     */
    Optional<LocalElf> findOwned(String roleId, String elfId) {
        return attachGold(roleId, jdbc.query(WITH_STONE + "WHERE e.role_id=? AND e.id=?", ELF, roleId, elfId)).stream().findFirst();
    }

    /** 将装备表中的黄金携带星级加入本次读取的精灵快照。
     * @param roleId 所属角色
     * @param owned 已读取的精灵实例
     * @return 含实时装备的精灵实例
     */
    private List<LocalElf> attachGold(String roleId, List<LocalElf> owned) {
        if (owned.isEmpty()) return owned;
        Map<String,LocalElf> byId = new java.util.HashMap<>();
        for (LocalElf elf : owned) byId.put(elf.id, elf);
        jdbc.query("SELECT elf_id,config_id,star FROM local_elf_gear WHERE role_id=? AND kind=4 "
                + "AND config_id BETWEEN 50001 AND 50005 AND elf_id IS NOT NULL", (row, index) -> {
            LocalElf elf = byId.get(row.getString(1));
            if (elf != null) elf.goldCarry.put(String.valueOf(row.getInt(2)), row.getInt(3));
            return index;
        }, roleId);
        pets.attach(roleId, owned);
        return owned;
    }

    /**
     * 写入进化后的模板和培养状态。
     *
     * @param roleId 角色编号
     * @param elfId 精灵实例编号
     * @param configId 当前模板编号
     * @param training 更新后的培养状态
     * @return 更新条数
     */
    int saveTraining(String roleId, String elfId, int configId, ObjectNode training) {
        return jdbc.update("UPDATE local_elf SET config_id=?,training_data=? WHERE role_id=? AND id=?",
                configId, training.toString(), roleId, elfId);
    }

    /**
     * 扣除非初始且未上阵或跟随的材料精灵。
     *
     * @param roleId 角色编号
     * @param elfId 精灵实例编号
     * @return 删除条数
     */
    int consumeElf(String roleId, String elfId) {
        return jdbc.update("DELETE FROM local_elf WHERE role_id=? AND id=? AND slot=0 AND starter=FALSE"
                + " AND NOT EXISTS (SELECT 1 FROM local_elf_gear g WHERE g.role_id=local_elf.role_id AND g.elf_id=local_elf.id AND g.kind=4 AND g.config_id BETWEEN 50001 AND 50005)"
                + " AND NOT EXISTS (SELECT 1 FROM local_secret_stone s WHERE s.elf_id=local_elf.id)"
                + " AND NOT EXISTS (SELECT 1 FROM local_elf_follow f WHERE f.elf_id=local_elf.id)"
                + " AND NOT EXISTS (SELECT 1 FROM local_travel_dispatch t WHERE t.elf_id=local_elf.id)",
                roleId, elfId);
    }

    /**
     * 判断精灵是否允许作为材料，并排除正在跟随的个体。
     *
     * @param roleId 角色编号
     * @param elfId 精灵实例编号
     * @return 是否可作为培养材料
     */
    boolean isConsumable(String roleId, String elfId) {
        return !jdbc.query("SELECT id FROM local_elf WHERE role_id=? AND id=? AND slot=0 AND starter=FALSE"
                + " AND NOT EXISTS (SELECT 1 FROM local_elf_gear g WHERE g.role_id=local_elf.role_id AND g.elf_id=local_elf.id AND g.kind=4 AND g.config_id BETWEEN 50001 AND 50005)"
                + " AND NOT EXISTS (SELECT 1 FROM local_secret_stone s WHERE s.elf_id=local_elf.id)"
                + " AND NOT EXISTS (SELECT 1 FROM local_elf_follow f WHERE f.elf_id=local_elf.id)"
                + " AND NOT EXISTS (SELECT 1 FROM local_travel_dispatch t WHERE t.elf_id=local_elf.id)",
                (row, index) -> row.getString("id"), roleId, elfId).isEmpty();
    }

    /**
     * 解码已持久化的培养状态。
     *
     * @param value JSON 字段内容
     * @return 培养状态对象
     */
    private static ObjectNode readTraining(String value) {
        try {
            return (ObjectNode) JSON.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("精灵培养存档损坏", e);
        }
    }

    /**
     * 保存已选或通过奖励取得的精灵
     *
     * @param roleId 角色标识
     * @param elf 精灵与初始队伍存档
     * @param starter 是否为三选一的初始精灵
     * @return 写入条数
     */
    public int insert(String roleId, LocalElf elf, boolean starter) {
        int inserted = jdbc.update("INSERT INTO local_elf (role_id,id,config_id,level,team,slot,starter) VALUES (?,?,?,?,?,?,?)",
                roleId, elf.id, elf.configId, elf.level, elf.team, elf.slot, starter);
        if (inserted == 1) {
            discover(roleId, elf.configId);
            com.pokemon.offline.battle.FirstBattleData.Starter template = com.pokemon.offline.battle.FirstBattleData.starter(elf.configId);
            if (template != null && (template.quality == 5 || template.quality == 6)) {
                com.pokemon.offline.activity.PassportProgress.record(jdbc, roleId, template.quality == 5 ? 44 : 45, 1);
            }
        }
        return inserted;
    }

    /**
     * 在角色事务锁内登记首次获得的图鉴条目，保留既有已读状态。
     *
     * @param roleId 当前角色编号
     * @param configId 已取得或已进化的精灵模板编号
     */
    void discover(String roleId, int configId) {
        jdbc.update("INSERT INTO local_elf_handbook(role_id,config_id,unread) SELECT ?,?,1 "
                        + "WHERE NOT EXISTS (SELECT 1 FROM local_elf_handbook WHERE role_id=? AND config_id=?)",
                roleId, configId, roleId, configId);
    }

    /**
     * 批量补录旧存档仍持有的模板，重复读取时保留已读设置。
     *
     * @param roleId 当前角色编号
     */
    void backfillHandbook(String roleId) {
        jdbc.update("INSERT INTO local_elf_handbook(role_id,config_id,unread) "
                        + "SELECT DISTINCT elf.role_id,elf.config_id,1 FROM local_elf elf WHERE elf.role_id=? "
                        + "AND NOT EXISTS (SELECT 1 FROM local_elf_handbook entry "
                        + "WHERE entry.role_id=elf.role_id AND entry.config_id=elf.config_id)", roleId);
    }

    /**
     * 按模板读取图鉴的持久解锁及已读状态。
     *
     * @param roleId 当前角色编号
     * @return 图鉴模板及未读标记
     */
    Map<Integer, Integer> handbook(String roleId) {
        Map<Integer, Integer> entries = new LinkedHashMap<>();
        jdbc.query("SELECT config_id,unread FROM local_elf_handbook WHERE role_id=? ORDER BY config_id",
                row -> {
                    entries.put(row.getInt("config_id"), row.getInt("unread"));
                }, roleId);
        return entries;
    }

    /**
     * 将已登记的模板标记为已读。
     *
     * @param roleId 当前角色编号
     * @param configId 原图鉴点击的模板编号
     * @return 是否有可更新的合法条目
     */
    boolean readHandbook(String roleId, int configId) {
        return jdbc.update("UPDATE local_elf_handbook SET unread=0 WHERE role_id=? AND config_id=?",
                roleId, configId) == 1;
    }

    /**
     * 更新该角色拥有的精灵队伍位置。
     *
     * @param roleId 角色标识
     * @param elfId 精灵实例编号
     * @param team 所属普通编队
     * @param slot 队伍位置
     * @return 更新条数
     */
    public int setSlot(String roleId, String elfId, int team, int slot) {
        return jdbc.update("UPDATE local_elf SET team=?,slot=? WHERE role_id=? AND id=?", team, slot, roleId, elfId);
    }

    /**
     * 更新该角色拥有的精灵等级。
     *
     * @param roleId 角色标识
     * @param elfId 精灵实例编号
     * @param level 升级后的等级
     * @return 更新条数
     */
    int setLevel(String roleId, String elfId, int level) {
        return jdbc.update("UPDATE local_elf SET level=? WHERE role_id=? AND id=?", level, roleId, elfId);
    }

    /**
     * 读取相同进阶或进化请求的已提交结果，签名变化时拒绝重放。
     * @param roleId 角色编号
     * @param receipt 请求回执
     * @param signature 操作与参数签名
     * @return 未结算时为空，否则为已保存结果
     */
    Map<String,Object> growthReceipt(String roleId,String receipt,String signature) {
        List<Map<String,Object>> found=jdbc.query("SELECT signature,result_json FROM local_elf_growth_receipt WHERE role_id=? AND receipt=?",
                (row,index)->{
                    if(!signature.equals(row.getString(1))) return java.util.Collections.<String,Object>singletonMap("errno",100021);
                    try {return JSON.readValue(row.getString(2),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
                    catch(java.io.IOException failure){throw new IllegalStateException("精灵进化回执损坏",failure);}
                },roleId,receipt);
        return found.isEmpty()?null:found.get(0);
    }

    /**
     * 在成功扣费及培养事务内记录可重放结果。
     * @param roleId 角色编号
     * @param receipt 请求回执
     * @param signature 操作与参数签名
     * @param result 原协议结果
     */
    void saveGrowthReceipt(String roleId,String receipt,String signature,Map<String,Object> result) {
        jdbc.update("INSERT INTO local_elf_growth_receipt(role_id,receipt,signature,result_json) VALUES(?,?,?,?)",
                roleId,receipt,signature,JSON.valueToTree(result).toString());
    }
}
