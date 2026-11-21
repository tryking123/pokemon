package com.pokemon.local.payment;

import com.pokemon.local.payment.api.vo.CreateOrderVO;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地模拟充值商品、下单和结算接口
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:04
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService payments;

    /**
     * 初始化模拟充值接口
     *
     * @param payments 模拟充值服务
     */
    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /**
     * 查询本地模拟充值商品
     *
     * @return 商品档位列表
     */
    @GetMapping("/products")
    public Object products() {
        return payments.products();
    }

    /**
     * 创建模拟充值订单
     *
     * @param authorization HTTP 授权请求头
     * @param vo 模拟充值下单信息
     * @return 订单状态和账号余额
     */
    @PostMapping("/orders")
    public Object createOrder(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                              @RequestBody CreateOrderVO vo) {
        return payments.createOrder(authorization, vo);
    }

    /**
     * 确认模拟充值订单
     *
     * @param authorization HTTP 授权请求头
     * @param orderId 服务端订单编号
     * @return 订单状态和账号余额
     */
    @PostMapping("/orders/{orderId}/confirm")
    public Object confirmOrder(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                               @PathVariable String orderId) {
        return payments.confirmOrder(authorization, orderId);
    }
}
