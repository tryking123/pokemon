package com.pokemon.local.gacha;

import com.pokemon.local.quest.api.entity.QuestTask;
import java.util.Collections;
import java.util.List;

/**
 * 已结算的一次扭蛋请求和对应奖励
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:01
 */
public final class GachaDraw {
    public final int errno;
    public final List<Reward> rewards;
    public final int diamondsSpent;
    public final boolean couponSpent;
    public final boolean changed;
    public final GachaState state;
    public final List<QuestTask> tasks;

    /**
     * 保存抽奖结算和需要同步的角色数据
     *
     * @param errno 原游戏错误码
     * @param rewards 对应每次抽取的奖励
     * @param diamondsSpent 本次扣除的角色钻石
     * @param couponSpent 是否消耗抽奖券
     * @param changed 本次是否新结算
     * @param state 当前扭蛋次数与冷却
     * @param tasks 本次新推进的任务
     */
    GachaDraw(int errno, List<Reward> rewards, int diamondsSpent, boolean couponSpent,
              boolean changed, GachaState state, List<QuestTask> tasks) {
        this.errno = errno;
        this.rewards = Collections.unmodifiableList(rewards);
        this.diamondsSpent = diamondsSpent;
        this.couponSpent = couponSpent;
        this.changed = changed;
        this.state = state;
        this.tasks = tasks;
    }

    /**
     * 构造未通过验证的无变更结果
     *
     * @param code 原游戏错误码
     * @param state 当前扭蛋状态
     * @return 拒绝抽取的结果
     */
    static GachaDraw failure(int code, GachaState state) {
        return new GachaDraw(code, Collections.emptyList(), 0, false, false, state, Collections.emptyList());
    }

    /**
     * 扭蛋结果中的一份道具或精灵。
     *
     * @author tongruiyuan
     * @since 2026-11-18 21:37
     */
    public static final class Reward {
        public final int type;
        public final int id;

        /**
         * 记录原始奖励编号。
         *
         * @param type 原协议奖励类型
         * @param id 原版道具或精灵配置编号
         */
        Reward(int type, int id) {
            this.type = type;
            this.id = id;
        }
    }
}
