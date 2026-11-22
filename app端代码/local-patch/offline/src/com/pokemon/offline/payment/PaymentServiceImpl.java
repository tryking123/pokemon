package com.pokemon.offline.payment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.pokemon.offline.account.AccountService;
import com.pokemon.offline.common.ApiException;
import com.pokemon.offline.payment.api.entity.LocalPaymentOrder;
import com.pokemon.offline.payment.api.vo.CreateOrderVO;
import com.pokemon.offline.platform.beans.factory.annotation.Value;
import com.pokemon.offline.platform.http.HttpStatus;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 本地模拟充值 ServiceImpl
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:04
 */
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Pattern PRODUCT_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern CLIENT_ORDER_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    private final AccountService accounts;
    private final PaymentMapper payments;
    private final Map<String, Product> catalog = new LinkedHashMap<>();

    /**
     * 初始化充值业务及配置中的商品档位
     *
     * @param accounts 本地账号服务
     * @param payments 充值订单和资产 Mapper
     * @param configuredProducts 用逗号分隔的商品编号:价格分:钻石数量
     */
    public PaymentServiceImpl(AccountService accounts, PaymentMapper payments,
                              @Value("${pokemon.products}") String configuredProducts) {
        this.accounts = accounts;
        this.payments = payments;
        for (String entry : configuredProducts.split(",", -1)) {
            String[] fields = entry.trim().split(":", -1);
            if (fields.length != 3 || !PRODUCT_ID.matcher(fields[0]).matches()) {
                throw new IllegalArgumentException("商品档位格式应为：商品编号:价格分:钻石数量");
            }
            long amount = Long.parseLong(fields[1]);
            long reward = Long.parseLong(fields[2]);
            if (amount <= 0 || reward <= 0 || catalog.containsKey(fields[0])) {
                throw new IllegalArgumentException("商品编号必须唯一，价格及钻石数量必须为正数");
            }
            catalog.put(fields[0], new Product(amount, reward));
        }
    }

    /**
     * 返回本地商品档位和服务端配置的价格及奖励
     *
     * @return 商品档位列表
     */
    @Override
    public Object products() {
        List<Map<String, Object>> result = new ArrayList<>();
        catalog.forEach((id, product) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("productId", id);
            view.put("amount", product.amount);
            view.put("reward", product.reward);
            result.add(view);
        });
        return result;
    }

    /**
     * 按客户端订单编号幂等创建充值订单
     *
     * @param authorization HTTP 授权请求头
     * @param vo 模拟充值下单信息
     * @return 当前订单状态和账号余额
     */
    @Override
    @Transactional
    public Object createOrder(String authorization, CreateOrderVO vo) {
        String accountId = accounts.requireAccount(authorization).id;
        if (vo.productId == null || !PRODUCT_ID.matcher(vo.productId).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_ID", "商品编号无效");
        }
        if (vo.clientOrderId == null || !CLIENT_ORDER_ID.matcher(vo.clientOrderId).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CLIENT_ORDER_ID", "客户端订单编号无效");
        }

        long balance = payments.lockBalance(accountId);
        LocalPaymentOrder existing = payments.findByClientOrderId(accountId, vo.clientOrderId);
        if (existing != null) {
            if (!existing.productId.equals(vo.productId)) {
                throw new ApiException(HttpStatus.CONFLICT, "ORDER_CONFLICT", "该客户端订单编号已用于其他商品");
            }
            return orderView(existing, existing.confirmed, balance);
        }

        Product product = catalog.get(vo.productId);
        if (product == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "模拟充值商品不存在");
        }
        LocalPaymentOrder order = new LocalPaymentOrder(UUID.randomUUID().toString(),
                vo.productId, product.amount, product.reward, false);
        payments.insertOrder(accountId, vo.clientOrderId, order, System.currentTimeMillis());
        return orderView(order, false, balance);
    }

    /**
     * 在同一事务内确认订单和发放钻石，重复确认只查询当前状态
     *
     * @param authorization HTTP 授权请求头
     * @param orderId 服务端订单编号
     * @return 当前订单状态和账号余额
     */
    @Override
    @Transactional
    public Object confirmOrder(String authorization, String orderId) {
        String accountId = accounts.requireAccount(authorization).id;
        long balance = payments.lockBalance(accountId);
        LocalPaymentOrder order = payments.findById(accountId, orderId);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "模拟充值订单不存在");
        }
        if (order.confirmed) {
            return orderView(order, true, balance);
        }
        if (balance > Long.MAX_VALUE - order.reward) {
            throw new ApiException(HttpStatus.CONFLICT, "BALANCE_LIMIT", "模拟钻石余额达到上限");
        }
        long updatedBalance = balance + order.reward;
        if (payments.confirmOrder(accountId, orderId, System.currentTimeMillis()) != 1
                || payments.updateBalance(accountId, updatedBalance) != 1) {
            throw new IllegalStateException("模拟充值订单结算未完成");
        }
        return orderView(order, true, updatedBalance);
    }

    /**
     * 将订单记录转换为接口响应
     *
     * @param order 充值订单
     * @param confirmed 是否已结算
     * @param balance 当前账号余额
     * @return 订单状态、下单快照和账号余额
     */
    private Map<String, Object> orderView(LocalPaymentOrder order, boolean confirmed, long balance) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.id);
        result.put("status", confirmed ? "CONFIRMED" : "PENDING");
        result.put("productId", order.productId);
        result.put("amount", order.amount);
        result.put("reward", order.reward);
        result.put("balance", balance);
        return result;
    }

    /**
     * 服务端配置的模拟充值商品
     *
     * @author tongruiyuan
     * @since 2026-11-18 17:04
     */
    private static class Product {

        /** 模拟价格，单位为分 */
        private final long amount;

        /** 模拟充值发放的钻石数量 */
        private final long reward;

        /**
         * 初始化商品价格及奖励
         *
         * @param amount 模拟价格，单位为分
         * @param reward 模拟钻石数量
         */
        private Product(long amount, long reward) {
            this.amount = amount;
            this.reward = reward;
        }
    }
}
