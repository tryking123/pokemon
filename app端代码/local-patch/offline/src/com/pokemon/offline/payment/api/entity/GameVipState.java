package com.pokemon.offline.payment.api.entity;

/**
 * 角色已确认游戏订单及已使用道具对应的 VIP 经验和等级
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:38
 */
public final class GameVipState {
    /** 当前 VIP 等级，按底包 VIP 经验门槛计算 */
    public final int level;
    /** 从已结算订单及道具独立流水汇总的经验 */
    public final long experience;

    /**
     * 保存当前角色 VIP 数据
     *
     * @param level 当前 VIP 等级
     * @param experience 累计 VIP 经验
     */
    public GameVipState(int level, long experience) {
        this.level = level;
        this.experience = experience;
    }
}
