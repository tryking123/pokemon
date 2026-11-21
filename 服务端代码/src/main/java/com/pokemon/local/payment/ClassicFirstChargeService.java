package com.pokemon.local.payment;

import com.pokemon.local.activity.ActivityService;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.payment.api.entity.FirstRechargeState;
import com.pokemon.local.role.RoleService;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 经典首充活动 10011 的资格和背包奖励
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:43
 */
@Service
public class ClassicFirstChargeService {
    private static final int ACTIVITY_ID = 10011;
    // 数据源为底包 shouchong_proto[1].rew。
    private static final List<Integer> REWARDS = Collections.unmodifiableList(Arrays.asList(
            1, 34021, 1, 1, 34016, 1, 1, 21034, 50, 1, 21000, 200));
    private final ActivityService activities;
    private final GamePaymentMapper orders;
    private final RoleService roles;
    private final InventoryService inventory;

    /**
     * 绑定运营活动、角色和真实本地订单
     *
     * @param activities 活动日历
     * @param orders 游戏订单和首充进度
     * @param roles 当前角色锁
     * @param inventory 角色背包发放
     */
    ClassicFirstChargeService(ActivityService activities, GamePaymentMapper orders, RoleService roles,
                              InventoryService inventory) {
        this.activities = activities;
        this.orders = orders;
        this.roles = roles;
        this.inventory = inventory;
    }

    /**
     * 查询底包经典首充四件奖品
     *
     * @return 类型、编号、数量三元组列表
     */
    public List<Integer> rewards() {
        return REWARDS;
    }

    /**
     * 查询当前周期是否已确认充值及领取
     *
     * @param roleId 当前角色编号
     * @return 当期旗标和周期边界
     */
    public FirstRechargeState snapshot(String roleId) {
        ActivityPeriod period = activities.cycle(ACTIVITY_ID);
        int flag = flag(roleId, period);
        return new FirstRechargeState(period.start, period.end, 0, flag);
    }

    /**
     * 在锁内根据已确认订单校验并发放一次经典礼包
     *
     * @param roleId 当前角色编号
     * @param expectedPeriod 客户端看到的周期起点
     * @return 是否发放成功
     */
    @Transactional
    public boolean claim(String roleId, long expectedPeriod) {
        roles.lockRole(roleId);
        ActivityPeriod period = activities.cycle(ACTIVITY_ID);
        if (period.start != expectedPeriod || flag(roleId, period) != FirstRechargeService.CLAIMABLE) {
            return false;
        }
        for (int offset = 0; offset < REWARDS.size(); offset += 3) {
            inventory.grant(roleId, REWARDS.get(offset + 1), REWARDS.get(offset + 2));
        }
        orders.claimClassic(roleId, period.start);
        return true;
    }

    /**
     * 解析原版 0/1/2 三种首充状态
     *
     * @param roleId 当前角色编号
     * @param period 本轮首充活动时间窗
     * @return 首充未达成、可领取或已领取
     */
    private int flag(String roleId, ActivityPeriod period) {
        if (orders.classicClaimed(roleId, period.start)) {
            return FirstRechargeService.CLAIMED;
        }
        if (Instant.now().getEpochSecond() >= period.start
                && orders.paidInPeriod(roleId, period.start * 1000, period.end * 1000)) {
            return FirstRechargeService.CLAIMABLE;
        }
        return FirstRechargeService.LOCKED;
    }
}
