package com.pokemon.offline.inventory.api.entity;

/**
 * 角色背包物品库存实体
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:37
 */
public final class InventoryItem {
    /** 客户端配置中的物品编号 */
    public final int id;
    /** 堆叠数量 */
    public final long quantity;
    /** 当前背包格子 */
    public final int position;

    /**
     * 构造一条背包库存
     *
     * @param id 配置编号
     * @param quantity 堆叠数量
     * @param position 背包格子
     */
    public InventoryItem(int id, long quantity, int position) {
        this.id = id;
        this.quantity = quantity;
        this.position = position;
    }
}
