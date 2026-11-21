package com.pokemon.offline.role;

import com.pokemon.offline.activitylevel.AdventureProgressService;
import com.pokemon.offline.activitylevel.TrainerEquipmentService;
import com.pokemon.offline.dojo.DojoService;
import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.elf.ElfTrainingCatalog;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.util.LinkedHashMap;
import java.util.Map;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 训练师升阶 Service，按原 newlevel 表核验通关、装备、图鉴和材料。
 *
 * @author tongruiyuan
 * @since 2026-11-20 09:32
 */
@Service
public class TrainerPromotionService {
    private final RoleService roles;
    private final AdventureProgressService adventure;
    private final TrainerEquipmentService equipment;
    private final DojoService dojo;
    private final ElfService elves;
    private final InventoryService inventory;
    private final JdbcTemplate jdbc;

    /**
     * 连接升阶所需的角色进度与资产。
     *
     * @param roles 角色与钱包
     * @param adventure 冒险进度
     * @param equipment 训练师装备
     * @param dojo 道馆通关历史
     * @param elves 图鉴历史
     * @param inventory 升阶材料
     * @param jdbc 本地角色存档
     */
    public TrainerPromotionService(RoleService roles, AdventureProgressService adventure, TrainerEquipmentService equipment,
            DojoService dojo, ElfService elves, InventoryService inventory, JdbcTemplate jdbc) {
        this.roles = roles;
        this.adventure = adventure;
        this.equipment = equipment;
        this.dojo = dojo;
        this.elves = elves;
        this.inventory = inventory;
        this.jdbc = jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_promotion_receipt(role_id VARCHAR(100) NOT NULL,"
                + " receipt VARCHAR(100) NOT NULL, PRIMARY KEY(role_id,receipt))");
    }

    /**
     * 校验原训练师面板的全部条件，实扣金币、材料并提升一个阶段。
     *
     * @param id 角色编号
     * @param receipt 原连接和请求序号
     * @return 提升后的角色；资格不足时为空
     */
    @Transactional
    public LocalRole promote(String id, String receipt) {
        LocalRole current = roles.lockRole(id);
        if (!jdbc.queryForList("SELECT receipt FROM local_promotion_receipt WHERE role_id=? AND receipt=?",
                String.class, id, receipt).isEmpty()) {
            return null;
        }
        if (current.stage < 0 || current.stage >= 2) {
            return null;
        }
        boolean first = current.stage == 0;
        int requiredLevel = first ? 50 : 80;
        if (current.level < requiredLevel || adventure.nextMission(id) <= (first ? 10800 : 13819)
                || dojo.snapshot(id).history < (first ? 1008 : 1012)
                || equipment.qualifyingEquipment(id, 5, requiredLevel) < 1) {
            return null;
        }
        long discovered = elves.handbook(id).keySet().stream()
                .filter(species -> ElfTrainingCatalog.row("species", species).path("quality").asInt() >= 5).count();
        Map<Integer, Long> materials = new LinkedHashMap<>();
        materials.put(20001, first ? 100L : 300L);
        materials.put(20002, first ? 300L : 500L);
        if (discovered < (first ? 3 : 10)
                || !inventory.spendMaterials(id, materials, first ? 3000000L : 10000000L, 0)) {
            return null;
        }
        jdbc.update("UPDATE local_game_role SET stage=? WHERE id=?", current.stage + 1, id);
        jdbc.update("INSERT INTO local_promotion_receipt(role_id,receipt) VALUES(?,?)", id, receipt);
        return roles.lockRole(id);
    }
}
