package com.pokemon.local.elf.api.entity;

/**
 * 本地携带品或 P 力石实例，记录归属、装配位置和保护状态。
 *
 * @author tongruiyuan
 * @since 2026-11-19 00:24
 */
public final class ElfGear {
    public final String id;
    public final int kind;
    public final int configId;
    public final String elfId;
    public final int position;
    public final boolean locked;
    public final int star;
    /** 实例附加存档，包含已确认的 P 力石洗炼词条。 */
    public final String details;

    /**
     * 从装备存档查出的字段创建装备实例。
     *
     * @param id 装备实例编号
     * @param kind 装备类别
     * @param configId 底包模板编号
     * @param elfId 当前装配的精灵
     * @param position 装配位置
     * @param locked 是否保护
     * @param star 装备星级
     */
    public ElfGear(String id, int kind, int configId, String elfId, int position, boolean locked, int star) {
        this(id, kind, configId, elfId, position, locked, star, "{}");
    }

    /**
     * 从装备实例及附加存档创建快照。
     * @param id 实例编号
     * @param kind 装备类别
     * @param configId 配置编号
     * @param elfId 装配精灵编号
     * @param position 装配位置
     * @param locked 是否保护
     * @param star 星级
     * @param details 已保存的实例属性
     */
    public ElfGear(String id, int kind, int configId, String elfId, int position, boolean locked, int star, String details) {
        this.id = id;
        this.kind = kind;
        this.configId = configId;
        this.elfId = elfId;
        this.position = position;
        this.locked = locked;
        this.star = star;
        this.details = details;
    }
}
