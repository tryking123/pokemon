package com.pokemon.offline.payment;

import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 超级加速特权卡的有效期、每日材料及挂机收益。
 *
 * @author tongruiyuan
 * @since 2026-11-19 21:23
 */
@Service
public class SpeedCardService {
    private static final long WEEK = 604800;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<Integer> RAW = Collections.unmodifiableList(
            Arrays.asList(1, 21024, 3000, 1, 21026, 500, 1, 20003, 20));
    private final GamePaymentMapper payments;
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final RoleService roles;

    /**
     * 绑定确认流水、每日凭据与角色背包。
     * @param payments 原模拟订单映射
     * @param jdbc 每日领用凭据
     * @param inventory 背包及邮件发放
     * @param roles 领取使用的角色锁
     */
    public SpeedCardService(GamePaymentMapper payments, JdbcTemplate jdbc, InventoryService inventory, RoleService roles) {
        this.payments = payments;
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.roles = roles;
    }

    /**
     * 查询本时段的有效激活，每笔普通 198 元订单维持原充值用途。
     * @param roleId 角色编号
     * @param from 时段起点秒
     * @param until 时段终点秒，不含
     * @return 与时段有交集的激活起点，按时间排序
     */
    private List<Long> activations(String roleId, long from, long until) {
        List<Long> result = new ArrayList<>();
        long previousEnd = 0;
        for (long confirmed : payments.confirmationTimes(roleId, 9, 0, until * 1000)) {
            long start = confirmed / 1000;
            if (start >= previousEnd) {
                previousEnd = start + WEEK;
                if (previousEnd > from && start < until) {
                    result.add(start);
                }
            }
        }
        return result;
    }

    /**
     * 读取当前时刻属于哪一次激活。
     * @param roleId 角色编号
     * @param now 当前秒数
     * @return 激活起点，零表示未激活
     */
    private long activeSince(String roleId, long now) {
        List<Long> found = activations(roleId, now, now + 1);
        return found.isEmpty() ? 0 : found.get(0);
    }

    /**
     * 检查当天已领或者本次权益七份日奖已领完。
     * @param roleId 角色编号
     * @param activation 当前激活起点
     * @param day 北京业务日
     * @return 是否已经用完可领资格
     */
    private boolean claimed(String roleId, long activation, String day) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_speed_card_claim WHERE role_id=? "
                + "AND business_day=?", Integer.class, roleId, day) > 0
                || jdbc.queryForObject("SELECT COUNT(*) FROM local_speed_card_claim WHERE role_id=? "
                + "AND activated_at=?", Integer.class, roleId, activation) >= 7;
    }

    /**
     * 查询原特权界面使用的剩余秒数、今日领奖状态与奖励预览。
     * @param roleId 当前角色
     * @return 原 privilegeinfo 字段
     */
    public Map<String, Object> panel(String roleId) {
        Instant now = Instant.now();
        long start = activeSince(roleId, now.getEpochSecond());
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("dayNum", start == 0 ? 0 : start + WEEK - now.getEpochSecond());
        info.put("flag", start > 0 && claimed(roleId, start, now.atZone(ZONE).toLocalDate().toString()) ? 1 : 0);
        info.put("raw", RAW);
        return info;
    }

    /**
     * 领取当前有效权益的北京自然日日奖。
     * @param roleId 当前角色
     * @return 正常到账的道具，含邮件时可能为空；无资格时返回空引用
     */
    @Transactional
    public Map<Integer, Long> claim(String roleId) {
        roles.lockRole(roleId);
        Instant now = Instant.now();
        long start = activeSince(roleId, now.getEpochSecond());
        String day = now.atZone(ZONE).toLocalDate().toString();
        if (start == 0 || claimed(roleId, start, day)) {
            return null;
        }
        jdbc.update("INSERT INTO local_speed_card_claim(role_id,business_day,activated_at) VALUES (?,?,?)",
                roleId, day, start);
        Map<Integer, Long> items = new LinkedHashMap<>();
        for (int i = 0; i < RAW.size(); i += 3) {
            items.put(RAW.get(i + 1), RAW.get(i + 2).longValue());
        }
        return inventory.grantItemsOrMail(roleId, "speed-card:" + day, items);
    }

    /**
     * 计算真实挂机期间或当前快速战斗的有效加成秒数。
     * @param roleId 角色编号
     * @param from 挂机起点，快速战斗时为扣费时刻
     * @param seconds 实际纳入收益的秒数
     * @param quick 是否立即结算两小时的快速战斗
     * @return 金币、经验使用的加成区间
     */
    public Bonus bonus(String roleId, long from, int seconds, boolean quick) {
        Bonus bonus = new Bonus();
        if (seconds <= 0) {
            return bonus;
        }
        for (long start : activations(roleId, from, from + (quick ? 1 : seconds))) {
            long left = Math.max(from, start) - start;
            long right = quick ? left + seconds : Math.min(from + seconds, start + WEEK) - start;
            bonus.covered += right - left;
            bonus.pairs += right / 2 - left / 2;
        }
        return bonus;
    }

    /**
     * 挂机与特权卡交集的秒数，用于避免分次结算造成半点经验舍入误差。
     * @author tongruiyuan
     * @since 2026-11-19 21:23
     */
    public static final class Bonus {
        private long covered;
        private long pairs;

        /**
         * 获取金币额外百分之百收益。
         * @param rate 普通每秒金币
         * @return 额外金币
         */
        public long coins(long rate) {
            return covered * rate;
        }

        /**
         * 获取角色或精灵的额外百分之五十经验。
         * @param rate 普通每秒经验
         * @return 额外经验
         */
        public long experience(long rate) {
            return covered * (rate / 2) + pairs * (rate % 2);
        }
    }
}
