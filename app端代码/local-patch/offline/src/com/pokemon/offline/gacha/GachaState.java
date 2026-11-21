package com.pokemon.offline.gacha;

import java.time.LocalDate;

/**
 * 本地普通扭蛋的日期和剩余次数，以及终身抽取记录
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:01
 */
public final class GachaState {
    final LocalDate day;
    final LocalDate advancedFreeDay;
    final int used;
    final int friendTotal;
    public final long normalTime;
    public final long friendPoints;
    public final int normalTotal;
    public final int advancedTotal;
    public final int normalRemaining;
    public final int advancedFree;
    public final long nextReset;

    /**
     * 生成已经按当地日期计算的扭蛋状态
     *
     * @param day 北京时间业务日
     * @param used 今日普通抽取次数
     * @param normalTime 最近普通抽取的 UTC 秒数
     * @param normalTotal 历史普通次数
     * @param advancedTotal 历史高级次数
     * @param advancedFreeDay 最近一次使用高级免费抽取的北京时间日期
     * @param dailyAdvancedFree 本地是否配置每天一次高级免费抽取
     * @param friendTotal 历史友情抽取次数
     * @param friendPoints 可用友情点
     * @param nextReset 下次重置的 UTC 秒数
     */
    GachaState(LocalDate day, int used, long normalTime, int normalTotal, int advancedTotal,
               LocalDate advancedFreeDay, boolean dailyAdvancedFree, int friendTotal, long friendPoints, long nextReset) {
        this.day = day;
        this.advancedFreeDay = advancedFreeDay;
        this.used = used;
        this.friendTotal = friendTotal;
        this.friendPoints = friendPoints;
        this.normalTime = normalTime;
        this.normalTotal = normalTotal;
        this.advancedTotal = advancedTotal;
        this.normalRemaining = GachaService.NORMAL_LIMIT - used;
        this.advancedFree = dailyAdvancedFree && !day.equals(advancedFreeDay) ? 1 : 0;
        this.nextReset = nextReset;
    }
}
