package com.pokemon.offline.payment;

import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.payment.api.entity.FirstRechargeState;
import com.pokemon.offline.payment.api.entity.GamePaymentOrder;
import com.pokemon.offline.role.RoleService;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 新手充值活动 Service，根据当前周期内已经确认的角色模拟订单发放返利
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:25
 */
@Service
public class FirstRechargeService {
    public static final int FIRST_SLOT = 8;
    public static final int LAST_SLOT = 11;
    public static final int SHOW_ELF = 64073;
    public static final int LOCKED = 0;
    public static final int CLAIMABLE = 1;
    public static final int CLAIMED = 2;
    private static final int ACTIVITY_ID = 10052;
    private static final int DIAMOND_ITEM_ID = 1002;
    // 底包 xinshouchong_proto 的 rew1 和各档共用的 rew2。
    private static final long[] DIAMOND_REBATES = {300000, 630000, 1050000, 2100000};
    private static final List<Integer> ITEMS = Collections.unmodifiableList(Arrays.asList(
            1, 34522, 1, 1, 21025, 20, 1, 34031, 20, 1, 20003, 500, 1, 31022, 20));
    private final ActivityService activities;
    private final FirstRechargeMapper mapper;
    private final RoleService roles;
    private final InventoryService inventory;

    /**
     * 绑定活动日历、模拟订单、角色与资产服务
     *
     * @param activities 运营活动日历
     * @param mapper 模拟订单与领取记录
     * @param roles 本地角色事务锁
     * @param inventory 角色钻石和背包发放
     */
    FirstRechargeService(ActivityService activities, FirstRechargeMapper mapper, RoleService roles,
                         InventoryService inventory) {
        this.activities = activities;
        this.mapper = mapper;
        this.roles = roles;
        this.inventory = inventory;
    }

    /**
     * 查询指定原充值档位的返利钻石
     *
     * @param paysid 原新手充值活动的档位编号
     * @return 原始钻石数
     */
    public long diamondRebate(int paysid) {
        return DIAMOND_REBATES[paysid - FIRST_SLOT];
    }

    /**
     * 查询各档共用的原始道具奖励
     *
     * @return 类型、道具编号和数量的三元组序列
     */
    public List<Integer> items() {
        return ITEMS;
    }

    /**
     * 查询角色本期的返利资格
     *
     * @param roleId 当前认证角色
     * @return 当前轮次及对应档位状态
     */
    public FirstRechargeState snapshot(String roleId) {
        ActivityPeriod period = activities.cycle(ACTIVITY_ID);
        if (Instant.now().getEpochSecond() < period.start) {
            return new FirstRechargeState(period.start, period.start, 0, LOCKED);
        }
        Optional<Integer> claimed = mapper.claimed(roleId, period.start);
        if (claimed.isPresent()) {
            return new FirstRechargeState(period.start, period.end, claimed.get(), CLAIMED);
        }
        Optional<GamePaymentOrder> paid = paid(roleId, period);
        return paid.map(order -> new FirstRechargeState(period.start, period.end, order.paysid, CLAIMABLE))
                .orElseGet(() -> new FirstRechargeState(period.start, period.end, 0, LOCKED));
    }

    /**
     * 在同一角色事务内发放一次返利钻石和礼包道具
     *
     * @param roleId 当前认证角色
     * @param expectedPeriod 客户端此前同步的轮次起点
     * @return 是否完成本次发放
     */
    @Transactional
    public boolean claim(String roleId, long expectedPeriod) {
        roles.lockRole(roleId);
        ActivityPeriod period = activities.cycle(ACTIVITY_ID);
        if (period.start != expectedPeriod || Instant.now().getEpochSecond() < period.start
                || mapper.claimed(roleId, period.start).isPresent()) {
            return false;
        }
        Optional<GamePaymentOrder> paid = paid(roleId, period);
        if (!paid.isPresent()) {
            return false;
        }
        GamePaymentOrder order = paid.get();
        inventory.grant(roleId, DIAMOND_ITEM_ID, diamondRebate(order.paysid));
        for (int offset = 0; offset < ITEMS.size(); offset += 3) {
            inventory.grant(roleId, ITEMS.get(offset + 1), ITEMS.get(offset + 2));
        }
        mapper.insertClaim(roleId, period.start, order);
        return true;
    }

    /**
     * 读取当前轮次内第一笔符合档位的已确认模拟订单
     *
     * @param roleId 当前认证角色
     * @param period 当前活动窗口
     * @return 符合条件的订单
     */
    private Optional<GamePaymentOrder> paid(String roleId, ActivityPeriod period) {
        return mapper.firstPaidOrder(roleId, period.start * 1000, period.end * 1000);
    }
}
