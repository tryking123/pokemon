package com.pokemon.offline.payment.api.entity;

/**
 * 角色在当前新手充值活动内的返利状态
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:25
 */
public final class FirstRechargeState {
    /** 周期开始的 Unix 秒 */
    public final long periodStart;
    /** 下次计算周期的 Unix 秒 */
    public final long nextRefresh;
    /** 对应实付订单的充值档位，无符合订单时为零 */
    public final int paysid;
    /** 原协议状态，零为不可领、一为待领取、二为已领取 */
    public final int flag;

    /**
     * 保存客户端需同步的返利状态
     *
     * @param periodStart 当前周期
     * @param nextRefresh 下次刷新时刻
     * @param paysid 对应的充值档位
     * @param flag 领取状态
     */
    public FirstRechargeState(long periodStart, long nextRefresh, int paysid, int flag) {
        this.periodStart = periodStart;
        this.nextRefresh = nextRefresh;
        this.paysid = paysid;
        this.flag = flag;
    }
}
