package com.pokemon.offline.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 精灵种族与觉醒技能升级，按原材料进度满额结算并记录可重生返还的消耗。
 *
 * @author tongruiyuan
 * @since 2026-11-20 15:39
 */
@Service
public class ElfSkillUpgradeService {
    private final RoleService roles;
    private final ElfMapper elves;
    private final ElfReleaseService release;
    private final InventoryService inventory;

    /** 绑定角色事务、精灵持有与材料服务。
     * @param roles 角色事务锁
     * @param elves 精灵存档
     * @param release 材料占用与消耗
     * @param inventory 道具背包
     */
    public ElfSkillUpgradeService(RoleService roles, ElfMapper elves, ElfReleaseService release, InventoryService inventory) {
        this.roles = roles;
        this.elves = elves;
        this.release = release;
        this.inventory = inventory;
    }

    /** 将一项已解锁技能提升一级，同一材料实例仅可被消耗一次。
     * @param role 角色编号
     * @param uid 接受培养的精灵
     * @param skill 原技能基础编号
     * @param selected 玩家选定的材料精灵编号列表
     * @param receipt 当前连接的请求回执
     * @return 原协议错误码、技能编号与累计消耗
     */
    @Transactional
    public Map<String, Object> upgrade(String role, String uid, long skill, Object selected, String receipt) {
        Map<String, Object> invalid = Collections.singletonMap("errno", 100021);
        if (roles.lockRole(role).level < 50 || skill <= 0 || skill > Integer.MAX_VALUE || !(selected instanceof List)
                || receipt == null || elves.hasIndividualReceipt(role, receipt)) return invalid;
        LocalElf target = elves.findOwned(role, uid).orElse(null);
        if (target == null) return invalid;
        int kind = ElfSkillBar.upgradeKind(target, (int)skill);
        String key = Long.toString(skill);
        int level = target.training.path("skill_lv").path(key).asInt(1);
        int assess = ElfTrainingCatalog.row("species", target.configId).path("assess").asInt();
        JsonNode entry = ElfSkillBar.UPGRADE.path("types").path(key);
        JsonNode demand = ElfSkillBar.UPGRADE.path("levels").path(Integer.toString(level));
        if (kind == 0 || level >= ElfSkillBar.UPGRADE.path("limits").path(assess - 1).asInt()
                || level >= entry.path(1).asInt() || demand.isMissingNode()) return invalid;
        List<?> ids = (List<?>) selected;
        if (ids.isEmpty() || ids.size() > 2) return invalid;
        int element = entry.path(0).asInt();
        int progress = 0;
        List<LocalElf> materials = new ArrayList<>();
        for (Object id : ids) {
            if (!(id instanceof String) || uid.equals(id)) return invalid;
            LocalElf material = elves.findOwned(role, (String)id).orElse(null);
            if (material == null || materials.stream().anyMatch(previous -> previous.id.equals(id))
                    || release.detachedCandidates(role, material.configId, false).stream().noneMatch(candidate -> candidate.id.equals(id))) return invalid;
            JsonNode species = ElfTrainingCatalog.row("species", material.configId);
            if (species.path("assess").asInt() < demand.path("assess").asInt()
                    || species.path("quality").asInt() < demand.path("quality").asInt()) return invalid;
            progress += species.path("attribute1").asInt() == element || species.path("attribute2").asInt() == element ? 2 : 1;
            materials.add(material);
        }
        int item = kind == 1 ? 20003 + element : 21063;
        int amount = demand.path(kind == 1 ? "attributeStone" : "awakeningStone").asInt();
        if (progress != 2 || !inventory.spendMaterials(role, item, amount, 0, 0)) return invalid;
        release.consumeExchangeCandidates(role, materials);
        ArrayNode paid = target.training.withArray("skill_cost");
        recordCost(paid, 1, item, amount);
        for (LocalElf material : materials) recordCost(paid, 2, material.configId, 1);
        target.training.with("skill_lv").put(key, level + 1);
        if (elves.saveTraining(role, uid, target.configId, target.training) != 1) throw new IllegalStateException("未能保存技能等级");
        elves.recordIndividualReceipt(role, receipt);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errno", 0);
        result.put("uid", uid);
        result.put("skill", skill);
        result.put("skill_cost", costs(target));
        return result;
    }

    /** 合并同模板的历次实际投入。
     * @param paid 已保存的消耗
     * @param type 资产类型，一为物品、二为精灵
     * @param id 资产模板
     * @param amount 本次消耗数量
     */
    private static void recordCost(ArrayNode paid, int type, int id, int amount) {
        for (JsonNode row : paid) {
            if (row.path("itype").asInt() == type && row.path("id").asInt() == id) {
                ((ObjectNode)row).put("num", Math.addExact(row.path("num").asInt(), amount));
                return;
            }
        }
        paid.addObject().put("itype", type).put("id", id).put("num", amount);
    }

    /** 输出原精灵重生预览与训练回包共用的累计材料。
     * @param elf 已保存的精灵
     * @return 原协议通用资产列表
     */
    public static List<Map<String,Integer>> costs(LocalElf elf) {
        List<Map<String,Integer>> result = new ArrayList<>();
        for (JsonNode row : elf.training.path("skill_cost")) {
            Map<String,Integer> entry = new LinkedHashMap<>();
            for (String key : new String[]{"itype","id","num"}) entry.put(key,row.path(key).asInt());
            result.add(entry);
        }
        return result;
    }

    /** 用稳定的基础技能编号作为原技能等级表的索引，换装或进化后保留对应关系。
     * @param elf 精灵存档
     * @return 原 S1001 等级列表
     */
    public static List<Map<String,Integer>> levels(LocalElf elf) {
        List<Map<String,Integer>> result = new ArrayList<>();
        elf.training.path("skill_lv").fields().forEachRemaining(entry -> {
            Map<String,Integer> row = new LinkedHashMap<>();
            row.put("pos", Integer.parseInt(entry.getKey()));
            row.put("level", entry.getValue().asInt());
            result.add(row);
        });
        return result;
    }
}
