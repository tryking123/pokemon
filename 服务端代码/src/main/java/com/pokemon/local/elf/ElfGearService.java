package com.pokemon.local.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.local.elf.api.entity.ElfGear;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.role.RoleService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地携带品与 P 力石 Service，校验实例归属和装配条件。
 *
 * @author tongruiyuan
 * @since 2026-11-19 00:24
 */
@Service
public class ElfGearService {
    public static final int CARRY = 4;
    public static final int POWERSTONE = 13;
    public static final int BAG_CAPACITY = 100;
    private final RoleService roles;
    private final ElfGearMapper gears;
    private final ElfMapper elves;

    /**
     * 初始化装备服务。
     *
     * @param roles 角色事务锁
     * @param gears 装备存档
     * @param elves 精灵存档
     */
    ElfGearService(RoleService roles, ElfGearMapper gears, ElfMapper elves) {
        this.roles = roles;
        this.gears = gears;
        this.elves = elves;
    }

    /**
     * 获取角色全部装配与背包装备。
     *
     * @param roleId 角色编号
     * @return 装备列表
     */
    public List<ElfGear> snapshot(String roleId) {
        return gears.list(roleId);
    }

    /**
     * 查询角色已结算的力石碎屑余额。
     *
     * @param roleId 角色编号
     * @return 碎屑余额
     */
    public long debris(String roleId) {
        return gears.debris(roleId);
    }

    /**
     * 校验加工请求中的每个独立背包实例，防止跨角色、重复或锁定材料被消耗。
     *
     * @param roleId 已锁定的角色编号
     * @param ids 客户端选择的实例编号
     * @return 有效材料，失败时为空
     */
    List<ElfGear> materials(String roleId, List<?> ids) {
        return materials(roleId, ids, POWERSTONE);
    }

    /**
     * 校验同一角色背包中未穿戴、未锁定、未重复的指定类别装备。
     * @param roleId 角色编号
     * @param ids 提交的实例编号列表
     * @param kind 装备类别
     * @return 可用于消耗的实例，无效时返回空列表
     */
    private List<ElfGear> materials(String roleId, List<?> ids, int kind) {
        if (ids.isEmpty() || ids.size() > BAG_CAPACITY || new java.util.HashSet<>(ids).size() != ids.size()) {
            return java.util.Collections.emptyList();
        }
        java.util.Map<String, ElfGear> available = gears.list(roleId).stream()
                .filter(g -> g.kind == kind && g.elfId == null && !g.locked)
                .collect(Collectors.toMap(g -> g.id, g -> g));
        List<ElfGear> result = new ArrayList<>();
        for (Object id : ids) {
            ElfGear gear = available.get(id);
            if (gear == null) {
                return java.util.Collections.emptyList();
            }
            result.add(gear);
        }
        return result;
    }

    /**
     * 根据原客户端 hecid 将旧版力石转换为加工界面所见的模板编号。
     *
     * @param gear 存档中的力石
     * @return 客户端实际展示的模板编号
     */
    static int visibleStone(ElfGear gear) {
        return ElfTrainingCatalog.row("gem", gear.configId).path("hecid").asInt(gear.configId);
    }

    /**
     * 按同品质普通携带品配方消耗背包实例并生成一件结果。
     * @param roleId 角色编号
     * @param ids 原界面选中的携带品实例
     * @return 新入库的携带品；材料无效时返回空引用
     */
    @Transactional
    public ElfGear fuseCarries(String roleId, List<?> ids) {
        roles.lockRole(roleId);
        if (ids.size() != 2 && ids.size() != 5) {
            return null;
        }
        List<ElfGear> ingredients = materials(roleId, ids, CARRY);
        if (ingredients.isEmpty()) {
            return null;
        }
        int quality = ElfTrainingCatalog.row("portable", ingredients.get(0).configId).path("quality").asInt();
        if (quality < 2 || quality > 5 || ids.size() != (quality == 5 ? 2 : 5)
                || ingredients.stream().anyMatch(item ->
                        ElfTrainingCatalog.row("portable", item.configId).path("quality").asInt() != quality)) {
            return null;
        }
        List<Integer> pool = ElfTrainingCatalog.portablePool(Math.min(5, quality + 1));
        if (pool.isEmpty()) {
            return null;
        }
        for (ElfGear item : ingredients) {
            if (gears.remove(roleId, item.id) != 1) {
                throw new IllegalStateException("携带品合成材料已变化");
            }
        }
        int target = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        return grantGear(roleId, CARRY, target, 1).get(0);
    }

