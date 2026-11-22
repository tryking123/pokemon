package com.pokemon.offline.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.offline.elf.api.entity.LocalElf;
import java.util.Map;

/**
 * 奥义石穿戴等级、星级基础属性与洗练词条的战斗换算。
 *
 * @author tongruiyuan
 * @since 2026-11-20 09:53
 */
public final class StoneWearRules {
    private static final JsonNode RULES = ElfTrainingCatalog.load("elf/stone-wear.json");
    private static final String[] BASE = {"hp_max", "physical_attack", "special_attack", "physical_defense",
            "special_defense", "hit_chance", "dodge_chance", "crit_chance", "toughness", "speed"};

    /** 保留为静态原表。 */
    private StoneWearRules() {
    }

    /**
     * 读取模板对精灵的最低等级要求。
     * @param template 原奥义石编号
     * @return 等级要求，未知模板永不满足
     */
    static int level(int template) {
        return RULES.path("stones").path(String.valueOf(template)).path("level").asInt(Integer.MAX_VALUE);
    }

    /**
     * 读取某项原战斗属性的实际加值，比例词条采用原千分单位。
     * @param elf 实际持有精灵
     * @param field 原战斗协议字段
     * @return 已装备奥义石在当前星级的有效加值
     */
    public static int bonus(LocalElf elf, String field) {
        if (!elf.stone.has("id")) {
            return 0;
        }
        JsonNode power = RULES.path("stones").path(elf.stone.path("id").asText()).path("power")
                .path(elf.stone.path("elfequ_star").asInt());
        int total = 0;
        for (int i = 0; i + 1 < power.size(); i += 2) {
            if (field.equals(field(power.get(i).asInt()))) {
                total += power.get(i + 1).asInt();
            }
        }
        for (JsonNode extra : elf.stone.path("elfequ_addattr")) {
            int code = RULES.path("extra").path(extra.path("attrid").asText()).asInt();
            if (field.equals(field(code))) {
                total += extra.path("attrnum").asInt();
            }
        }
        return total;
    }

    /**
     * 叠加六项基础值之外的原命中、暴击、增减伤等字段。
     * @param elf 实际持有精灵
     * @param fighter 当前战斗单位快照
     */
    static void applySecondary(LocalElf elf, Map<String, Object> fighter) {
        if (!elf.stone.has("id")) {
            return;
        }
        for (int code : new int[]{10, 11, 12, 13, 20, 22, 23, 59, 60, 61, 62}) {
            String key = field(code);
            int add = bonus(elf, key);
            if (add != 0) {
                fighter.put(key, ((Number) fighter.getOrDefault(key, 0)).intValue() + add);
            }
        }
    }

    /**
     * 将游戏属性编号映射到对应战斗字段。
     * @param code 原 attribute 属性编号
     * @return 原协议字段名
     */
    private static String field(int code) {
        if (code >= 5 && code <= 14) {
            return BASE[code - 5];
        }
        switch (code) {
            case 20: return "reduced";
            case 22: return "crit_damage_bonus";
            case 23: return "damage_bonus";
            case 59: return "physical_damage_bonus";
            case 60: return "special_damage_bonus";
            case 61: return "physical_damage_reduced";
            case 62: return "special_damage_reduced";
            default: return "";
        }
    }
}
