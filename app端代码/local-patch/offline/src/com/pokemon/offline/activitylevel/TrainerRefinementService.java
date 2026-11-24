package com.pokemon.offline.activitylevel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.pokemon.offline.activitylevel.api.entity.AdventureEquipment;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 训练家装备初始附属性、洗炼预览和确认服务。
 *
 * @author tongruiyuan
 * @since 2026-11-19 19:08
 */
@Service
public class TrainerRefinementService {
    private final ObjectMapper json;
    private final JsonNode rules;
    private final JsonNode advanceRules;
    private final RoleService roles;
    private final InventoryService inventory;
    private final LevelGiftMapper mapper;
    private static final TypeReference<List<Map<String, Integer>>> PROPERTIES =
            new TypeReference<List<Map<String, Integer>>>() {};

    /**
     * 将属性数组转换为原游戏协议结构。
     * @param properties 属性数组
     * @return 可写入原协议的属性列表
     */
    public List<Map<String, Integer>> properties(ArrayNode properties) {
        return json.convertValue(properties, PROPERTIES);
    }

    /**
     * 加载装备模板对应的费用与词条池。
     * @param json 配置序列化
     * @param roles 角色存档和事务锁
     * @param inventory 洗炼石和金币扣除
     * @param mapper 装备及候选词条存档
     * @throws IOException 读取配置失败
     */
    TrainerRefinementService(ObjectMapper json, RoleService roles, InventoryService inventory, LevelGiftMapper mapper)
            throws IOException {
        this.json = json;
        this.roles = roles;
        this.inventory = inventory;
        this.mapper = mapper;
        try (InputStream in = new ClassPathResource("catalog/gear-refine.json").getInputStream()) {
            rules = json.readTree(in);
        }
        try (InputStream in = new ClassPathResource("catalog/gear-advance.json").getInputStream()) {
            advanceRules = json.readTree(in);
        }
    }

    /**
     * 读取下发到客户端装备面板的附加属性：洗炼原值，进阶装备按进阶倍率折算，不含强化等级。
     * 强化加成由客户端按装备 level 在基础属性上本地计算，本数组只承载词条数值；
     * 既避免附加属性被强化值污染，也避免参战侧再次读取时把强化等级算两遍。
     * @param gear 已拥有的装备
     * @return 原协议 prop_t 数组
     */
    public ArrayNode attributes(AdventureEquipment gear) {
        return displayed(gear, rawAttributes(gear));
    }

    /** 将原洗炼值转换为进阶后实际附加属性，普通装备原样读取。
     * @param gear 装备实例
     * @param rolls 未进阶的词条
     * @return 原协议词条值
     */
    private ArrayNode displayed(AdventureEquipment gear,ArrayNode rolls) {
        if (!gear.advanced) return rolls;
        JsonNode cfg=advanceRules.path(Integer.toString(gear.configId));
        if (!cfg.isArray() || cfg.size() < 9) return rolls;
        ArrayNode result=rolls.deepCopy();
        for(int i=0;i<result.size() && i<3;i++) {
            ObjectNode r=(ObjectNode)result.get(i);
            int old=r.path("value").asInt();
            r.put("value",old+old*cfg.get(6+i).asInt()/1000);
        }
        return result;
    }

    /** 恢复未加进阶倍率的原洗炼词条。
     * @param gear 装备实例
     * @return 原始词条
     */
    private ArrayNode rawAttributes(AdventureEquipment gear) {
        JsonNode rule = rules.path("templates").path(Integer.toString(gear.configId));
        if (rule.isMissingNode()) {
            return json.createArrayNode();
        }
        String saved = mapper.attributes(gear.id);
        if (saved != null) {
            try {
                return (ArrayNode) json.readTree(saved);
            } catch (IOException ex) {
                throw new IllegalStateException("已保存的装备属性无法解析", ex);
            }
        }
        ArrayNode rolls = roll(rule, json.createArrayNode(), Collections.emptySet(), new Random(gear.id.hashCode()), false);
        // 首次生成的词条立即落库：重登/重进游戏后同一件装备词条与库内一致，不再每次临时重 roll。
        // 库内只存原值（不含进阶倍率、不含强化等级），两类加成在读取时分别结算。
        if (rolls.size() > 0) {
            mapper.saveAttributes(gear.id, rolls.toString());
        }
        return rolls;
    }

