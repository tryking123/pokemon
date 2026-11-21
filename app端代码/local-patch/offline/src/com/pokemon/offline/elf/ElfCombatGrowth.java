package com.pokemon.offline.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.offline.elf.api.entity.LocalElf;
import java.util.Map;

/**
 * 将存档的培养值转换为本地单人战斗和战力可共用的增量。
 *
 * @author tongruiyuan
 * @since 2026-11-19 00:30
 */
public final class ElfCombatGrowth {
    private static final String[] ATTRIBUTES = {
        "hp_max", "physical_attack", "special_attack", "physical_defense", "special_defense", "speed"
    };

    /** 防止创建纯计算对象。 */
    private ElfCombatGrowth() {
    }

    /**
     * 按原表提供的星级、觉醒和超古代系数调整本地基础值。
     * 进阶和六项训练数值按离线战斗的单位换算，初始一星的基础值沿用旧存档表现。
     *
     * @param elf 精灵培养状态
     * @param attr 战斗属性名
     * @param base 当前玩法提供的基础值
     * @return 培养调整后的本地战斗属性
     */
    public static int attribute(LocalElf elf, String attr, int base) {
        JsonNode state = elf.training;
        int star = state.path("star").asInt(1);
        int awaken = state.path("awakenlv").asInt();
        int advanced = state.path("advanced").asInt();
        double starBonus = ElfTrainingCatalog.row("variable", 1019 + star).path("data5").asDouble()
                - ElfTrainingCatalog.row("variable", 1020).path("data5").asDouble();
        double awakeBonus = ElfTrainingCatalog.row("variable", 2213 + awaken).path("data4").asDouble();
        double ancientBonus = ElfTrainingCatalog.row("ancient", state.path("ancient").asInt(-1))
                .path(attr + "_bonus").asDouble() / 1000;
        double natureBonus = ElfTrainingCatalog.row("nature", state.path("nature").asInt(1006))
                .path(attr + "_bonus").asDouble() / 1000;
        int individual = state.path("individual").path(attr).asInt();
        int effort = state.path("effort").path(attr).asInt();
        double boost = 1 + starBonus + awakeBonus + ancientBonus + natureBonus + advanced * 0.05
                + ElfCharacterRules.percentBonus(elf, attr) / 1000.0;
        JsonNode travel=state.path("travelEffects").path(attr);
        double eventFactor=travel.path("until").asLong()>java.time.Instant.now().getEpochSecond()
                ? 1.0+travel.path("percent").asInt()/100.0 : 1.0;
        return Math.max(1, (int) Math.round((base + individual / 10.0 + effort / 4.0) * boost * eventFactor)
                + StoneWearRules.bonus(elf, attr) + ElfCharacterRules.flatBonus(elf, attr));
    }

    /**
     * 与实战斗属性一致的战力取值：在培养属性上叠加研究加成百分比。
     * 研究面板在战斗中按千分比放大六项属性，战力此前漏算这一层，导致与界面各项不匹配。
     *
     * @param elf 精灵培养状态
     * @param attr 战斗属性名
     * @param base 当前玩法提供的基础值
     * @return 研究加成后的本地战斗属性
     */
    public static int powerAttribute(LocalElf elf, String attr, int base) {
        int value = attribute(elf, attr, base);
        if (elf.training.has("pcl")) {
            String[] bases = {"hp_max", "speed", "physical_attack", "physical_defense", "special_attack", "special_defense"};
            for (int i = 0; i < bases.length; i++) {
                if (bases[i].equals(attr)) {
                    value += (int) ((long) value * researchValue(elf, 24 + i) / 1000);
                    break;
                }
            }
        }
        return value;
    }

