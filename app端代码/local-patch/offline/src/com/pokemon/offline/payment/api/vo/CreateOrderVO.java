package com.pokemon.offline.payment.api.vo;

/**
 * 模拟充值下单视图对象，用于提交商品和客户端订单标识
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:04
 */
public class CreateOrderVO {

    /** 服务端商品编号 */
    public String productId;

    /** 客户端生成的订单编号，重试时保持一致 */
    public String clientOrderId;
}
