package com.pokemon.local.gateway;

/**
 * P0 Sproto 结构的字段描述
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:46
 */
final class SprotoField {

    final String name;
    final int tag;
    final String type;
    final boolean array;

    /**
     * 读取字段的名字、标签和类型
     *
     * @param definition 字段声明
     */
    SprotoField(String definition) {
        String[] columns = definition.split(":");
        name = columns[0];
        tag = Integer.parseInt(columns[1]);
        array = columns[2].startsWith("*");
        // 键映射只影响 Lua 表的组织形式，线上的数组仍逐条编码。
        String element = array ? columns[2].substring(1) : columns[2];
        int keyed = element.indexOf('(');
        type = keyed < 0 ? element : element.substring(0, keyed);
    }
}
