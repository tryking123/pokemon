package com.pokemon.local.payment;

import com.pokemon.local.activity.ActivityService;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.common.ApiException;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 原直购和连续充值页面使用的本地限购商品、展示和发货规则。
 *
 * @author tongruiyuan
 * @since 2026-11-19 21:12
 */
@Service
public class WeeklyGiftService {
    private static final int DIRECT = 301;
    private static final int DAILY = 302;
    private static final long DAY = 86400L;
    private static final int[] DIRECT_ITEMS = {21063, 1, 20027, 1};
    private static final int[][] DAILY_ITEMS = {
            {34076, 1, 20027, 2}, {34076, 1, 21063, 1}, {34076, 1, 21068, 1},
            {34076, 1, 20027, 3}, {34076, 1, 21063, 2}, {34076, 1, 21068, 2},
            {34076, 1, 21063, 2, 21068, 2}
    };
    private final ActivityService calendar;
    private final RoleService roles;
    private final GamePaymentMapper payments;
    private final InventoryService inventory;

    /**
     * 绑定活动周期、真实订单及角色资产。
     * @param calendar 统一活动日历
     * @param roles 角色等级和归属
     * @param payments 支付订单查询
     * @param inventory 道具和系统邮件
     */
    public WeeklyGiftService(ActivityService calendar, RoleService roles, GamePaymentMapper payments,
                             InventoryService inventory) {
        this.calendar = calendar;
        this.roles = roles;
        this.payments = payments;
        this.inventory = inventory;
    }

    /**
     * 判断商品是否使用本活动的限购和奖励。
     * @param sku 原充值商品号
     * @return 是否属于每周直购或逐日礼包
     */
    public static boolean supports(int sku) {
        return sku == DIRECT || sku == DAILY;
    }

    /**
     * 查询原运营活动面板，已错过且未购买的历史日不会显示为已购买。
     * @param role 当前角色
     * @param direct 是否为每周直购
     * @return 原客户端的面板数据
     */
    public Map<String, Object> panel(LocalRole role, boolean direct) {
        int sku = direct ? DIRECT : DAILY;
        long now = System.currentTimeMillis();
        ActivityPeriod period = period(sku, now);
        Map<String, Object> result = new LinkedHashMap<>();
        if (role.level < 30 || now < period.start * 1000 || now >= period.end * 1000) {
            result.put("errno", 1);
            return result;
        }
        Set<Integer> bought = purchasedDays(role.id, sku, period);
        result.put("errno", 0);
        if (direct) {
            result.put("rew", display(DIRECT_ITEMS));
            result.put("paysid", sku);
            result.put("flag", bought.isEmpty() ? 0 : 1);
        } else {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 1; i <= DAILY_ITEMS.length; i++) {
                if (i < period.day && !bought.contains(i)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", i);
                row.put("paysid", sku);
                row.put("rew", display(DAILY_ITEMS[i - 1]));
                rows.add(row);
            }
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("list", rows);
            info.put("show", display(DAILY_ITEMS[6]));
            result.put("info", info);
            result.put("days", period.day);
            result.put("flag", bought.contains(period.day));
        }
        return result;
    }

    /**
     * 在角色锁内校验本次购买资格，返回本订单最迟可确认的时间。
     * @param roleId 购买角色
     * @param sku 原商品号
     * @param now 当前毫秒时间
     * @return 此档权益窗口结束的毫秒时间
     */
    long purchaseDeadline(String roleId, int sku, long now) {
        if (!supports(sku)) {
            return Long.MAX_VALUE;
        }
        ActivityPeriod period = period(sku, now);
        if (!roles.findById(roleId).map(role -> role.level >= 30).orElse(false)
                || now < period.start * 1000 || now >= period.end * 1000) {
            throw new ApiException(HttpStatus.CONFLICT, "GIFT_UNAVAILABLE", "礼包当前未开放");
        }
        Set<Integer> bought = purchasedDays(roleId, sku, period);
        if (sku == DIRECT ? !bought.isEmpty() : bought.contains(period.day)) {
            throw new ApiException(HttpStatus.CONFLICT, "GIFT_ALREADY_BOUGHT", "本档礼包已经购买");
        }
        return (sku == DIRECT ? period.end : period.start + period.day * DAY) * 1000;
    }

    /**
     * 在订单确认的同一事务内按资格所属日期发货。
     * @param roleId 收货角色
     * @param sku 活动商品号
     * @param orderId 已确认的唯一订单号
     * @param now 本次结算时间，单位毫秒
     */
    void deliver(String roleId, int sku, String orderId, long now) {
        inventory.grantItemsOrMail(roleId, "weekly-gift:" + orderId, itemsFor(sku, now));
    }

    /**
     * 读取本笔订单有效日的实际礼包道具，供原生确认框和结算共用。
     * @param sku 原活动商品号
     * @param time 订单有效时段内的一刻，单位毫秒
     * @return 对应原道具及发放数量
     */
    Map<Integer, Long> itemsFor(int sku, long time) {
        int[] items = sku == DIRECT ? DIRECT_ITEMS : DAILY_ITEMS[period(sku, time).day - 1];
        Map<Integer, Long> rewards = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i += 2) {
            rewards.put(items[i], (long) items[i + 1]);
        }
        return rewards;
    }

    /**
     * 选取对应商品的七日活动窗口。
     * @param sku 原商品号
     * @param now 当前毫秒时间
     * @return 统一活动周期
     */
    private ActivityPeriod period(int sku, long now) {
        return calendar.cycleAt(sku == DIRECT ? 10080 : 10079, now / 1000);
    }

    /**
     * 从本轮确认流水恢复已购日。
     * @param roleId 角色编号
     * @param sku 原商品号
     * @param period 所属周期
     * @return 已成功购买的一基日序
     */
    private Set<Integer> purchasedDays(String roleId, int sku, ActivityPeriod period) {
        Set<Integer> days = new HashSet<>();
        for (long time : payments.confirmationTimes(roleId, sku, period.start * 1000, period.end * 1000)) {
            days.add((int) ((time / 1000 - period.start) / DAY) + 1);
        }
        return days;
    }

    /**
     * 转为原 UI 使用的类型、编号和数量三元组。
     * @param items 原物品及数量对
     * @return 顺序与发货相同的原奖励列表
     */
    private List<Integer> display(int[] items) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < items.length; i += 2) {
            result.add(1);
            result.add(items[i]);
            result.add(items[i + 1]);
        }
        return result;
    }
}
