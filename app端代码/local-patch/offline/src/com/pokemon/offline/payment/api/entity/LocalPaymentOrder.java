package com.pokemon.offline.payment.api.entity;

/**
 * 本地模拟充值订单数据库实体，用于保存下单时的商品及结算记录
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:04
 */
public class LocalPaymentOrder {

    /** 服务端生成的订单编号 */
    public final String id;

    /** 下单时的商品编号 */
    public final String productId;

    /** 下单时的模拟价格，单位为分 */
    public final long amount;

    /** 下单时约定的模拟钻石数量 */
    public final long reward;

    /** 订单是否已结算 */
    public final boolean confirmed;

    /**
     * 构造订单记录
     *
     * @param id 服务端订单编号
     * @param productId 商品编号
     * @param amount 模拟价格，单位为分
     * @param reward 模拟钻石数量
     * @param confirmed 是否已结算
     */
    public LocalPaymentOrder(String id, String productId, long amount, long reward, boolean confirmed) {
        this.id = id;
        this.productId = productId;
        this.amount = amount;
        this.reward = reward;
        this.confirmed = confirmed;
    }
}