    /**
     * 在加工事务中删除已经校验的材料。
     *
     * @param roleId 角色编号
     * @param materials 待消耗的实例
     */
    void consumeStones(String roleId, List<ElfGear> materials) {
        for (ElfGear material : materials) {
            if (gears.remove(roleId, material.id) != 1) {
                throw new IllegalStateException("力石材料已变化");
            }
        }
    }

    /**
     * 按客户端显示的模板计算分解碎屑，返还事务结束后的总余额。
     *
     * @param roleId 角色编号
     * @param ids 本次选中的力石实例编号
     * @return 已保存的余额，负数表示材料无效
     */
    @Transactional
    public long splitStones(String roleId, List<?> ids) {
        roles.lockRole(roleId);
        List<ElfGear> materials = materials(roleId, ids);
        if (materials.isEmpty()) {
            return -1;
        }
        long balance = debris(roleId);
        for (ElfGear material : materials) {
            long amount = ElfTrainingCatalog.row("gem", visibleStone(material)).path("powder").asLong();
            if (amount <= 0 || balance > Long.MAX_VALUE - amount) {
                return -1;
            }
            balance += amount;
        }
        consumeStones(roleId, materials);
        gears.setDebris(roleId, balance);
        return balance;
    }

    /**
     * 校验装备模板和背包容量。
     *
     * @param roleId 角色编号
     * @param kind 装备类别
     * @param configId 装备模板编号
     * @param quantity 数量
     * @return 背包可接收的有效装备
     */
    public boolean canGrantGear(String roleId, int kind, int configId, long quantity) {
        if (kind != CARRY && kind != POWERSTONE) {
            return false;
        }
        JsonNode template = ElfTrainingCatalog.row(kind == CARRY ? "portable" : "gem", configId);
        return quantity > 0 && quantity <= BAG_CAPACITY && !template.isMissingNode()
                && looseCount(gears.list(roleId), kind) + quantity <= BAG_CAPACITY;
    }

    /**
     * 向角色发放携带品或 P 力石实例。
     *
     * @param roleId 角色编号
     * @param kind 装备类别，4 为携带品，13 为 P 力石
     * @param configId 底表装备编号
     * @param quantity 装备数量
     * @return 新实例清单
     */
    @Transactional
    public List<ElfGear> grantGear(String roleId, int kind, int configId, long quantity) {
        roles.lockRole(roleId);
        if (!canGrantGear(roleId, kind, configId, quantity)) {
            throw new IllegalArgumentException("装备无对应模板或背包空间不足");
        }
        List<ElfGear> result = new ArrayList<>((int) quantity);
        for (int i = 0; i < quantity; i++) {
            ElfGear gear = new ElfGear(UUID.randomUUID().toString().replace("-", ""), kind, configId, null, 0, false, 0);
            if (gears.insert(roleId, gear) != 1) {
                throw new IllegalStateException("装备发放未写入存档");
            }
            result.add(gear);
        }
        return result;
    }

    /**
     * 把背包中的 P 力石移入已解锁栏位，替换时将旧石返还背包。
     *
     * @param roleId 角色编号
     * @param elfId 精灵编号
     * @param position 装配栏位
     * @param stoneId 力石实例编号，空字符串为卸下
     * @return 是否完成装配
     */
    @Transactional
    public boolean equipStone(String roleId, String elfId, int position, String stoneId) {
        roles.lockRole(roleId);
        LocalElf elf = elves.findOwned(roleId, elfId).orElse(null);
        if (elf == null || position < 1 || position > 4) {
            return false;
        }
        JsonNode limit = ElfTrainingCatalog.row("variable", 3620);
        int[] actual = {elf.level, elf.training.path("love").asInt(1), elf.training.path("star").asInt(1),
            elf.training.path("awakenlv").asInt()};
        if (actual[position - 1] < limit.path("data" + position).asInt()) {
            return false;
        }
        boolean remove = stoneId == null || stoneId.isEmpty();
        List<ElfGear> all = gears.list(roleId);
        ElfGear present = all.stream().filter(gear -> gear.kind == POWERSTONE && elfId.equals(gear.elfId)
                && gear.position == position).findFirst().orElse(null);
        ElfGear incoming = remove ? null : gears.find(roleId, stoneId).orElse(null);
        if (remove) {
            if (present == null || looseCount(all, POWERSTONE) >= BAG_CAPACITY) {
                return false;
            }
        } else {
            if (incoming == null || incoming.kind != POWERSTONE || incoming.elfId != null) {
                return false;
            }
            int lv = ElfTrainingCatalog.row("gem", incoming.configId).path("lv").asInt();
            if (elf.level < limit.path("data6").path(Integer.toString(lv)).asInt()) {
                return false;
            }
        }
        if (present != null) {
            gears.equip(roleId, present.id, null, 0);
        }
        if (incoming != null) {
            gears.equip(roleId, incoming.id, elfId, position);
        }
        return true;
    }

