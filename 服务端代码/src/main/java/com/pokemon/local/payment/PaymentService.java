package com.pokemon.local.payment;

import com.pokemon.local.payment.api.vo.CreateOrderVO;

/**
 * 本地模拟充值 Service
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:04
 */
public interface PaymentService {

    /**
     * 查询可模拟充值的商品档位
     *
     * @return 商品档位列表
     */
    Object products();

    /**
     * 创建或查询客户端订单编号对应的充值订单
     *
     * @param authorization HTTP 授权请求头
     * @param vo 模拟充值下单信息
     * @return 当前订单状态和账号余额
     */
    Object createOrder(String authorization, CreateOrderVO vo);

    /**
     * 结算模拟充值订单并发放钻石
     *
     * @param authorization HTTP 授权请求头
     * @param orderId 服务端订单编号
     * @return 订单状态和账号余额
     */
    Object confirmOrder(String authorization, String orderId);
}
