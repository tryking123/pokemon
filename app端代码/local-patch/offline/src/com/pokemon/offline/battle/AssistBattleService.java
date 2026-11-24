package com.pokemon.offline.battle;

import com.pokemon.offline.elf.ElfCharacterRules;
import com.pokemon.offline.elf.ElfCombatGrowth;
import com.pokemon.offline.elf.ElfMapper;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.role.RoleCosmeticService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import java.io.InputStream;
import java.io.IOException;

/**
 * 助战精灵开战快照 Service，按已解锁槽位冻结属性、系别和精灵自身的特性。
 *
 * @author tongruiyuan
 * @since 2026-11-24 00:53
 */
@Service
public final class AssistBattleService {
    private static final JsonNode SLOT_RULES = loadSlotRules();
    private final ElfMapper elves;
    private final RoleCosmeticService cosmetics;

    /**
     * 绑定助战存档和角色战斗属性来源。
     * @param elves 精灵与编队存档
     * @param cosmetics 角色战斗加成
     */
    public AssistBattleService(ElfMapper elves, RoleCosmeticService cosmetics) {
        this.elves = elves;
        this.cosmetics = cosmetics;
    }

    /**
     * 冻结助战来源，排除本场已经出战的同一实例。
     * @param roleId 阵容所属角色
     * @param deployed 本场实际出战的精灵实例编号
     * @return 按助战槽排序的独立战斗来源
     */
    public List<Map<String, Object>> snapshot(String roleId, Collection<String> deployed) {
        Map<Integer, LocalElf> slots = elves.battleAssists(roleId);
        if (slots.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> seen = new HashSet<>(deployed);
        RoleCosmeticService.BattleBonuses bonuses = cosmetics.battleBonuses(roleId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Integer, LocalElf> slot : slots.entrySet()) {
            LocalElf elf = slot.getValue();
            FirstBattleData.Starter species = FirstBattleData.starter(elf.configId);
            if (species == null || !seen.add(elf.id)) {
                continue;
            }
            Map<String, Object> actor = new LinkedHashMap<>();
            actor.put("hp_max", 240);
            actor.put("physical_attack", 130 + species.physicalAttack);
            actor.put("special_attack", 130 + species.specialAttack);
            actor.put("physical_defense", 90 + species.physicalDefense);
            actor.put("special_defense", 90 + species.specialDefense);
            actor.put("speed", 100 + species.speed);
            ElfCombatGrowth.apply(elf, actor);
            // 助战槽不是前排阵位，角色全局加成仍计入，前排专属石板和宠物天赋不借位。
            bonuses.withBreakthrough(elf).apply(actor);
            actor.remove("trigger");
            ElfCharacterRules.applyTriggers(elf, actor);
            actor.put("id", elf.id);
            actor.put("parent", roleId);
            actor.put("sid", elf.configId);
            actor.put("sprite", elf.configId);
            actor.put("level", elf.level);
            actor.put("quality", species.quality);
            actor.put("type1", species.type);
            actor.put("type2", species.secondType);
            actor.put("position", slot.getKey());
            JsonNode rule = SLOT_RULES.path(Integer.toString(1000 + slot.getKey()));
            int percent = slotPercent(elf, species.quality, rule);
            String attribute = slotAttribute(rule.path("type").asInt());
            int base = attribute == null ? 0 : ((Number) actor.getOrDefault(attribute, 0)).intValue();
            actor.put("assist_slot_type", rule.path("type").asInt());
            actor.put("assist_slot_percent", percent);
            actor.put("assist_slot_value", (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, base) * percent / 100));
            result.add(actor);
        }
        return result;
    }

    /**
     * 读取与原客户端助战页面同版本的二十四槽配置。
     * @return 按槽位编号索引的原始 cheer 配置
     */
    private static JsonNode loadSlotRules() {
        try (InputStream input = new ClassPathResource("battle/assist-slots.json").getInputStream()) {
            return new ObjectMapper().readTree(input).path("slots");
        } catch (IOException failure) {
            throw new IllegalStateException("助战槽位配置无法读取", failure);
        }
    }

    /**
     * 将原助战属性编号映射到基础六维。
     * @param type 原属性编号
     * @return 属性名称，非六维编号返回空
     */
    private static String slotAttribute(int type) {
        switch (type) {
            case 5: return "hp_max";
            case 6: return "physical_attack";
            case 7: return "special_attack";
            case 8: return "physical_defense";
            case 9: return "special_defense";
            case 14: return "speed";
            default: return null;
        }
    }

    /**
     * 按原槽位六项激活条件累加转换百分比，不修改任何精灵属性。
     * @param elf 当前助战精灵
     * @param quality 物种品质
     * @param rule 当前槽位的原 cheer 配置
     * @return 已激活的整数百分比
     */
    private static int slotPercent(LocalElf elf, int quality, JsonNode rule) {
        if (rule.isMissingNode()) return 0;
        int individual = 0;
        for (String field : new String[]{"hp_max", "physical_attack", "special_attack", "physical_defense", "special_defense", "speed"}) {
            individual += elf.training.path("individual").path(field).asInt();
        }
        int[] actual = {1, quality, elf.level, individual, elf.training.path("star").asInt(1),
                elf.training.path("awakenlv").asInt()};
        int percent = rule.path("num1").asInt();
        for (int i=2; i<=6; i++) {
            if (actual[i-1] >= rule.path("condition"+i).path(1).asInt(Integer.MAX_VALUE)) {
                percent += rule.path("num"+i).asInt();
            }
        }
        return Math.max(0, percent);
    }
}