    /** 按已穿戴实例的原进阶比例计入真实参战属性，与未进阶原值分开结算。
     * @param id 角色编号
     * @param total 本次战斗的原属性累计
     */
    public void addAdvanceBonuses(String id,Map<Integer,Integer> total) {
        for (AdventureEquipment gear : mapper.equipped(id)) {
            if (!gear.advanced) continue;
            JsonNode cfg=advanceRules.path(Integer.toString(gear.configId));
            if (!cfg.isArray()) continue;
            total.merge(cfg.get(3).asInt(),cfg.get(4).asInt()*cfg.get(5).asInt()/1000,Math::addExact);
            ArrayNode rolls=rawAttributes(gear);
            for(int i=0;i<rolls.size() && i<3;i++) {
                JsonNode r=rolls.get(i);
                total.merge(r.path("type").asInt(),r.path("value").asInt()*cfg.get(6+i).asInt()/1000,Math::addExact);
            }
        }
    }

    /**
     * 把所有已穿戴装备的原始洗炼词条计入参战属性。
     * 进阶装备也须保留每条原值，前三条的额外倍率增量由
     * {@link #addAdvanceBonuses} 追加；第 4 条及之后没有进阶倍率，仍只计原值。
     * 每条原值只计一次；强化等级另由 {@link #addEnhanceBonuses} 单独计入。
     * @param id 角色编号
     * @param total 本次战斗的原属性累计
     */
    public void addRefinementBonuses(String id, Map<Integer, Integer> total) {
        for (AdventureEquipment gear : mapper.equipped(id)) {
            ArrayNode rolls = rawAttributes(gear);
            for (int i = 0; i < rolls.size(); i++) {
                JsonNode r = rolls.get(i);
                total.merge(r.path("type").asInt(), r.path("value").asInt(), Math::addExact);
            }
        }
    }

    /**
     * 按部位强化等级为六项属性提供固定加成。强化等级此前只影响装备评分，未进入战斗与战力；
     * 洗炼词条在低级装备上数值很小，按百分比放大会被取整成零，因此改为每级每项固定加一。
     * @param id 角色编号
     * @param total 本次战斗的原属性累计
     */
    public void addEnhanceBonuses(String id,Map<Integer,Integer> total) {
        for (AdventureEquipment gear : mapper.equipped(id)) {
            if (gear.level <= 0) continue;
            try {
                for (int code : new int[]{5, 6, 7, 8, 9, 14}) {
                    total.merge(code, gear.level, Math::addExact);
                }
            } catch (RuntimeException ignored) {
                // 单件装备异常不应让整个战力计算失败并显示为零。
            }
        }
    }

