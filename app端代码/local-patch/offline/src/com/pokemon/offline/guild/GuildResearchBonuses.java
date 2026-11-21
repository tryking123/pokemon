package com.pokemon.offline.guild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 公会考古加成计算器，按原阵位冻结石盘、徽章与化石的已提交属性。
 *
 * @author tongruiyuan
 * @since 2026-11-22 02:27
 */
public final class GuildResearchBonuses {
    private final JsonNode rules;
    private static final String[] FIELDS = {"hp_max", "physical_attack", "special_attack", "physical_defense",
            "special_defense", "speed", "hp_max_bonus", "physical_attack_bonus", "special_attack_bonus",
            "physical_defense_bonus", "special_defense_bonus", "speed_bonus", "damage_bonus",
            "physical_damage_bonus", "special_damage_bonus", "ultra_reduce", "rage_provide", "element_provide"};
    private static final int[] CODES = {5, 6, 7, 8, 9, 14, 24, 26, 28, 27, 29, 25, 23, 59, 60, 108, 109, 110};

    /**
     * 加载与客户端一致的原表。
     * @param json 配置解析器
     * @throws IOException 随包配置读取失败
     */
    public GuildResearchBonuses(ObjectMapper json) throws IOException {
        try (InputStream input = new ClassPathResource("guild/research-bonuses.json").getInputStream()) {
            rules = json.readTree(input);
        }
    }

    /**
     * 冻结六个阵位的独立加成，不把前后排限定扩散到全队。
     * @param state 已提交的考古存档
     * @return 阵位到原属性代码的映射
     */
    public Map<Integer, Map<Integer, Integer>> snapshot(JsonNode state) {
        Map<Integer, Map<Integer, Integer>> result = new HashMap<>();
        for (int position = 0; position <= 6; position++) {
            Map<Integer, Integer> values = new HashMap<>();
            for (int piece = 1; piece <= 8; piece++) {
                if (state.path("pieces").path(String.valueOf(piece)).asInt() == 1) {
                    pairs(values, rules.path("slatebase").path(String.valueOf(piece)).path("attribute"));
                }
            }
            fields(values, rules.path("slate").path(state.path("tabletLevel").asText("0")));
            levels(values, state.path("badge"), "badge", position);
            levels(values, state.path("fossil"), "fossil", position);
            for (JsonNode suit : rules.path("badgesuit")) {
                int common = Integer.MAX_VALUE;
                for (JsonNode index : suit.path("badge")) {
                    common = Math.min(common, state.path("badge").path(index.asText()).asInt());
                }
                for (int tier = 1; tier <= 4; tier++) {
                    int required = suit.path("level").path(String.valueOf(tier)).asInt(Integer.MAX_VALUE);
                    if (common >= required) pairs(values, suit.path("effect" + tier));
                }
            }
            java.util.Iterator<Map.Entry<String, JsonNode>> suits = state.path("fossilsuit").fields();
            while (suits.hasNext()) {
                Map.Entry<String, JsonNode> saved = suits.next();
                if (saved.getValue().asInt() <= 0) continue;
                JsonNode row = rules.path("fossilsuit").path(String.valueOf(Integer.parseInt(saved.getKey()) * 10000 + saved.getValue().asInt()));
                for (int effect = 1; effect <= 2; effect++) {
                    if (contains(row.path("position" + effect), position)) pairs(values, row.path("effect" + effect));
                }
            }
            result.put(position, values);
        }
        return result;
    }

    /**
     * 读取各部件当前档位的总量。
     * @param values 属性累计值
     * @param saved 部件等级
     * @param table 原表名称
     * @param position 当前阵位
     */
    private void levels(Map<Integer, Integer> values, JsonNode saved, String table, int position) {
        saved.fields().forEachRemaining(entry -> {
            if (entry.getValue().asInt() <= 0) return;
            JsonNode row = rules.path(table).path(String.valueOf(Integer.parseInt(entry.getKey()) * 100 + entry.getValue().asInt()));
            if (contains(row.path("position"), position)) fields(values, row);
        });
    }

    /**
     * 核对原字典数组包含指定阵位。
     * @param positions 原阵位字典
     * @param position 当前阵位
     * @return 适用时为真
     */
    private static boolean contains(JsonNode positions, int position) {
        for (JsonNode value : positions) if (value.asInt() == position) return true;
        return false;
    }

    /**
     * 叠加原命名字段。
     * @param values 属性累计值
     * @param row 当前档位
     */
    private static void fields(Map<Integer, Integer> values, JsonNode row) {
        for (int i = 0; i < FIELDS.length; i++) {
            if (row.has(FIELDS[i])) values.merge(CODES[i], row.path(FIELDS[i]).asInt(), Math::addExact);
        }
    }

    /**
     * 叠加原属性编号、数值交替排列的字典数组。
     * @param values 属性累计值
     * @param pairs 原属性字典
     */
    private static void pairs(Map<Integer, Integer> values, JsonNode pairs) {
        for (int i = 1; i < pairs.size(); i += 2) {
            values.merge(pairs.path(String.valueOf(i)).asInt(), pairs.path(String.valueOf(i + 1)).asInt(), Math::addExact);
        }
    }
}