    /** 汇总当前两个研究等级及其共鸣的原表属性或评分。
     * @param elf 持有的精灵
     * @param code 属性编号，零表示战力评分
     * @return 当前总增量
     */
    public static int researchValue(LocalElf elf, int code) {
        JsonNode slots = elf.training.path("pcl");
        if (!slots.isArray()) {
            return 0;
        }
        JsonNode rules = PclResearchService.RULES;
        int value = researchRow(rules.path("resonance").path(elf.training.path("pcltype").asText()), code);
        for (JsonNode slot : slots) {
            int id = slot.path("path").asInt()*100 + slot.path("level").asInt();
            value += researchRow(rules.path("levels").path(String.valueOf(id)), code);
        }
        return value;
    }

    /** 读取当前研究配置的评分或指定属性。
     * @param row 原表行
     * @param code 属性编号，零表示评分
     * @return 本档数值
     */
    private static int researchRow(JsonNode row, int code) {
        if (code == 0) {
            return row.path("score").asInt();
        }
        JsonNode attributes = row.path("att");
        for (int i = 0; i + 1 < attributes.size(); i += 2) {
            if (attributes.get(i).asInt() == code) {
                return attributes.get(i+1).asInt();
            }
        }
        return 0;
    }

    /**
     * 更新某个己方战斗单位的培养属性，保留玩法设置的基础战斗难度。
     *
     * @param elf 精灵培养状态
     * @param fighter 当前玩法生成的己方战斗数据
     */
    public static void apply(LocalElf elf, Map<String, Object> fighter) {
        for (String attr : ATTRIBUTES) {
            fighter.put(attr, attribute(elf, attr, ((Number) fighter.get(attr)).intValue()));
        }
        if (elf.training.has("pcl")) {
            JsonNode trait = PclResearchService.RULES.path("resonance")
                    .path(elf.training.path("pcltype").asText()).path("character");
            if (trait.isArray()) {
                java.util.List<Integer> triggers = new java.util.ArrayList<>();
                Object existing = fighter.get("trigger");
                if (existing instanceof Iterable) {
                    for (Object value : (Iterable<?>)existing) {
                        triggers.add(((Number)value).intValue());
                    }
                }
                for (JsonNode value : trait) {
                    if (!triggers.contains(value.asInt())) {
                        triggers.add(value.asInt());
                    }
                }
                fighter.put("trigger",triggers);
            }
            String[] bases = {"hp_max","speed","physical_attack","physical_defense","special_attack","special_defense"};
            for (int i=0; i<bases.length; i++) {
                String key = bases[i];
                int current = ((Number)fighter.get(key)).intValue();
                fighter.put(key, current + (int)((long)current * researchValue(elf,24+i)/1000));
            }
            int[] codes = {13,20,22,23,63,101};
            int[] scales = {1,1,1,1,1,1};
            String[] fields = {"toughness","reduced","crit_damage_bonus","damage_bonus","add_skill_point_limit","real_damage_reduction"};
            for (int i=0; i<codes.length; i++) {
                int added = researchValue(elf,codes[i]) * scales[i];
                if (added != 0) {
                    fighter.put(fields[i], ((Number)fighter.getOrDefault(fields[i],0)).intValue()+added);
                }
            }
        }
        // 效果型特性：触发编号并入开战快照，与研究共鸣、极巨化被动同一去重口径。
        ElfCharacterRules.applyTriggers(elf, fighter);
        JsonNode love = ElfTrainingCatalog.row("love", elf.training.path("love").asInt(1));
        for (String attr : new String[]{"hit_chance", "dodge_chance", "crit_chance", "damage_bonus"}) {
            Number existing = (Number) fighter.getOrDefault(attr, 0);
            int initial = ElfTrainingCatalog.row("love", 1).path(attr).asInt();
            fighter.put(attr, existing.intValue() + love.path(attr).asInt() - initial);
        }
        GoldCarryRules.apply(elf, fighter);
        StoneWearRules.applySecondary(elf, fighter);
        DynamaxRules.applyStats(elf, fighter);
        fighter.put("hp", fighter.get("hp_max"));
        fighter.put("vein", elf.training.path("veinlevel").asInt());
    }
}
