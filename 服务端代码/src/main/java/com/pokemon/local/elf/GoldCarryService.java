package com.pokemon.local.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.local.elf.api.entity.ElfGear;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 黄金携带品的装卸、替换与升星。
 * @author tongruiyuan
 * @since 2026-11-20 14:01
 */
@Service
public class GoldCarryService {
    private final RoleService roles;
    private final ElfMapper elves;
    private final ElfGearMapper gear;
    private final InventoryService inventory;
    private final JdbcTemplate jdbc;

    /** 创建黄金携带品操作服务。
     * @param roles 角色锁与钱包
     * @param elves 归属精灵
     * @param gear 携带品实例
     * @param inventory 道具和金币支付
     * @param jdbc 请求回执及星级存储
     */
    public GoldCarryService(RoleService roles, ElfMapper elves, ElfGearMapper gear, InventoryService inventory, JdbcTemplate jdbc) {
        this.roles = roles;
        this.elves = elves;
        this.gear = gear;
        this.inventory = inventory;
        this.jdbc = jdbc;
    }

    /** 装上、卸下或替换对应的黄金栏位。
     * @param role 角色
     * @param elf 已打开的精灵
     * @param id 选中的携带品
     * @param opt 1 装、2 卸、3 换
     * @param pos 目标栏位
     * @param use 界面显示的被替换实例
     * @param receipt 原协议连接及请求号
     * @return 操作是否成功
     */
    @Transactional
    public boolean equip(String role, String elf, String id, int opt, int pos, String use, String receipt) {
        LocalRole owner = roles.lockRole(role);
        if (owner.level < 40 || pos < 1 || pos > 3 || opt < 1 || opt > 3 || id == null || elf == null) return false;
        String signature = "equip|" + elf + "|" + id + "|" + opt + "|" + pos + "|" + use;
        Integer previous = previous(role, receipt, signature);
        if (previous != null) return previous >= 0;
        if (!elves.findOwned(role, elf).isPresent()) return false;
        List<ElfGear> all = gear.list(role);
        ElfGear selected = null, current = null;
        int ordinary = 0, inBag = 0;
        for (ElfGear g : all) {
            if (g.kind != ElfGearService.CARRY) continue;
            if (id.equals(g.id)) selected = g;
            if (g.elfId == null) inBag++;
            if (!elf.equals(g.elfId)) continue;
            if (!GoldCarryRules.isGold(g.configId)) ordinary++;
            else if (pos == g.position) current = g;
        }
        if (selected == null || !GoldCarryRules.isGold(selected.configId)) return false;
        if (opt == 2) {
            if (current == null || !id.equals(current.id) || use != null && !use.isEmpty()
                    || inBag >= ElfGearService.BAG_CAPACITY) return false;
        } else {
            if (selected.elfId != null || ordinary < (pos == 1 ? 0 : pos == 2 ? 6 : 8)) return false;
            if (opt == 1 && (current != null || use != null && !use.isEmpty())) return false;
            if (opt == 3 && (current == null || !current.id.equals(use))) return false;
            for (ElfGear g : all) {
                if (g.kind == ElfGearService.CARRY && elf.equals(g.elfId) && g.configId == selected.configId) return false;
            }
        }
        if (opt != 1 && !inventory.spendMaterials(role, 21075, 1, 0, 0)) return false;
        if (current != null && gear.equip(role, current.id, null, 0) != 1) throw new IllegalStateException("待卸下的黄金携带品已变更");
        if (opt != 2 && gear.equip(role, id, elf, pos) != 1) throw new IllegalStateException("目标黄金携带品已变更");
        record(role, receipt, signature, 0);
        return true;
    }

