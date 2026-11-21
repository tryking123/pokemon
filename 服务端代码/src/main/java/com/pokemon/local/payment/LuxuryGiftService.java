package com.pokemon.local.payment;

import com.pokemon.local.activity.ActivityService;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.common.ApiException;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宝可梦节日直购礼包权益、历史已发订单兼容及手动领取。
 * @author tongruiyuan
 * @since 2026-11-19 21:38
 */
@Service
public class LuxuryGiftService {
    private static final List<Integer> SKUS = Arrays.asList(501, 503, 504);
    private static final int LIMIT = 6;
    private final JdbcTemplate jdbc;
    private final ActivityService calendar;
    private final RoleService roles;
    private final InventoryService inventory;

    /**
     * 绑定支付流水和发奖所需的实际角色资产服务。
     * @param jdbc 订单和权益访问模板
     * @param calendar 活动周期
     * @param roles 角色锁及钱包
     * @param inventory 道具、家园币与精灵碎片
     */
    LuxuryGiftService(JdbcTemplate jdbc, ActivityService calendar, RoleService roles, InventoryService inventory) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.roles = roles;
        this.inventory = inventory;
    }

    /**
     * 判断是否由宝可梦节日使用原 C289 领货。
     * @param sku 原充值商品
     * @return 是否为原五百号段已配置的商品
     */
    public static boolean supports(int sku) {
        return SKUS.contains(sku);
    }

    /**
     * 获取指定时间所在轮次的结束时间，用于已登录连接跨周刷新。
     * @param now 当前时间戳，单位为秒
     * @return 下轮开始时间戳，单位为秒
     */
    public long periodEnd(long now) {
        return calendar.cycleAt(10054, now).end;
    }

    /**
     * 为新建订单保存分离支付与领取的标记；既有订单沿用原发货方式。
     * @param orderId 唯一订单编号
     */
    void reserve(String orderId) {
        jdbc.update("INSERT INTO local_luxury_gift_order(order_id) VALUES(?)", orderId);
    }

    /**
     * 判断本笔订单从创建起是否约定独立领取。
     * @param orderId 订单编号
     * @return 是否为新版手动领取订单
     */
    boolean reserved(String orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_luxury_gift_order WHERE order_id=?", Integer.class, orderId) > 0;
    }

    /**
     * 查询此笔手领订单是否发货完成。
     * @param orderId 订单编号
     * @return 已发放时为真
     */
    boolean collected(String orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_luxury_gift_order WHERE order_id=? AND receipt IS NOT NULL",
                Integer.class, orderId) > 0;
    }

    /**
     * 在角色锁内按原六次限购检查新的购买。
     * @param roleId 角色编号
     * @param sku 原充值商品
     * @param now 当前毫秒
     * @return 本轮订单最晚支付时刻
     */
    long deadline(String roleId, int sku, long now) {
        if (!supports(sku)) {
            return Long.MAX_VALUE;
        }
        ActivityPeriod period = calendar.cycleAt(10054, now / 1000);
        if (now < period.start * 1000 || bought(roleId, sku, period) >= LIMIT || pending(roleId, sku) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "LUXURY_GIFT_UNAVAILABLE", "礼包未开放、次数已满或上笔待领取");
        }
        return period.end * 1000;
    }

    /**
     * 恢复 S451 所需原档的已买与已领数，保留老版本即时到账订单。
     * @param roleId 角色编号
     * @return 按原商品号索引的礼包状态
     */
    @Transactional
    public List<Map<String,Object>> records(String roleId) {
        roles.lockRole(roleId);
        ActivityPeriod period = calendar.cycle(10054);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (int sku : SKUS) {
            int buy = bought(roleId, sku, period);
            String pending = pending(roleId, sku);
            if (pending != null && jdbc.queryForObject("SELECT confirmed_at FROM local_game_payment_order WHERE id=?",
                    Long.class, pending) < period.start * 1000) {
                buy++;
            }
            Map<String,Object> entry = new LinkedHashMap<>();
            entry.put("index", sku);
            entry.put("buy", buy);
            entry.put("get", buy - (pending == null ? 0 : 1));
            rows.add(entry);
        }
        return rows;
    }

    /**
     * 领取一笔本角色已确认的节日礼包，并在同一事务内保存回执。
     * @param roleId 角色编号
     * @param sku 原页面商品号
     * @param receipt 原连接请求回执
     * @return 原错误码和已直接发放的资产
     */
    @Transactional
    public Map<String,Object> claim(String roleId, int sku, String receipt) {
        roles.lockRole(roleId);
        if (!supports(sku)) {
            return Collections.singletonMap("errno", 100021);
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM local_luxury_gift_order WHERE receipt=?", Integer.class, receipt) > 0) {
            return Collections.singletonMap("errno", 100011);
        }
        String order = pending(roleId, sku);
        if (order == null) {
            return Collections.singletonMap("errno", 100012);
        }
        GamePaymentCatalog.Product product = GamePaymentCatalog.require(sku);
        Map<String,Object> adds = new LinkedHashMap<>();
        if (product.diamonds > 0) {
            roles.addCurrency(roleId, 0, product.diamonds, 0);
            adds.put("diamonds", product.diamonds);
        }
        if (product.homeCoins > 0) {
            inventory.grantHomeCoins(roleId, product.homeCoins);
            adds.put("homecoin", product.homeCoins);
        }
        List<Map<String,Object>> chips = new ArrayList<>();
        product.chips.forEach((id,n) -> {
            inventory.grantChip(roleId, id, n);
            chips.add(attachment(id,n));
        });
        adds.put("elschips", chips);
        Map<Integer,Long> goods = new LinkedHashMap<>();
        product.items.forEach((id,n) -> goods.put(id, n.longValue()));
        List<Map<String,Object>> items = new ArrayList<>();
        inventory.grantItemsOrMail(roleId, "luxury:" + order, goods).forEach((id,n) -> {
            Map<String,Object> item = attachment(id, n);
            item.put("type", inventory.itemType(id));
            items.add(item);
        });
        adds.put("items", items);
        jdbc.update("UPDATE local_luxury_gift_order SET receipt=? WHERE order_id=?", receipt, order);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("errno", 0);
        result.put("adds", adds);
        return result;
    }

    /**
     * 构造原附件中的模板编号与数量。
     * @param id 原模板号
     * @param count 发放数量
     * @return 物品或碎片描述
     */
    private Map<String,Object> attachment(int id, long count) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("n", count);
        return row;
    }

    /**
     * 查询本角色指定商品的待领订单。
     * @param roleId 角色编号
     * @param sku 原充值商品
     * @return 已付款仍未领的订单编号
     */
    private String pending(String roleId, int sku) {
        List<String> results = jdbc.queryForList("SELECT o.id FROM local_game_payment_order o JOIN local_luxury_gift_order g "
                + "ON o.id=g.order_id WHERE o.role_id=? AND o.paysid=? AND o.confirmed_at IS NOT NULL "
                + "AND g.receipt IS NULL ORDER BY o.confirmed_at,o.id LIMIT 1", String.class, roleId, sku);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 统计本轮新旧版本合计的已购数。
     * @param roleId 角色编号
     * @param sku 原充值商品
     * @param period 所属活动轮次
     * @return 本轮确认的单数
     */
    private int bought(String roleId, int sku, ActivityPeriod period) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_game_payment_order WHERE role_id=? AND paysid=? "
                + "AND confirmed_at>=? AND confirmed_at<?", Integer.class, roleId, sku, period.start*1000, period.end*1000);
    }
}
