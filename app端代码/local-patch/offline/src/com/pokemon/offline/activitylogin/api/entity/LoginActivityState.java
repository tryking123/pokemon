package com.pokemon.offline.activitylogin.api.entity;

import java.util.List;

/**
 * 七日登录周期内的到访及领取状态
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:03
 */
public final class LoginActivityState {
    /** 本轮累计登录自然日数 */
    public final int days;
    /** 本轮已领取奖励序号 */
    public final List<Integer> record;
    /** 当前活动周期起点 Unix 秒 */
    public final long periodStart;
    /** 下一次到访计数可能变化的 Unix 秒 */
    public final long nextRefresh;

    /**
     * 生成角色状态快照
     *
     * @param days 本轮累计登录天数
     * @param record 本轮已领序号
     * @param periodStart 当前周期起点
     * @param nextRefresh 下一次自然日或周期开始
     */
    public LoginActivityState(int days, List<Integer> record, long periodStart, long nextRefresh) {
        this.days = days;
        this.record = record;
        this.periodStart = periodStart;
        this.nextRefresh = nextRefresh;
    }
}
