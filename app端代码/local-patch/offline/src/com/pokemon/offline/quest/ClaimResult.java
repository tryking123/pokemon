package com.pokemon.offline.quest;

/**
 * 任务或章节领奖结果，用于网关发送真实增加的资产
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:46
 */
public final class ClaimResult {
    public final int errno;
    public final QuestReward reward;
    public final boolean changed;

    /**
     * 构造奖励领取响应
     *
     * @param errno 原游戏错误码
     * @param reward 新发放奖励，重复请求时为空
     * @param changed 本次是否实际执行领奖
     */
    ClaimResult(int errno, QuestReward reward, boolean changed) {
        this.errno = errno;
        this.reward = reward;
        this.changed = changed;
    }

    /**
     * 构造无资产变更的幂等结果
     *
     * @param errno 原游戏错误码
     * @return 领取结果
     */
    static ClaimResult result(int errno) {
        return new ClaimResult(errno, null, false);
    }
}