    /** 以指定同名和品质材料将选中携带品升一星。
     * @param role 角色
     * @param elf 实际装配者，背包中为空
     * @param id 目标携带品
     * @param same 同名材料实例
     * @param others 指定品质材料实例
     * @param receipt 原协议连接及请求号
     * @return 成功的星级，失败为负一
     */
    @Transactional
    public int star(String role, String elf, String id, List<?> same, List<?> others, String receipt) {
        LocalRole owner = roles.lockRole(role);
        if (owner.level < 40 || id == null || same == null || others == null || same.size() > 5 || others.size() > 14) return -1;
        List<String> self = new ArrayList<>(), other = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        unique.add(id);
        if (!materials(same, self, unique) || !materials(others, other, unique)) return -1;
        String signature = "star|" + elf + "|" + id + "|" + self + "|" + other;
        Integer replay = previous(role, receipt, signature);
        if (replay != null) return replay;
        Map<String,ElfGear> owned = new HashMap<>();
        for (ElfGear g : gear.list(role)) if (g.kind == ElfGearService.CARRY) owned.put(g.id, g);
        ElfGear target = owned.get(id);
        if (target == null || !GoldCarryRules.isGold(target.configId)
                || !java.util.Objects.equals(target.elfId, elf == null || elf.isEmpty() ? null : elf)) return -1;
        int next = target.star + 1;
        JsonNode row = GoldCarryRules.DATA.path("gear").path(Integer.toString(target.configId)).path(next);
        if (!row.has("self") || self.size() != row.path("self").asInt()) return -1;
        for (String uid : self) {
            ElfGear g = owned.get(uid);
            if (g == null || g.elfId != null || g.locked || g.configId != target.configId) return -1;
        }
        Map<Integer,Integer> expected = new HashMap<>(), selected = new HashMap<>();
        JsonNode requirements = row.path("other");
        for (int i = 0; i + 1 < requirements.size(); i += 2) expected.put(requirements.get(i).asInt(),requirements.get(i+1).asInt());
        for (String uid : other) {
            ElfGear g = owned.get(uid);
            if (g == null || g.elfId != null || g.locked) return -1;
            selected.merge(ElfTrainingCatalog.row("portable",g.configId).path("quality").asInt(), 1, Integer::sum);
        }
        if (!expected.equals(selected) || !inventory.spendMaterials(role, Collections.emptyMap(), row.path("gold").get(2).asLong(), 0)) return -1;
        unique.remove(id);
        for (String uid : unique) if (gear.remove(role,uid) != 1) throw new IllegalStateException("升星材料已变化");
        if (jdbc.update("UPDATE local_elf_gear SET star=? WHERE role_id=? AND id=? AND star=?", next,role,id,target.star) != 1)
            throw new IllegalStateException("黄金携带品星级已变化");
        record(role, receipt, signature, next);
        return next;
    }

    /** 核验两类素材之间、与目标实例之间互不重复。
     * @param raw 原请求输入
     * @param output 按编号排序的素材
     * @param seen 当前操作所有实例
     * @return 通过为真
     */
    private boolean materials(List<?> raw, List<String> output, Set<String> seen) {
        for (Object value : raw) {
            if (!(value instanceof String) || !seen.add((String)value)) return false;
            output.add((String)value);
        }
        Collections.sort(output);
        return true;
    }

    /** 读取已提交请求的真实结果。
     * @param role 角色
     * @param receipt 连接及请求号
     * @param signature 操作参数
     * @return 已完成时返回结果，首次为空
     */
    private Integer previous(String role, String receipt, String signature) {
        List<Integer> saved = jdbc.query("SELECT argument,result_value FROM local_gold_carry_receipt WHERE role_id=? AND receipt=?",
                (r, ix) -> signature.equals(r.getString(1)) ? r.getInt(2) : -1, role, receipt);
        return saved.isEmpty() ? null : saved.get(0);
    }

    /** 与扣费和实例变更一起记录结果。
     * @param role 角色
     * @param receipt 连接及请求号
     * @param signature 操作参数
     * @param value 已提交结果
     */
    private void record(String role, String receipt, String signature, int value) {
        jdbc.update("INSERT INTO local_gold_carry_receipt(role_id,receipt,argument,result_value) VALUES(?,?,?,?)", role,receipt,signature,value);
    }
}
