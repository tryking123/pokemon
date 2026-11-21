package com.pokemon.local.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.ActivityService;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.common.ApiException;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 两版限时商城的货架、付费待领权益与实物结算。
 * @author tongruiyuan
 * @since 2026-11-19 21:19
 */
@Service
public class TimedPaymentShopService {
    private final JdbcTemplate jdbc;
    private final ActivityService calendar;
    private final RoleService roles;
    private final InventoryService inventory;
    private final Map<Integer, JsonNode> offers = new LinkedHashMap<>();

    /**
     * 从随包目录读取两个商城互不重复的原商品。
     * @param jdbc 订单和领取账本
     * @param calendar 活动周历
     * @param roles 角色锁
     * @param inventory 发货和满包邮件
     * @param json 原始配置解析器
     * @throws IOException 商品目录读取失败
     */
    TimedPaymentShopService(JdbcTemplate jdbc, ActivityService calendar, RoleService roles,
                            InventoryService inventory, ObjectMapper json) throws IOException {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.roles = roles;
        this.inventory = inventory;
        try (InputStream input = new ClassPathResource("payment/timed-shop.json").getInputStream()) {
            for (JsonNode offer : json.readTree(input)) {
                offers.put(offer.get("sid").asInt(), offer);
            }
        }
    }

    /**
     * 判断订单是否在模拟支付后单独进入原商城领取。
     * @param sku 充值商品编号
     * @return 是否为限时商城货架商品
     */
    boolean supports(int sku) {
        return offers.containsKey(sku);
    }

    /**
     * 查询已确认订单在商城的实际领货状态。
     * @param orderId 已确认的订单编号
     * @return 已发货时为真
     */
    boolean collected(String orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_timed_shop_claim WHERE order_id=?",
                Integer.class, orderId) > 0;
    }

    /**
     * 填充原版页面所需的售价、限购、计数、可领标记及道具。
     * @param roleId 角色编号
     * @param seasonal 是否为第二版页面
     * @return 本页面商品列表
     */
    @Transactional
    public List<Map<String,Object>> panel(String roleId, boolean seasonal) {
        roles.lockRole(roleId);
        List<Map<String,Object>> list = new ArrayList<>();
        for (JsonNode row : offers.values()) {
            int sku = row.get("sid").asInt();
            if (row.get("seasonal").asBoolean() != seasonal) {
                continue;
            }
            ActivityPeriod period = calendar.cycle(seasonal ? 10082 : 10067);
            boolean pending = awaiting(roleId, sku) != null;
            if (System.currentTimeMillis() < period.start * 1000 && !pending) {
                continue;
            }
            int limit = row.get("limit").asInt();
            int count = bought(roleId, sku, period);
            Map<String,Object> line = new LinkedHashMap<>();
            line.put("sid", sku);
            line.put("limit", limit);
            line.put("count", count);
            line.put("flag", pending);
            line.put("status", count >= limit && !pending ? 4 : 1);
            line.put("price", Long.toString(GamePaymentCatalog.require(sku).amount / 100));
            line.put("chongzhi", row.get("chongzhi").asInt());
            line.put("endtime", period.end);
            line.put("finaltime", period.showUntil);
            List<Integer> award = new ArrayList<>();
            row.get("rew").forEach(value -> award.add(value.asInt()));
            line.put("rew", award);
            list.add(line);
        }
        return list;
    }

    /**
     * 校验购买次数及未领状态，避免同一商品重复收费。
     * @param roleId 角色编号
     * @param sku 充值商品
     * @param now 当前毫秒
     * @return 最迟可确认时间，其他商品为不限
     */
    long purchaseDeadline(String roleId, int sku, long now) {
        JsonNode row = offers.get(sku);
        if (row == null) {
            return Long.MAX_VALUE;
        }
        ActivityPeriod period = calendar.cycleAt(row.get("seasonal").asBoolean() ? 10082 : 10067, now / 1000);
        if (now < period.start * 1000 || now >= period.end * 1000 || bought(roleId, sku, period) >= row.get("limit").asInt()
                || awaiting(roleId, sku) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "LIMITED_SHOP_UNAVAILABLE", "本档已达限购或有订单待领取");
        }
        return period.end * 1000;
    }

    /**
     * 对已确认的单笔订单按对应旧页或新版页请求领取一次。
     * @param roleId 角色编号
     * @param seasonal 是否为新版货架
     * @param sku 实际原页面传入的商品号
     * @param requestKey 连接与请求序号回执
     * @return 原协议错误码及直接入包的物品附件
     */
    @Transactional
    public Map<String,Object> claim(String roleId, boolean seasonal, int sku, String requestKey) {
        roles.lockRole(roleId);
        JsonNode row = offers.get(sku);
        if (row == null || row.get("seasonal").asBoolean() != seasonal) {
            return Collections.singletonMap("errno", 100021);
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM local_timed_shop_claim WHERE role_id=? AND request_key=?",
                Integer.class, roleId, requestKey) > 0) {
            return Collections.singletonMap("errno", 100011);
        }
        String order = awaiting(roleId, sku);
        if (order == null) {
            return Collections.singletonMap("errno", 100012);
        }
        Map<Integer,Long> goods = new LinkedHashMap<>();
        JsonNode award = row.get("rew");
        for (int index = 0; index < award.size(); index += 3) {
            if (award.get(index).asInt() != 1) {
                throw new IllegalStateException("限时商城包含未配置的资产类型");
            }
            goods.merge(award.get(index + 1).asInt(), award.get(index + 2).asLong(), Long::sum);
        }
        List<Map<String,Object>> items = new ArrayList<>();
        inventory.grantItemsOrMail(roleId, "timed-shop:" + order, goods).forEach((id, count) -> {
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("n", count);
            item.put("type", inventory.itemType(id));
            items.add(item);
        });
        jdbc.update("INSERT INTO local_timed_shop_claim(order_id,role_id,request_key) VALUES(?,?,?)",
                order, roleId, requestKey);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("errno", 0);
        result.put("items", items);
        return result;
    }

    /**
     * 统计本轮实际确认的订单数。
     * @param roleId 角色编号
     * @param sku 充值商品
     * @param period 商品所在轮次
     * @return 本轮确认单数
     */
    private int bought(String roleId, int sku, ActivityPeriod period) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_game_payment_order WHERE role_id=? AND paysid=? "
                + "AND confirmed_at>=? AND confirmed_at<?", Integer.class, roleId, sku,
                period.start * 1000, period.end * 1000);
    }

    /**
     * 查询仍未领取的最早已付订单，跨周或重启后保留。
     * @param roleId 角色编号
     * @param sku 充值商品
     * @return 待领订单号，无订单时为空
     */
    private String awaiting(String roleId, int sku) {
        List<String> orders = jdbc.queryForList("SELECT o.id FROM local_game_payment_order o "
                + "LEFT JOIN local_timed_shop_claim c ON o.id=c.order_id WHERE o.role_id=? AND o.paysid=? "
                + "AND o.confirmed_at IS NOT NULL AND c.order_id IS NULL ORDER BY o.confirmed_at,o.id LIMIT 1",
                String.class, roleId, sku);
        return orders.isEmpty() ? null : orders.get(0);
    }
}
