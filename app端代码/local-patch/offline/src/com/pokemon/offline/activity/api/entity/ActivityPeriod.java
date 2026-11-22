package com.pokemon.offline.activity.api.entity;

/**
 * 本地运营活动当前可参与的周期
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:04
 */
public final class ActivityPeriod {
    /** 原客户端运营活动编号 */
    public final int id;
    /** 周期起点，Unix 秒，含该时刻 */
    public final long start;
    /** 周期终点，Unix 秒，不含该时刻 */
    public final long end;
    /** 活动结束后可看结果的截止时间，Unix 秒 */
    public final long showUntil;
    /** 当前为周期内第几天，从一开始 */
    public final int day;

    /**
     * 生成本次活动的计算窗口
     *
     * @param id 活动编号
     * @param start 起点
     * @param end 终点
     * @param day 周期内日序
     */
    public ActivityPeriod(int id, long start, long end, int day) {
        this(id, start, end, end, day);
    }

    /**
     * 生成有结束展示阶段的限时活动窗口。
     *
     * @param id 活动编号
     * @param start 起点
     * @param end 参与终点
     * @param showUntil 展示终点
     * @param day 周期内日序
     */
    public ActivityPeriod(int id, long start, long end, long showUntil, int day) {
        this.id = id;
        this.start = start;
        this.end = end;
        this.showUntil = showUntil;
        this.day = day;
    }
}
