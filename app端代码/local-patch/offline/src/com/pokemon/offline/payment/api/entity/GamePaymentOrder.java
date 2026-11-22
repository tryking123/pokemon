package com.pokemon.offline.payment.api.entity;

/**
 * 记录 Unity 角色模拟充值的商品、金额和结算状态
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:39
 */
public final class GamePaymentOrder {
    public final String id;
    public final String roleId;
    public final int paysid;
    public final long amount;
    public final long reward;
    public final long expiresAt;
    public final boolean confirmed;

    /**
     * 初始化角色模拟订单
     *
     * @param id 服务端订单编号
     * @param roleId 到账角色编号
     * @param paysid 原游戏充值栏位
     * @param amount 模拟价格，单位分
     * @param reward 实际发放的角色钻石
     * @param expiresAt 待确认订单过期时间，单位毫秒
     * @param confirmed 是否已完成结算
     */
    public GamePaymentOrder(String id, String roleId, int paysid, long amount, long reward,
                            long expiresAt, boolean confirmed) {
        this.id = id;
        this.roleId = roleId;
        this.paysid = paysid;
        this.amount = amount;
        this.reward = reward;
        this.expiresAt = expiresAt;
        this.confirmed = confirmed;
    }
}
