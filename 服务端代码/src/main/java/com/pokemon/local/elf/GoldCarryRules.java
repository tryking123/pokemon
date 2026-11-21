package com.pokemon.local.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.local.elf.api.entity.LocalElf;
import java.util.Map;

/** 黄金携带品的原开战属性及可核对的直接真伤技能。
 * @author tongruiyuan
 * @since 2026-11-20 14:01
 */
public final class GoldCarryRules {
    static final JsonNode DATA = ElfTrainingCatalog.load("elf/gold-carry.json");

    /** 使用固定原表。 */
    private GoldCarryRules() { }

    /** 识别已配置的黄金携带模板。
     * @param id 携带品模板
     * @return 是否属于黄金原表
     */
    public static boolean isGold(int id) {
        return DATA.path("gear").has(Integer.toString(id));
    }

    /** 将已装备三槽黄金携带的原 character 属性写入开战快照。
     * @param elf 当前精灵
     * @param fighter 开战属性
     */
    static void apply(LocalElf elf, Map<String,Object> fighter) {
        elf.goldCarry.fields().forEachRemaining(entry -> {
            JsonNode row = DATA.path("gear").path(entry.getKey()).path(entry.getValue().asInt());
            if (!row.has("field")) return;
            String field = row.path("field").asText();
            Number initial = (Number)fighter.getOrDefault(field, 0);
            fighter.put(field, initial.intValue() + row.path("value").asInt());
        });
    }

    /** 按佩戴模板冻结旧版回合计算所需的黄金属性。
     * @param elf 玩家精灵
     * @param actor 本地战斗快照
     */
    public static void snapshot(LocalElf elf, com.fasterxml.jackson.databind.node.ObjectNode actor) {
        if (elf.goldCarry.size() == 0) return;
        com.fasterxml.jackson.databind.node.ObjectNode bonus = actor.putObject("goldCarry");
        elf.goldCarry.fields().forEachRemaining(entry -> {
            JsonNode row = DATA.path("gear").path(entry.getKey()).path(entry.getValue().asInt());
            if (row.has("field")) bonus.put(row.path("field").asText(), row.path("value").asInt());
        });
    }

    /** 计算底包 spelleffect 11 中敌方承受的物攻或特攻额外真伤。
     * @param skill 实际命中的技能
     * @param attacker 攻方快照
     * @param target 守方快照
     * @return 目标额外损失的生命
     */
    public static int extraDamage(int skill, JsonNode attacker, JsonNode target) {
        int reduction = Math.max(0, Math.min(990, target.path("real_damage_reduction").asInt()));
        int sum = 0;
        for (JsonNode part : DATA.path("trueHits").path(Integer.toString(skill))) {
            long power = attacker.path(part.get(0).asInt() == 6 ? "physical_attack" : "special_attack").asLong();
            long raw = (power * part.get(1).asInt() + 999) / 1000;
            sum = (int)Math.min(Integer.MAX_VALUE, sum + raw - raw * reduction / 1000);
        }
        return sum;
    }
}
