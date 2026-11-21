package com.pokemon.offline.payment;

import java.util.List;

import com.pokemon.offline.payment.api.entity.LocalPaymentOrder;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.core.RowMapper;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 本地模拟充值订单与资产 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:04
 */
@Repository
public class PaymentMapper {

    private static final RowMapper<LocalPaymentOrder> ORDER = (row, index) -> new LocalPaymentOrder(
            row.getString("id"), row.getString("product_id"), row.getLong("amount"),
            row.getLong("reward"), row.getObject("confirmed_at") != null);

    private final JdbcTemplate jdbc;

    /**
     * 初始化充值 Mapper
     *
     * @param jdbc JDBC 操作模板
     */
    public PaymentMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 锁定账号的资产记录，为同一账号的下单和确认串行化
     *
     * @param accountId 账号主键
     * @return 当前模拟钻石余额
     */
    public long lockBalance(String accountId) {
        return jdbc.queryForObject("SELECT balance FROM local_account WHERE id = ? FOR UPDATE",
                Long.class, accountId);
    }

    /**
     * 按客户端订单编号查找该账号的订单
     *
     * @param accountId 账号主键
     * @param clientOrderId 客户端订单编号
     * @return 匹配订单，未找到时返回空
     */
    public LocalPaymentOrder findByClientOrderId(String accountId, String clientOrderId) {
        List<LocalPaymentOrder> rows = jdbc.query(
                "SELECT * FROM local_payment_order WHERE account_id = ? AND client_order_id = ?",
                ORDER, accountId, clientOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按服务端订单编号查找该账号的订单
     *
     * @param accountId 账号主键
     * @param orderId 服务端订单编号
     * @return 匹配订单，未找到时返回空
     */
    public LocalPaymentOrder findById(String accountId, String orderId) {
        List<LocalPaymentOrder> rows = jdbc.query(
                "SELECT * FROM local_payment_order WHERE account_id = ? AND id = ?", ORDER, accountId, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 保存待确认订单及下单时的价格和奖励
     *
     * @param accountId 账号主键
     * @param clientOrderId 客户端订单编号
     * @param order 待确认订单
     * @param now 下单时间戳，单位毫秒
     * @return 插入行数
     */
    public int insertOrder(String accountId, String clientOrderId, LocalPaymentOrder order, long now) {
        return jdbc.update("INSERT INTO local_payment_order "
                + "(id, account_id, client_order_id, product_id, amount, reward, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                order.id, accountId, clientOrderId, order.productId, order.amount, order.reward, now);
    }

    /**
     * 写入待确认订单的结算时间
     *
     * @param accountId 账号主键
     * @param orderId 服务端订单编号
     * @param now 结算时间戳，单位毫秒
     * @return 更新行数
     */
    public int confirmOrder(String accountId, String orderId, long now) {
        return jdbc.update("UPDATE local_payment_order SET confirmed_at = ? "
                + "WHERE account_id = ? AND id = ? AND confirmed_at IS NULL", now, accountId, orderId);
    }

    /**
     * 写入账号的模拟钻石余额
     *
     * @param accountId 账号主键
     * @param balance 更新后的模拟钻石余额
     * @return 更新行数
     */
    public int updateBalance(String accountId, long balance) {
        return jdbc.update("UPDATE local_account SET balance = ? WHERE id = ?", balance, accountId);
    }
}
