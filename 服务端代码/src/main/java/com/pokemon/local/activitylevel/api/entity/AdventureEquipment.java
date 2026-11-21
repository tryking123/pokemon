package com.pokemon.local.activitylevel.api.entity;

/**
 * 冒险奖励的独立训练家装备实例，用于保存与背包同步。
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:50
 */
public final class AdventureEquipment {
    /** 装备实例编号。 */
    public final String id;
    /** equip_proto 模板编号。 */
    public final int configId;
    /** 装备所属部位。 */
    public final int slot;
    /** 已佩戴时，该部位当前强化等级。 */
    public final int level;
    /** 此装备实例是否已经进阶。 */
    public final boolean advanced;

    /**
     * 读取持久化的装备实例。
     *
     * @param id 实例编号
     * @param configId 装备模板编号
     * @param slot 原始部位
     */
    public AdventureEquipment(String id, int configId, int slot) {
        this(id, configId, slot, 0);
    }

    /**
     * 读取已佩戴装备与其部位的强化等级。
     *
     * @param id 实例编号
     * @param configId 装备模板编号
     * @param slot 原始部位
     * @param level 部位强化等级
     */
    public AdventureEquipment(String id, int configId, int slot, int level) {
        this(id, configId, slot, level, false);
    }

    /** 读取装备部位等级及实例级进阶标记。
     * @param id 装备实例
     * @param configId 底包模板
     * @param slot 部位
     * @param level 部位强化等级
     * @param advanced 已进阶
     */
    public AdventureEquipment(String id, int configId, int slot, int level, boolean advanced) {
        this.advanced = advanced;
        this.id = id;
        this.configId = configId;
        this.slot = slot;
        this.level = level;
    }
}
