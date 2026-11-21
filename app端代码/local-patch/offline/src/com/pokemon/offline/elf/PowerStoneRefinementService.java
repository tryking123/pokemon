package com.pokemon.offline.elf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.api.entity.ElfGear;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * P 力石实例词条、洗炼预览与保存服务。
 * @author tongruiyuan
 * @since 2026-11-19 19:24
 */
@Service
public class PowerStoneRefinementService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode RULES = ElfTrainingCatalog.load("elf/pstone-refine.json");
    private static final TypeReference<List<Map<String,Integer>>> PROPS = new TypeReference<List<Map<String,Integer>>>() {};
    private final RoleService roles;
    private final InventoryService inventory;
    private final ElfGearMapper gears;

    /**
     * 创建力石洗炼服务。
     * @param roles 角色锁与资产
     * @param inventory 钻石及材料扣费
     * @param gears 力石实例及预览存档
     */
    PowerStoneRefinementService(RoleService roles, InventoryService inventory, ElfGearMapper gears) {
        this.roles = roles;
        this.inventory = inventory;
        this.gears = gears;
    }

    /**
     * 读取已保存的词条，旧实例按自身编号稳定生成原始词条。
     * @param stone 力石实例
     * @return 原协议词条
     */
    public static List<Map<String,Integer>> properties(ElfGear stone) {
        return JSON.convertValue(current(stone), PROPS);
    }

    /**
     * 读取原背包或穿戴位置的力石并扣费生成候选。
     * @param roleId 当前角色
     * @param elfId 穿戴精灵编号，背包时为空
     * @param pos 穿戴位置
     * @param id 背包力石编号，穿戴时为空
     * @param mode 零表示钻石，一表示道具
     * @param locks 要保留的词条下标，从一开始
     * @param receipt 连接请求回执
     * @return 候选词条，无效操作返回空引用
     */
    @Transactional
    public List<Map<String,Integer>> preview(String roleId, String elfId, int pos, String id, int mode,
                                              List<?> locks, String receipt) {
        roles.lockRole(roleId);
        ElfGear stone;
        if (elfId == null || elfId.isEmpty()) {
            stone = id == null || pos != 0 ? null : gears.find(roleId, id).filter(g -> g.elfId == null).orElse(null);
        } else {
            stone = id != null && !id.isEmpty() || pos < 1 || pos > 4 ? null : gears.list(roleId).stream()
                    .filter(g -> g.kind == ElfGearService.POWERSTONE && pos == g.position && elfId.equals(g.elfId))
                    .findFirst().orElse(null);
        }
        if (stone == null || stone.kind != ElfGearService.POWERSTONE || mode < 0 || mode > 1
                || gears.hasStoneReceipt(roleId, receipt)) {
            return null;
        }
        ArrayNode original = current(stone);
        if (locks.size() >= original.size()) {
            return null;
        }
        Set<Integer> locked = new HashSet<>();
        for (Object index : locks) {
            if (!(index instanceof Number)) {
                return null;
            }
            long n = ((Number)index).longValue();
            if (n < 1 || n > original.size() || !locked.add((int)n - 1)) {
                return null;
            }
        }
        JsonNode config = ElfTrainingCatalog.row("gem", ElfGearService.visibleStone(stone));
        JsonNode cost = config.path(mode == 0 ? "cost2" : "cost3");
        int base = 3 * locked.size();
        long amount = cost.path(Integer.toString(base + 3)).asLong();
        int material = cost.path(Integer.toString(base + 2)).asInt();
        if (amount <= 0 || !inventory.spendMaterials(roleId, material, mode == 1 ? amount : 0, 0, mode == 0 ? amount : 0)) {
            return null;
        }
        ArrayNode next = roll(stone, original, locked, ThreadLocalRandom.current());
        gears.previewStone(roleId, stone.id, next.toString());
        gears.recordStoneReceipt(roleId, receipt);
        return JSON.convertValue(next, PROPS);
    }

    /**
     * 保存最新候选，保留实例中的其他存档字段。
     * @param roleId 当前角色编号
     * @param receipt 本次请求回执
     * @return 成功写回原实例时为真
     */
    @Transactional
    public boolean save(String roleId, String receipt) {
        roles.lockRole(roleId);
        String[] pending = gears.pendingStone(roleId);
        if (pending == null || gears.hasStoneReceipt(roleId, receipt)) {
            return false;
        }
        ElfGear stone = gears.find(roleId, pending[0]).orElse(null);
        if (stone == null || stone.kind != ElfGearService.POWERSTONE) {
            return false;
        }
        ObjectNode details = (ObjectNode) read(stone.details);
        details.set("extra", read(pending[1]));
        gears.saveStone(roleId, stone.id, details.toString());
        gears.recordStoneReceipt(roleId, receipt);
        return true;
    }

    /**
     * 解析力石附加属性存档。
     * @param value 存档文本
     * @return 属性结构
     */
    private static JsonNode read(String value) {
        try {
            return JSON.readTree(value);
        } catch (IOException e) {
            throw new IllegalStateException("力石词条存档无法读取", e);
        }
    }

    /**
     * 读取原模板的各槽词条。
     * @param stone 力石实例
     * @return 当前实际词条
     */
    private static ArrayNode current(ElfGear stone) {
        JsonNode saved = read(stone.details).path("extra");
        return saved.isArray() ? (ArrayNode)saved : roll(stone, JSON.createArrayNode(), Collections.emptySet(),
                new Random(stone.id.hashCode()));
    }

    /**
     * 按每槽原词条池独立取样。
     * @param stone 目标力石
     * @param original 原词条
     * @param locks 要保留的下标
     * @param random 生成随机源
     * @return 新词条
     */
    private static ArrayNode roll(ElfGear stone, ArrayNode original, Set<Integer> locks, Random random) {
        JsonNode pools = RULES.path("pools").path(Integer.toString(ElfGearService.visibleStone(stone)));
        ArrayNode result = JSON.createArrayNode();
        for (int i = 0; i < pools.size(); i++) {
            if (locks.contains(i)) {
                result.add(original.get(i));
            } else {
                JsonNode candidates = pools.get(i);
                int id = candidates.get(random.nextInt(candidates.size())).asInt();
                JsonNode row = RULES.path("attrs").path(Integer.toString(id));
                result.addObject().put("id", id).put("type", row.get(0).asInt()).put("value", row.get(1).asInt());
            }
        }
        return result;
    }
}
