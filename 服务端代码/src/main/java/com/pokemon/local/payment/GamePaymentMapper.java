package com.pokemon.local.payment;

import com.pokemon.local.payment.api.entity.GamePaymentOrder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 游戏角色模拟充值与钻石到账 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:39
 */
@Repository
@DependsOnDatabaseInitialization
public class GamePaymentMapper {
    private static final RowMapper<GamePaymentOrder> ORDER = (row, index) -> new GamePaymentOrder(
            row.getString("id"), row.getString("role_id"), row.getInt("paysid"), row.getLong("amount"),
            row.getLong("reward"), row.getLong("expires_at"), row.getObject("confirmed_at") != null);

    private final JdbcTemplate jdbc;

    /**
     * 初始化游戏订单数据库访问
     *
     * @param jdbc JDBC 操作模板
     */
    public GamePaymentMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 移除老版本只允许正数钻石的约束，保留启动脚本建立的新约束
     */
    @PostConstruct
    void migrateDiamondRewardConstraint() {
        List<String> legacy = jdbc.queryForList("SELECT t.constraint_name "
                        + "FROM information_schema.table_constraints t JOIN information_schema.check_constraints c "
                        + "ON t.constraint_catalog = c.constraint_catalog AND t.constraint_schema = c.constraint_schema "
                        + "AND t.constraint_name = c.constraint_name WHERE t.table_schema = CURRENT_SCHEMA "
                        + "AND t.table_name = 'LOCAL_GAME_PAYMENT_ORDER' AND c.check_clause = ?",
                String.class, "\"REWARD\" > CAST(0 AS BIGINT)");
        if (!legacy.isEmpty()) {
            jdbc.execute("ALTER TABLE local_game_payment_order ADD CONSTRAINT IF NOT EXISTS "
                    + "local_game_payment_reward_valid CHECK (reward >= 0)");
        }
        for (String name : legacy) {
            String quoted = "\"" + name.replace("\"", "\"\"") + "\"";
            jdbc.execute("ALTER TABLE local_game_payment_order DROP CONSTRAINT IF EXISTS " + quoted);
        }
    }

    /**
     * 校验并锁定到账角色，串行化该角色的下单和结算
     *
     * @param accountId 角色所属账户
     * @param roleId 到账角色编号
     * @param realmId 角色所属区服
     * @return 当前钻石余额，未找到或角色归属不符时为空
     */
    public Optional<Long> lockRole(String accountId, String roleId, int realmId) {
        return jdbc.query("SELECT diamonds FROM local_game_role "
                        + "WHERE account_id = ? AND id = ? AND realm_id = ? FOR UPDATE",
                (row, index) -> row.getLong(1), accountId, roleId, realmId).stream().findFirst();
    }

    /**
     * 读取同一角色当前仍可确认的对应档位订单
     *
     * @param roleId 角色编号
     * @param paysid 原游戏充值栏位
     * @param now 当前毫秒时间戳
     * @return 有效订单，找不到时为空
     */
    public Optional<GamePaymentOrder> findPending(String roleId, int paysid, long now) {
        return jdbc.query("SELECT * FROM local_game_payment_order WHERE role_id = ? AND paysid = ? "
                        + "AND confirmed_at IS NULL AND expires_at > ? ORDER BY created_at DESC LIMIT 1",
                ORDER, roleId, paysid, now).stream().findFirst();
    }

    /**
     * 读取已通过账号授权、角色校验的订单
     *
     * @param roleId 到账角色编号
     * @param orderId 服务端订单编号
     * @return 匹配的订单，找不到时为空
     */
    public Optional<GamePaymentOrder> find(String roleId, String orderId) {
        return jdbc.query("SELECT * FROM local_game_payment_order WHERE role_id = ? AND id = ?",
                ORDER, roleId, orderId).stream().findFirst();
    }