    /**
     * 保护或解除指定位置的 P 力石。
     *
     * @param roleId 角色编号
     * @param elfId 精灵编号，空字符串表示力石位于背包
     * @param position 装配栏位
     * @param id 力石实例编号
     * @param locked 是否保护
     * @return 是否更新成功
     */
    @Transactional
    public boolean lockStone(String roleId, String elfId, int position, String id, boolean locked) {
        roles.lockRole(roleId);
        ElfGear stone = gears.find(roleId, id).orElse(null);
        if (stone == null || stone.kind != POWERSTONE || stone.position != position
                || !java.util.Objects.equals(stone.elfId, elfId == null || elfId.isEmpty() ? null : elfId)) {
            return false;
        }
        return gears.lock(roleId, id, locked) == 1;
    }

    /**
     * 将背包中的普通携带品注入精灵。按底表概率开启下一格，否则替换未保护栏位。
     *
     * @param roleId 角色编号
     * @param elfId 精灵编号
     * @param carryId 背包携带品实例
     * @return 是否完成装配
     */
    @Transactional
    public boolean equipCarry(String roleId, String elfId, String carryId) {
        roles.lockRole(roleId);
        ElfGear incoming = gears.find(roleId, carryId).orElse(null);
        if (incoming == null || incoming.kind != CARRY || incoming.elfId != null
                || ElfTrainingCatalog.row("portable", incoming.configId).path("quality").asInt() >= 6
                || !elves.findOwned(roleId, elfId).isPresent()) {
            return false;
        }
        List<ElfGear> equipped = gears.list(roleId).stream().filter(gear -> gear.kind == CARRY
                && !GoldCarryRules.isGold(gear.configId) && elfId.equals(gear.elfId)).collect(Collectors.toList());
        if (equipped.stream().anyMatch(gear -> gear.configId == incoming.configId)) {
            return false;
        }
        int probability = ElfTrainingCatalog.row("variable", 60 + Math.min(equipped.size(), 9)).path("data1").asInt();
        int position = equipped.size() + 1;
        if (ThreadLocalRandom.current().nextInt(10000) >= probability) {
            List<ElfGear> replaceable = equipped.stream().filter(gear -> !gear.locked).collect(Collectors.toList());
            if (replaceable.isEmpty()) {
                return false;
            }
            ElfGear replaced = replaceable.get(ThreadLocalRandom.current().nextInt(replaceable.size()));
            position = replaced.position;
            gears.remove(roleId, replaced.id);
        }
        return gears.equip(roleId, carryId, elfId, position) == 1;
    }

    /**
     * 修改精灵携带品的保护状态。
     *
     * @param roleId 角色编号
     * @param elfId 装配精灵
     * @param carryId 已装配的携带品
     * @param protect 是否保护
     * @return 是否找到可修改的装配
     */
    @Transactional
    public boolean protectCarry(String roleId, String elfId, String carryId, boolean protect) {
        roles.lockRole(roleId);
        ElfGear carry = gears.find(roleId, carryId).orElse(null);
        return carry != null && carry.kind == CARRY && !GoldCarryRules.isGold(carry.configId) && elfId != null && elfId.equals(carry.elfId)
                && gears.lock(roleId, carryId, protect) == 1;
    }

    /**
     * 在精灵被合法消耗时返还其装配的力石，并移除已注入的携带品。
     *
     * @param roleId 角色编号
     * @param elfId 被消耗的精灵编号
     * @return 是否有空间完成返还
     */
    boolean removeElfGear(String roleId, String elfId) {
        List<ElfGear> all = gears.list(roleId);
        long equippedStones = all.stream().filter(gear -> gear.kind == POWERSTONE && elfId.equals(gear.elfId)).count();
        if (looseCount(all, POWERSTONE) + equippedStones > BAG_CAPACITY) {
            return false;
        }
        for (ElfGear gear : all) {
            if (elfId.equals(gear.elfId)) {
                if (gear.kind == POWERSTONE) {
                    gears.equip(roleId, gear.id, null, 0);
                } else {
                    gears.remove(roleId, gear.id);
                }
            }
        }
        return true;
    }

    /**
     * 计算装备在背包中占用的格数。
     *
     * @param list 角色全部装备
     * @param kind 装备类别
     * @return 背包中的装备数
     */
    private static long looseCount(List<ElfGear> list, int kind) {
        return list.stream().filter(gear -> gear.kind == kind && gear.elfId == null).count();
    }
}
