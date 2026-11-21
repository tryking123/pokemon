package com.pokemon.offline.elf;

import com.pokemon.offline.elf.api.entity.ElfGear;
import java.util.List;
import java.util.Optional;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.core.RowMapper;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 携带品与 P 力石实例 Mapper。
 *
 * @author tongruiyuan
 * @since 2026-11-19 00:24
 */
@Repository
public class ElfGearMapper {
    private static final RowMapper<ElfGear> GEAR = (row, index) -> new ElfGear(row.getString("id"),
            row.getInt("kind"), row.getInt("config_id"), row.getString("elf_id"), row.getInt("position"),
            row.getInt("protected") == 1, row.getInt("star"), row.getString("details"));

    private final JdbcTemplate jdbc;

    /** @param jdbc 本地数据库访问模板 */
    ElfGearMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询角色持有的装备实例。
     *
     * @param roleId 角色编号
     * @return 装备实例，已按栏位排序
     */
    List<ElfGear> list(String roleId) {
        return jdbc.query("SELECT * FROM local_elf_gear WHERE role_id=? ORDER BY position,id", GEAR, roleId);
    }

    /**
     * 查询角色当前可用于兑换的 P 力石碎屑。
     *
     * @param roleId 角色编号
     * @return 已存入的碎屑余额
     */
    long debris(String roleId) {
        return jdbc.query("SELECT debris FROM local_pstone_wallet WHERE role_id=?",
                (row, index) -> row.getLong(1), roleId).stream().findFirst().orElse(0L);
    }

    /**
     * 在已持有角色事务锁时保存碎屑余额。
     *
     * @param roleId 角色编号
     * @param balance 结算后的余额
     */
    void setDebris(String roleId, long balance) {
        if (jdbc.update("UPDATE local_pstone_wallet SET debris=? WHERE role_id=?", balance, roleId) == 0) {
            jdbc.update("INSERT INTO local_pstone_wallet(role_id,debris) VALUES (?,?)", roleId, balance);
        }
    }

    /**
     * 查询角色拥有的指定装备。
     *
     * @param roleId 角色编号
     * @param id 装备编号
     * @return 装备实例
     */
    Optional<ElfGear> find(String roleId, String id) {
        return jdbc.query("SELECT * FROM local_elf_gear WHERE role_id=? AND id=?", GEAR, roleId, id)
                .stream().findFirst();
    }

    /**
     * 向装备背包中写入一个实例。
     *
     * @param roleId 角色编号
     * @param gear 装备实例
     * @return 写入条数
     */
    int insert(String roleId, ElfGear gear) {
        return jdbc.update("INSERT INTO local_elf_gear (role_id,id,kind,config_id) VALUES (?,?,?,?)",
                roleId, gear.id, gear.kind, gear.configId);
    }

    /**
     * 修改装备的装配精灵和栏位。
     *
     * @param roleId 角色编号
     * @param id 装备实例编号
     * @param elfId 精灵编号，为空时返还背包
     * @param position 装配栏位
     * @return 更新条数
     */
    int equip(String roleId, String id, String elfId, int position) {
        jdbc.update("DELETE FROM local_pstone_refine_pending WHERE role_id=? AND gear_id=?", roleId, id);
        return jdbc.update("UPDATE local_elf_gear SET elf_id=?,position=? WHERE role_id=? AND id=?",
                elfId, position, roleId, id);
    }

    /**
     * 查询待确认的力石及其候选属性。
     * @param roleId 角色编号
     * @return 实例编号和候选属性，尚无预览时为空
     */
    String[] pendingStone(String roleId) {
        return jdbc.query("SELECT gear_id,attributes FROM local_pstone_refine_pending WHERE role_id=?",
                (row,index) -> new String[]{row.getString(1),row.getString(2)}, roleId).stream().findFirst().orElse(null);
    }

    /**
     * 检查洗炼或保存回执是否已经存在。
     * @param roleId 角色编号
     * @param receipt 本次请求编号
     * @return 已处理时为真
     */
    boolean hasStoneReceipt(String roleId, String receipt) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_pstone_refine_receipt WHERE role_id=? AND receipt=?",
                Integer.class, roleId, receipt) > 0;
    }

    /**
     * 写入本次成功的请求编号。
     * @param roleId 角色编号
     * @param receipt 请求编号
     */
    void recordStoneReceipt(String roleId, String receipt) {
        jdbc.update("INSERT INTO local_pstone_refine_receipt(role_id,receipt) VALUES (?,?)", roleId, receipt);
    }

    /**
     * 保存角色最新的待确认力石候选。
     * @param roleId 角色编号
     * @param gear 目标实例
     * @param attributes 候选词条
     */
    void previewStone(String roleId, String gear, String attributes) {
        jdbc.update("MERGE INTO local_pstone_refine_pending(role_id,gear_id,attributes) KEY(role_id) VALUES (?,?,?)",
                roleId, gear, attributes);
    }

    /**
     * 将候选词条写回原力石实例。
     * @param roleId 角色编号
     * @param gear 力石编号
     * @param details 合并后的实例存档
     */
    void saveStone(String roleId, String gear, String details) {
        if (jdbc.update("UPDATE local_elf_gear SET details=? WHERE role_id=? AND id=? AND kind=13", details, roleId, gear) != 1) {
            throw new IllegalStateException("洗炼力石已变化");
        }
        jdbc.update("DELETE FROM local_pstone_refine_pending WHERE role_id=?", roleId);
    }

    /**
     * 修改指定装备的保护状态。
     *
     * @param roleId 角色编号
     * @param id 装备实例编号
     * @param locked 是否锁定
     * @return 更新条数
     */
    int lock(String roleId, String id, boolean locked) {
        return jdbc.update("UPDATE local_elf_gear SET protected=? WHERE role_id=? AND id=?",
                locked ? 1 : 0, roleId, id);
    }

    /**
     * 移除参与合成、分解或被替换的装备。
     *
     * @param roleId 角色编号
     * @param id 装备实例编号
     * @return 删除条数
     */
    int remove(String roleId, String id) {
        return jdbc.update("DELETE FROM local_elf_gear WHERE role_id=? AND id=?", roleId, id);
    }
}