    /**
     * 查询一个档位此前是否已完成充值，用于首购额外钻石
     *
     * @param roleId 到账角色编号
     * @param paysid 原游戏充值栏位
     * @return 该角色已完成的同档位订单数
     */
    public long countConfirmed(String roleId, int paysid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_game_payment_order "
                + "WHERE role_id = ? AND paysid = ? AND confirmed_at IS NOT NULL",
                Long.class, roleId, paysid);
    }

    /**
     * 查询活动商品在本轮已经确认的时间。
     * @param roleId 当前角色编号
     * @param sku 原充值商品档位
     * @param start 活动开始毫秒时间
     * @param end 活动结束毫秒时间
     * @return 已确认的毫秒时间列表
     */
    List<Long> confirmationTimes(String roleId, int sku, long start, long end) {
        return jdbc.queryForList("SELECT confirmed_at FROM local_game_payment_order "
                + "WHERE role_id=? AND paysid=? AND confirmed_at>=? AND confirmed_at<? ORDER BY confirmed_at",
                Long.class, roleId, sku, start, end);
    }

    /**
     * 保存待付订单与档位快照
     *
     * @param order 待确认订单
     * @param now 当前毫秒时间戳
     * @return 插入行数
     */
    public int insert(GamePaymentOrder order, long now) {
        return jdbc.update("INSERT INTO local_game_payment_order "
                        + "(id, role_id, paysid, amount, reward, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                order.id, order.roleId, order.paysid, order.amount, order.reward, now, order.expiresAt);
    }

    /**
     * 写入当前尚未结算的订单确认时间
     *
     * @param orderId 服务端订单编号
     * @param now 当前毫秒时间戳
     * @return 更新行数
     */
    public int confirm(String orderId, long now) {
        return jdbc.update("UPDATE local_game_payment_order SET confirmed_at = ? "
                + "WHERE id = ? AND confirmed_at IS NULL", now, orderId);
    }

    /**
     * 读取将要同步到账的最新角色余额
     *
     * @param roleId 到账角色编号
     * @return 当前角色钻石数量
     */
    public long diamonds(String roleId) {
        return jdbc.queryForObject("SELECT diamonds FROM local_game_role WHERE id = ?", Long.class, roleId);
    }

    /**
     * 汇总已确认的本地游戏订单金额，供 VIP 与充值活动独立使用
     *
     * @param roleId 当前角色编号
     * @return 已结算金额，单位分
     */
    long confirmedAmount(String roleId) {
        Long amount = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM local_recharge_activity_order "
                        + "WHERE role_id = ? AND confirmed_at IS NOT NULL", Long.class, roleId);
        return amount == null ? 0 : amount;
    }

    /**
     * 查询不计充值活动的道具 VIP 经验，计活动券已由充值视图计入。
     * @param roleId 角色编号
     * @return 道具直接增加的 VIP 经验
     */
    long itemVipExperience(String roleId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(vip_experience),0) FROM local_item_vip_grant "
                + "WHERE role_id=? AND item_id<>31008", Long.class, roleId);
    }

    /**
     * 判断当前经典首充周期是否有实际确认的本地游戏订单
     *
     * @param roleId 当前角色编号
     * @param start 周期起始毫秒
     * @param end 周期截止毫秒
     * @return 是否存在有效订单
     */
    boolean paidInPeriod(String roleId, long start, long end) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM local_recharge_activity_order "
                        + "WHERE role_id = ? AND confirmed_at >= ? AND confirmed_at < ?)",
                Boolean.class, roleId, start, end));
    }

    /**
     * 读取经典首充礼包的领取记录
     *
     * @param roleId 当前角色编号
     * @param start 周期起点 Unix 秒
     * @return 是否已经领取
     */
    boolean classicClaimed(String roleId, long start) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM local_classic_first_charge_claim "
                + "WHERE role_id = ? AND period_start = ?)", Boolean.class, roleId, start));
    }

    /**
     * 在同一角色锁和发放事务中记录经典首充领取
     *
     * @param roleId 当前角色编号
     * @param start 周期起点 Unix 秒
     */
    void claimClassic(String roleId, long start) {
        jdbc.update("INSERT INTO local_classic_first_charge_claim(role_id, period_start) VALUES (?, ?)", roleId, start);
    }

    /**
     * 读取角色各个商品档位的累计结算次数
     *
     * @param roleId 角色编号
     * @return 原 Sproto item 所读的 id、n 数据
     */
    public List<Map<String, Object>> counts(String roleId) {
        return jdbc.query("SELECT paysid, COUNT(*) AS purchase_count FROM local_game_payment_order "
                        + "WHERE role_id = ? AND confirmed_at IS NOT NULL GROUP BY paysid ORDER BY paysid",
                (row, index) -> {
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("id", row.getInt("paysid"));
                    item.put("n", row.getLong("purchase_count"));
                    return item;
                }, roleId);
    }

    /**
     * 读取确认后还未写入游戏连接的订单通知
     *
     * @param roleId 到账角色编号
     * @return 该角色最早的至多 50 笔待通知订单
     */
    public List<GamePaymentOrder> unnotified(String roleId) {
        return jdbc.query("SELECT * FROM local_game_payment_order WHERE role_id = ? "
                        + "AND confirmed_at IS NOT NULL AND notified_at IS NULL ORDER BY confirmed_at, id LIMIT 50",
                ORDER, roleId);
    }

    /**
     * 记录已发送到账通知，避免后续心跳再次弹出
     *
     * @param roleId 到账角色编号
     * @param orderId 服务端订单编号
     * @param now 当前毫秒时间戳
     * @return 更新行数
     */
    public int notified(String roleId, String orderId, long now) {
        return jdbc.update("UPDATE local_game_payment_order SET notified_at = ? "
                + "WHERE role_id = ? AND id = ? AND confirmed_at IS NOT NULL AND notified_at IS NULL",
                now, roleId, orderId);
    }
}
