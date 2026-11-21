package com.pokemon.offline.activitylevel;

import com.pokemon.offline.activitylevel.api.entity.AdventureEquipment;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.context.annotation.DependsOn;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 冒险进度与闯关礼包领取 Mapper。
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:30
 */
@Repository
@DependsOn("roleMapper")
public class LevelGiftMapper {
    private final JdbcTemplate jdbc;

    /**
     * 初始化数据访问模板。
     *
     * @param jdbc JDBC 操作模板
     */
    LevelGiftMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 初始化冒险进度和礼包记录表。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-activity-level.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 查询角色尚未通关的首个冒险关卡。
     *
     * @param roleId 角色编号
     * @return 已保存的关卡号，尚无存档时为空
     */
    Integer nextMission(String roleId) {
        List<Integer> rows = jdbc.queryForList("SELECT next_mission FROM local_adventure_progress WHERE role_id=?",
                Integer.class, roleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 保存服务器确认的下一关。
     *
     * @param roleId 角色编号
     * @param mission 下一关编号
     */
    void saveNextMission(String roleId, int mission) {
        jdbc.update("MERGE INTO local_adventure_progress (role_id,next_mission) KEY(role_id) VALUES (?,?)",
                roleId, mission);
    }

    /**
     * 添加本次冒险通关取得的装备。
     *
     * @param roleId 角色编号
     * @param equipment 已绑定模板的独立装备
     */
    void insertEquipment(String roleId, AdventureEquipment equipment) {
        jdbc.update("INSERT INTO local_adventure_equipment_instance (id,role_id,config_id,slot) VALUES (?,?,?,?)",
                equipment.id, roleId, equipment.configId, equipment.slot);
    }

    /**
     * 移除属于角色且未佩戴的熔炼材料，并清理旧版迁移来源。
     * @param roleId 角色编号
     * @param id 装备实例编号
     * @return 是否移除了一件背包装备
     */
    boolean removeLooseEquipment(String roleId, String id) {
        int removed = jdbc.update("DELETE FROM local_adventure_equipment_instance WHERE role_id=? AND id=? "
                + "AND NOT EXISTS (SELECT 1 FROM local_trainer_equipped WHERE equipment_id=?)", roleId, id, id);
        if (removed == 1) {
            jdbc.update("DELETE FROM local_adventure_equipment WHERE role_id=? AND id=?", roleId, id);
        }
        return removed == 1;
    }

    /**
     * 读取角色的熔炼点数。
     * @param roleId 角色编号
     * @return 已累计的点数
     */
    int meltPoints(String roleId) {
        return jdbc.query("SELECT points FROM local_trainer_melt WHERE role_id=?",
                (row, index) -> row.getInt(1), roleId).stream().findFirst().orElse(0);
    }

    /**
     * 保存本批装备熔炼后的点数。
     * @param roleId 角色编号
     * @param points 熔炼点数
     */
    void saveMeltPoints(String roleId, int points) {
        jdbc.update("MERGE INTO local_trainer_melt (role_id,points) KEY(role_id) VALUES (?,?)", roleId, points);
    }

    /**
     * 查询已保存的装备附属性。
     * @param gear 装备实例编号
     * @return 已保存的属性序列，未洗炼时为空
     */
    String attributes(String gear) {
        return jdbc.query("SELECT attributes FROM local_trainer_attributes WHERE equipment_id=?",
                (row, index) -> row.getString(1), gear).stream().findFirst().orElse(null);
    }

    /**
     * 保存装备实例的原始洗炼词条。库内只存未加进阶倍率、未加强化等级的原值，
     * 进阶倍率与强化加成在参战/展示读取时各自单独结算，避免重复计数。
     * 仅对已进入实例表的真实装备落库；锻造台候选等临时实例不在实例表内，
     * 直接跳过且不抛异常，避免外键约束打断整包同步。
     * @param gear 装备实例编号
     * @param attributes 原始词条 JSON 序列
     */
    void saveAttributes(String gear, String attributes) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_adventure_equipment_instance WHERE id=?",
                Integer.class, gear);
        if (exists == null || exists == 0) {
            return;
        }
        jdbc.update("MERGE INTO local_trainer_attributes(equipment_id,attributes) KEY(equipment_id) VALUES (?,?)",
                gear, attributes);
    }

