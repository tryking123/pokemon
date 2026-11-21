package com.pokemon.local.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.local.elf.api.entity.LocalElf;
import java.util.Map;


/**
 * 极巨化原表、路线属性和技能书选择规则。
 * @author tongruiyuan
 * @since 2026-11-20 11:03
 */
public final class DynamaxRules {
    static final JsonNode TABLES = ElfTrainingCatalog.load("elf/dynamax.json");
    static final String[] PATHS = {"", "attack", "control", "auxiliary"};

    /** 只提供原表及计算方法。 */
    private DynamaxRules() {}

    /** 判断原整书表是否包含指定的一级、二级或三级书。
     * @param book 整书模板编号
     * @return 属于完整的技能书时为真
     */
    public static boolean isBook(int book) {
        return TABLES.path("books").has(Integer.toString(book));
    }

    /** 按模板及稀有度核对该精灵的极巨路线和最大等级。
     * @param elf 精灵实例
     * @param path 路线
     * @return 合法路线对应的上限
     */
    static int maxLevel(LocalElf elf, int path) {
        JsonNode base = ElfTrainingCatalog.row("species", elf.configId);
        JsonNode evolution = base.path("evolution");
        if (evolution.isArray() && evolution.path(0).asInt() > 0) { return 0; }
        for (JsonNode candidate : TABLES.path("routes").path(Integer.toString(elf.configId))) {
            if (candidate.asInt() == path) {
                for (JsonNode rule : TABLES.path("caps")) {
                    if (base.path("assess").asInt() <= rule.path("quality").asInt()) { return rule.path("level").asInt(); }
                }
            }
        }
        return 0;
    }

    /** 读取当前阶的路线配置。
     * @param level 极巨等阶
     * @param path 路线
     * @param suffix 原配置字段后缀
     * @return 对应数组
     */
    static JsonNode route(int level, int path, String suffix) {
        return TABLES.path("levels").path(Integer.toString(level)).path(PATHS[path]+suffix);
    }

    /** 读取该卷当前等级的镶嵌门槛与升级费用。
     * @param book 完整书编号
     * @return 升级及佩戴配置
     */
    static JsonNode bookLevel(int book) {
        return TABLES.path("bookLevels").path(Integer.toString(book / 10000 * 1000 + book / 100 % 100));
    }

    /** 把原极巨修正并入四项当前实战属性。
     * @param elf 精灵实例
     * @param stats 当前战斗数据
     */
    public static void applyStats(LocalElf elf, Map<String,Object> stats) {
        int path = elf.training.path("dynapath").asInt();
        if (path < 1 || path > 3) { return; }
        int level = elf.training.path("dynalevel").asInt();
        JsonNode values = route(level,path,"_nature");
        String[] fields = {"physical_attack", "special_attack", "hp_max", "speed"};
        for (int i=0; i+1<values.size(); i+=2) {
            String key = fields[values.get(i).asInt()-30];
            int amount = ((Number)stats.get(key)).intValue();
            stats.put(key, Math.max(1, (int)((long)amount*(1000 + values.get(i+1).asInt()) / 1000)));
        }
        stats.put("dynapath",path);
        if (elf.training.has("dynatype")) { stats.put("dynatype",elf.training.path("dynatype").asInt()); }
        JsonNode passive = route(level,path,"_skill");
        if (passive.isArray()) {
            java.util.List<Integer> triggers = new java.util.ArrayList<>();
            Object old = stats.get("trigger");
            if (old instanceof Iterable) { for (Object id : (Iterable<?>)old) { triggers.add(((Number)id).intValue()); } }
            for (JsonNode id : passive) { if (!triggers.contains(id.asInt())) { triggers.add(id.asInt()); } }
            stats.put("trigger",triggers);
        }
    }

    /** 按书和方向在开场时冻结本场的极巨技能。
     * @param elf 书籍归属精灵
     * @param seed 当前场次的冻结种子
     * @return 该场技能号，未镶书时为零
     */
    public static int battleSkill(LocalElf elf,int seed) {
        int path = elf.training.path("dynapath").asInt();
        int book = elf.training.path("dynabook").asInt();
        if (path < 1 || path > 3 || book == 0) { return 0; }
        JsonNode entries = TABLES.path("books").path(Integer.toString(book)).path(PATHS[path]);
        int total = 0;
        for (int i=1; i<entries.size(); i+=2) { total += entries.get(i).asInt(); }
        if (total == 0) { return 0; }
        int roll = new java.util.Random(((long)seed<<32) ^ elf.id.hashCode()).nextInt(total);
        for (int i=0; i+1<entries.size(); i+=2) {
            roll -= entries.get(i+1).asInt();
            if (roll < 0) { return entries.get(i).asInt(); }
        }
        throw new IllegalStateException("极巨技能权重不完整");
    }
}