    /**
     * 对实际佩戴的装备预览一次洗炼，锁定的词条按原顺序保留。
     * @param roleId 角色编号
     * @param slot 穿戴部位
     * @param id 可选的装备实例编号
     * @param mode 道具模式为一，金币模式为零
     * @param reserve 从一开始的锁定词条位置
     * @param receipt 连接与请求唯一标记
     * @return 已持久化的候选，操作无效返回空引用
     */
    @Transactional
    public ArrayNode preview(String roleId, int slot, String id, int mode, List<?> reserve, String receipt) {
        roles.lockRole(roleId);
        AdventureEquipment gear = mapper.equipped(roleId).stream().filter(item -> item.slot == slot).findFirst().orElse(null);
        if (gear == null || id != null && !gear.id.equals(id) || mode < 0 || mode > 1 || mapper.refined(roleId, receipt)) {
            return null;
        }
        JsonNode rule = rules.path("templates").path(Integer.toString(gear.configId));
        ArrayNode current = rawAttributes(gear);
        if (reserve.size() >= current.size()) {
            return null;
        }
        Set<Integer> locked = new HashSet<>();
        for (Object index : reserve) {
            if (!(index instanceof Number)) {
                return null;
            }
            long n = ((Number) index).longValue();
            if (n < 1 || n > current.size() || !locked.add((int) n - 1)) {
                return null;
            }
        }
        long cost = rule.path(1 + mode).path(locked.size()).asLong();
        if (cost <= 0 || !inventory.spendMaterials(roleId, 21001, mode == 1 ? cost : 0, mode == 0 ? cost : 0, 0)) {
            return null;
        }
        ArrayNode candidate = roll(rule, current, locked, ThreadLocalRandom.current(), true);
        mapper.preview(roleId, gear.id, receipt, candidate.toString());
        return displayed(gear,candidate);
    }

    /** 按原装备档扣金币和对应部位进阶石，返回永久进阶后的实值。
     * @param id 角色编号
     * @param slot 已穿戴的部位
     * @param receipt 原连接请求回执
     * @return 进阶后原词条，条件不符为空
     */
    @Transactional
    public ArrayNode advance(String id,int slot,String receipt) {
        roles.lockRole(id);
        AdventureEquipment gear=mapper.equipped(id).stream().filter(e -> e.slot==slot).findFirst().orElse(null);
        if(gear==null || gear.advanced || mapper.refined(id,receipt)) return null;
        JsonNode cfg=advanceRules.path(Integer.toString(gear.configId));
        if(!cfg.isArray() || !inventory.spendMaterials(id,cfg.get(1).asInt(),cfg.get(2).asLong(),cfg.get(0).asLong(),0)) return null;
        mapper.advanceGear(id,gear.id,receipt);
        return displayed(new AdventureEquipment(gear.id,gear.configId,gear.slot,gear.level,true),rawAttributes(gear));
    }

    /**
     * 保存最近一次有效预览。
     * @param roleId 角色编号
     * @param receipt 本次保存请求回执
     * @return 已修改装备词条时为真
     */
    @Transactional
    public boolean save(String roleId, String receipt) {
        roles.lockRole(roleId);
        return mapper.commitPreview(roleId, receipt);
    }

    /**
     * 从同档原词条范围生成不重复的属性类别。
     * @param rule 装备词条与费用档
     * @param previous 已有属性
     * @param locked 要保留的属性下标
     * @param random 初始或洗炼随机源
     * @param maximum 洗炼时取抽中词条的上限，初始生成时在词条区间内取值
     * @return 按装备槽顺序排列的属性
     */
    private ArrayNode roll(JsonNode rule, ArrayNode previous, Set<Integer> locked, Random random, boolean maximum) {
        Set<Integer> reservedTypes = new HashSet<>();
        for (int index : locked) {
            reservedTypes.add(previous.get(index).path("type").asInt());
        }
        List<JsonNode> available = new ArrayList<>();
        for (JsonNode row : rules.path("pools").path(rule.get(0).asText())) {
            if (!reservedTypes.contains(row.get(1).asInt())) {
                available.add(row);
            }
        }
        Collections.shuffle(available, random);
        ArrayNode result = json.createArrayNode();
        for (int pos = 0; pos < rule.get(1).size(); pos++) {
            if (locked.contains(pos)) {
                result.add(previous.get(pos));
            } else {
                JsonNode row = available.remove(available.size() - 1);
                int low = row.get(2).asInt();
                int high = row.get(3).asInt();
                int value = maximum ? high : low + random.nextInt(high - low + 1);
                result.addObject().put("id", row.get(0).asInt()).put("type", row.get(1).asInt())
                        .put("value", value);
            }
        }
        return result;
    }
}