    /**
     * 记录角色的洗炼预览，替换前一份未保存的候选。
     * @param roleId 角色编号
     * @param gear 装备实例编号
     * @param receipt 已计费的请求编号
     * @param attributes 预览属性
     */
    void preview(String roleId, String gear, String receipt, String attributes) {
        jdbc.update("INSERT INTO local_trainer_refine_receipt(role_id,request_key) VALUES (?,?)", roleId, receipt);
        jdbc.update("MERGE INTO local_trainer_refine_pending(role_id,equipment_id,preview_key,attributes) "
                + "KEY(role_id) VALUES (?,?,?,?)", roleId, gear, receipt, attributes);
    }

    /**
     * 判断已受理的洗炼请求是否重放。
     * @param roleId 角色编号
     * @param receipt 请求编号
     * @return 是否存在回执
     */
    boolean refined(String roleId, String receipt) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_trainer_refine_receipt WHERE role_id=? AND request_key=?",
                Integer.class, roleId, receipt) > 0;
    }

    /**
     * 提交仍在原角色身上穿戴的装备预览。
     * @param roleId 角色编号
     * @param receipt 保存请求回执，用于区分重复提交
     * @return 是否保存了有效预览
     */
    boolean commitPreview(String roleId, String receipt) {
        List<String[]> pending = jdbc.query("SELECT p.equipment_id,p.attributes FROM local_trainer_refine_pending p "
                        + "JOIN local_trainer_equipped e ON e.role_id=p.role_id AND e.equipment_id=p.equipment_id WHERE p.role_id=?",
                (row,index) -> new String[]{row.getString(1),row.getString(2)}, roleId);
        if (pending.isEmpty() || refined(roleId, receipt)) {
            return false;
        }
        jdbc.update("MERGE INTO local_trainer_attributes(equipment_id,attributes) KEY(equipment_id) VALUES (?,?)",
                pending.get(0)[0], pending.get(0)[1]);
        jdbc.update("INSERT INTO local_trainer_refine_receipt(role_id,request_key) VALUES (?,?)", roleId, receipt);
        clearPreview(roleId);
        return true;
    }

    /**
     * 取消角色尚未保存的候选。
     * @param roleId 角色编号
     */
    void clearPreview(String roleId) {
        jdbc.update("DELETE FROM local_trainer_refine_pending WHERE role_id=?", roleId);
    }

    /**
     * 读取打造台的待领取装备及刷新次数。
     * @param roleId 角色编号
     * @return 候选实例、模板、计数日与次数，未开放时为空
     */
    String[] forge(String roleId) {
        return jdbc.query("SELECT offer_id,config_id,day_number,refreshes FROM local_trainer_forge WHERE role_id=?",
                (row,index) -> new String[]{row.getString(1),row.getString(2),row.getString(3),row.getString(4)},
                roleId).stream().findFirst().orElse(null);
    }

    /**
     * 保存打造台的待领取装备和刷新次数。
     * @param roleId 角色编号
     * @param offer 候选装备
     * @param day 业务日
     * @param count 当日已主动刷新次数
     */
    void saveForge(String roleId, AdventureEquipment offer, long day, int count) {
        jdbc.update("MERGE INTO local_trainer_forge(role_id,offer_id,config_id,day_number,refreshes) KEY(role_id) "
                + "VALUES (?,?,?,?,?)", roleId,offer.id,offer.configId,day,count);
    }

    /**
     * 检查本次打造或刷新请求是否已处理。
     * @param roleId 角色编号
     * @param key 请求编号
     * @return 已结算时为真
     */
    boolean hasForgeReceipt(String roleId, String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_trainer_forge_receipt WHERE role_id=? AND request_key=?",
                Integer.class, roleId, key) > 0;
    }

    /**
     * 保存本次成功操作的回执。
     * @param roleId 角色编号
     * @param key 请求编号
     */
    void addForgeReceipt(String roleId, String key) {
        jdbc.update("INSERT INTO local_trainer_forge_receipt(role_id,request_key) VALUES (?,?)", roleId,key);
    }

    /**
     * 读取冒险取得的全部训练家装备。
     *
     * @param roleId 角色编号
     * @return 按配置编号排序的装备实例列表
     */
    List<AdventureEquipment> equipment(String roleId) {
        return jdbc.query("SELECT id,config_id,slot,advanced FROM local_adventure_equipment_instance i "
                        + "WHERE role_id=? AND NOT EXISTS (SELECT 1 FROM local_trainer_equipped e "
                        + "WHERE e.equipment_id=i.id) ORDER BY config_id,id",
                (row, index) -> new AdventureEquipment(row.getString(1), row.getInt(2), row.getInt(3),0,row.getInt(4)!=0), roleId);
    }

    /**
     * 按当前角色查询独立装备，包括已经穿戴的装备。
     *
     * @param roleId 角色编号
     * @param id 装备实例编号
     * @return 角色拥有的装备，找不到时为空
     */
    AdventureEquipment ownedEquipment(String roleId, String id) {
        return jdbc.query("SELECT id,config_id,slot,advanced FROM local_adventure_equipment_instance WHERE role_id=? AND id=?",
                (row, index) -> new AdventureEquipment(row.getString(1), row.getInt(2), row.getInt(3),0,row.getInt(4)!=0),
                roleId, id).stream().findFirst().orElse(null);
    }

    /**
     * 读取当前角色已穿戴的装备和各部位强化等级。
     *
     * @param roleId 角色编号
     * @return 按部位排序的装备列表
     */
    List<AdventureEquipment> equipped(String roleId) {
        return jdbc.query("SELECT i.id,i.config_id,e.slot,e.enhance_level,i.advanced FROM local_trainer_equipped e "
                        + "JOIN local_adventure_equipment_instance i ON i.id=e.equipment_id "
                        + "WHERE e.role_id=? ORDER BY e.slot",
                (row, index) -> new AdventureEquipment(row.getString(1), row.getInt(2), row.getInt(3), row.getInt(4),row.getInt(5)!=0),
                roleId);
    }

    /** 标记该装备一次性进阶成功并保存回执。
     * @param roleId 角色编号
     * @param gear 角色持有的装备实例
     * @param receipt 请求回执
     */
    void advanceGear(String roleId,String gear,String receipt) {
        if (jdbc.update("UPDATE local_adventure_equipment_instance SET advanced=1 WHERE role_id=? AND id=? AND advanced=0",
                roleId,gear)!=1) throw new IllegalStateException("装备进阶状态已变化");
        jdbc.update("INSERT INTO local_trainer_refine_receipt(role_id,request_key) VALUES (?,?)",roleId,receipt);
    }

    /**
     * 按部位保存当前装备及强化等级。
     *
     * @param roleId 已锁定的角色
     * @param gear 装备及其部位的强化等级
     */
    void saveEquipped(String roleId, AdventureEquipment gear) {
        jdbc.update("MERGE INTO local_trainer_equipped (role_id,slot,equipment_id,enhance_level) "
                        + "KEY(role_id,slot) VALUES (?,?,?,?)",
                roleId, gear.slot, gear.id, gear.level);
    }

    /**
     * 查询一个周期内已领取的档位。
     *
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @return 已领取的礼包序号
     */
    Set<Integer> claimed(String roleId, long cycle) {
        return new HashSet<>(jdbc.queryForList("SELECT tier_index FROM local_level_gift_claim "
                + "WHERE role_id=? AND cycle_start=?", Integer.class, roleId, cycle));
    }

    /**
     * 原子记录当前档位已经发放。
     *
     * @param roleId 角色编号
     * @param cycle 周期起点
     * @param index 底包礼包序号
     */
    void insertClaim(String roleId, long cycle, int index) {
        jdbc.update("INSERT INTO local_level_gift_claim (role_id,cycle_start,tier_index) VALUES (?,?,?)",
                roleId, cycle, index);
    }
}
