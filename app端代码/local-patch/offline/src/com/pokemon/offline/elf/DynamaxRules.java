package com.pokemon.offline.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.offline.elf.api.entity.LocalElf;
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

    /**
     * 在同码百分比及固定加成计算完成后应用原极巨化最终倍率。
     * 原30至33码属于patk_affix、datk_affix、hp_affix、speed_affix，不能提前缩放未合并的基础值。
     * @param elf 当前精灵及极巨化存档
     * @param field 原战斗属性名
     * @param base 已合并角色、突破及阵位来源的当前属性
     * @return 极巨化最终倍率计算后的属性
     */
    public static int attribute(LocalElf elf, String field, int base) {
        int path=elf.training.path("dynapath").asInt();
        int level=elf.training.path("dynalevel").asInt();
        if(path<1 || path>3 || level<1) return base;
        String[] fields={"physical_attack","special_attack","hp_max","speed"};
        JsonNode values=route(level,path,"_nature");
        int bonus=0;
        for(int i=0;i+1<values.size();i+=2) {
            int index=values.get(i).asInt()-30;
            if(index>=0 && index<fields.length && fields[index].equals(field)) bonus+=values.get(i+1).asInt();
        }
        return bonus==0?base:Math.max(1,Math.toIntExact((long)base*(1000+bonus)/1000));
    }

    /** 同步原极巨路线、攻击类别及被动；四项最终倍率由统一属性出口处理。
     * @param elf 精灵实例
     * @param stats 当前战斗数据
     */
    public static void applyStats(LocalElf elf, Map<String,Object> stats) {
        int path = elf.training.path("dynapath").asInt();
        if (path < 1 || path > 3) { return; }
        int level = elf.training.path("dynalevel").asInt();
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
